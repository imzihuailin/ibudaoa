"""Offline entrypoint integration tests; requires Python 3 and a JDK 17.

Run: python3 tests/oneclick/test_entrypoints.py /path/to/jdk17 [/path/to/pwsh]
All managed paths are redirected to temporary fixtures. No real user files are touched.
"""
import json
import os
from pathlib import Path
import pty
import re
import select
import subprocess
import sys
import tempfile
import time
import termios
import io
import tarfile
import zipfile
import hashlib

PROJECT = Path(__file__).resolve().parents[2]
JDK = Path(sys.argv[1]).resolve()
PWSH = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else None
OWNER = 'ibudaoa-oneclick-v1'


def run(command, **kwargs):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, **kwargs)
    assert result.returncode == 0, result.stdout
    return result.stdout


with tempfile.TemporaryDirectory(prefix='ibudaoa-entry-test-') as directory:
    root = Path(directory)
    classes = root / 'classes'
    classes.mkdir()
    run([str(JDK / 'bin/javac'), '-d', str(classes), str(PROJECT / 'scripts/OneClick.java'),
         str(PROJECT / 'tests/oneclick/OneClickTest.java'), str(PROJECT / 'tests/oneclick/DownloadTest.java')])
    output = run([str(JDK / 'bin/java'), '-cp', str(classes), 'OneClickTest'])
    result = re.search(r'PASS \d+ checks', output)
    assert result, output
    print('Shared engine: ' + result.group())

    import shlex
    for snippet in re.findall(r'```sh\n(.*?)```', (PROJECT / 'README.md').read_text(), re.S):
        run(['bash', '-n', '-c', snippet])
        if snippet.startswith('bash -c '):
            run(['bash', '-n', '-c', shlex.split(snippet)[2]])
    print('README Bash command syntax: PASS')

    # An actual terminal verifies star masking, editing, paste, and reuse.
    master, slave = pty.openpty()
    process = subprocess.Popen([str(JDK / 'bin/java'), '-cp', str(classes), 'OneClickTest', 'key'],
                               stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
    os.close(slave)
    transcript = b''
    sent_invalid = sent_key = sent_reuse = sent_binding = False
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if select.select([master], [], [], 0.2)[0]:
            try:
                data = os.read(master, 65536)
            except OSError:
                break
            if not data:
                break
            transcript += data
            if not sent_binding and '确认已完成绑定后，按回车继续'.encode() in transcript:
                assert '请输入高德'.encode() not in transcript, transcript.decode(errors='replace')
                os.write(master, b'\n')
                sent_binding = True
            if not sent_invalid and '请输入高德'.encode() in transcript:
                os.write(master, b'invalid\n')
                sent_invalid = True
            if sent_invalid and not sent_key and '请重新输入'.encode() in transcript:
                # Wait for the next prompt so input cannot arrive before echo is disabled.
                if transcript.count('请输入高德'.encode()) >= 2:
                    os.write(master, b'a' * 31 + b'b\x7fa\n')
                    sent_key = True
            if sent_key and not sent_reuse and '直接回车复用'.encode() in transcript:
                os.write(master, b'\n')
                sent_reuse = True
    os.close(master)
    assert process.wait(timeout=5) == 0, transcript.decode(errors='replace')
    assert b'KEY_PASS' in transcript and b'a' * 32 not in transcript, transcript.decode(errors='replace')
    assert b'*' * 32 in transcript, transcript.decode(errors='replace')
    assert b'FOLDER:\x1b]8;;file:///' in transcript and b'/config/' in transcript, transcript.decode(errors='replace')
    print('Star-masked Key + invalid retry / paste / backspace / reuse (real PTY): PASS')

    master, slave = pty.openpty()
    original_terminal = termios.tcgetattr(slave)
    process = subprocess.Popen([str(JDK / 'bin/java'), '-cp', str(classes), 'OneClickTest', 'cancel-key'],
                               stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
    transcript = b''
    deadline = time.monotonic() + 15
    sent_cancel = False
    while time.monotonic() < deadline and process.poll() is None:
        if select.select([master], [], [], 0.2)[0]:
            transcript += os.read(master, 65536)
            if not sent_cancel and '请输入高德'.encode() in transcript:
                os.write(master, b'abc\x04')
                sent_cancel = True
    assert process.wait(timeout=5) == 0, transcript.decode(errors='replace')
    while select.select([master], [], [], 0)[0]:
        transcript += os.read(master, 65536)
    assert b'KEY_CANCEL_PASS' in transcript and b'abc' not in transcript, transcript.decode(errors='replace')
    assert termios.tcgetattr(slave) == original_terminal, 'terminal settings not restored after cancellation'
    os.close(slave)
    os.close(master)
    print('Masked input cancellation + terminal restoration (real PTY): PASS')

    # Exercise archive fallback and native offline uninstall with Java/network stubbed.
    fixtures = {}
    files = {'gradlew': b'#!/bin/sh', 'app/build.gradle.kts': b'// fixture',
             'scripts/OneClick.java': b'// fixture engine',
             'scripts/download-manifest.properties': b'# fixture'}
    for kind in ['valid', 'traversal', 'link']:
        archive = root / (kind + '.tar.gz')
        with tarfile.open(archive, 'w:gz') as tar:
            for name, data in files.items():
                entry = tarfile.TarInfo('ibudaoa-main/' + name)
                entry.size = len(data)
                tar.addfile(entry, io.BytesIO(data))
            if kind == 'traversal':
                entry = tarfile.TarInfo('ibudaoa-main/../../escape')
                entry.size = 1
                tar.addfile(entry, io.BytesIO(b'x'))
            if kind == 'link':
                entry = tarfile.TarInfo('ibudaoa-main/link')
                entry.type = tarfile.SYMTYPE
                entry.linkname = str(root)
                tar.addfile(entry)
        fixtures[kind] = archive
    html = root / 'html.tar.gz'
    html.write_text('<html>not an archive</html>')
    fixtures['html'] = html
    zip_fixture = root / 'source.zip'
    with zipfile.ZipFile(zip_fixture, 'w') as archive:
        for name, data in files.items():
            archive.writestr('ibudaoa-main/' + name, data)

    bad_zips = []
    for kind in ['traversal', 'link', 'html']:
        candidate = root / (kind + '.zip')
        if kind == 'html':
            candidate.write_text('<html>not a zip</html>')
        else:
            with zipfile.ZipFile(candidate, 'w') as archive:
                for name, data in files.items():
                    archive.writestr('ibudaoa-main/' + name, data)
                if kind == 'traversal':
                    archive.writestr('ibudaoa-main/../../escape', 'bad')
                else:
                    entry = zipfile.ZipInfo('ibudaoa-main/link')
                    entry.create_system = 3
                    entry.external_attr = 0o120777 << 16
                    archive.writestr(entry, str(root))
        bad_zips.append(candidate)

    fake = root / 'bin'
    fake.mkdir()
    fake_java = fake / 'java'
    fake_java.write_text('''#!/usr/bin/env bash
if [ "$1" = -version ]; then echo 'openjdk version "17.0.1"'; exit 0; fi
printf '%s\\n' "$@" > "$TEST_CALLS"
exit "${FAKE_JAVA_RESULT:-0}"
''')
    fake_java.chmod(0o755)
    (fake / 'javac').write_text('')
    (fake / 'javac').chmod(0o755)
    curl = fake / 'curl'
    curl.write_text('''#!/usr/bin/env python3
import os, sys, shutil
from pathlib import Path
args=sys.argv[1:]
url=next(a for a in args if a.startswith('https://'))
with open(os.environ['TEST_DOWNLOADS'],'a') as log: log.write(url+'\\n')
if os.environ.get('FAKE_NETWORK_FAIL') == '1': sys.exit(22)
assert '/archive/refs/heads/main.tar.gz' in url, url
if os.environ.get('FAIL_FIRST') == '1' and url.startswith('https://ghfast.top/'): sys.exit(22)
source=os.environ['TEST_ARCHIVE']
if os.environ.get('FIRST_BAD') and url.startswith('https://ghfast.top/'):
 source=os.environ['FIRST_BAD']
shutil.copyfile(source,args[args.index('-o')+1])
''')
    curl.chmod(0o755)
    shell = root / 'oneclick.sh'
    shell.write_text((PROJECT / 'scripts/oneclick.sh').read_text().replace('$HOME', '${TEST_USER_ROOT}'))
    user_root = root / '用户 space'
    user_root.mkdir()
    env = dict(os.environ, TEST_USER_ROOT=str(user_root), JAVA_HOME=str(root),
               TEST_CALLS=str(root / 'calls'), TEST_DOWNLOADS=str(root / 'downloads'),
               TEST_ARCHIVE=str(fixtures['valid']), PATH=str(fake) + os.pathsep + os.environ['PATH'])
    assert '没有需要清理' in run(['bash', str(shell), 'uninstall'], env=env)
    run(['bash', str(shell), 'build'], env=env)
    digest = hashlib.sha256(fixtures['valid'].read_bytes()).hexdigest()
    assert digest in (root / 'calls').read_text()
    cache = user_root / '.cache/ibudaoa'
    assert (cache / 'OneClick.java').is_file() and (cache / 'uninstall.sh').is_file()
    assert (cache / 'work/source.sha256').read_text() == digest
    config = user_root / '.local/share/ibudaoa/config'
    config.mkdir(parents=True)
    (config / '.owner').write_text(OWNER)
    (config / 'signing.keystore').write_text('keep signature')
    (config / 'amap.key').write_text('a' * 32)
    for kind in ['html', 'traversal', 'link']:
        run(['bash', str(shell), 'build'], env=dict(env, FIRST_BAD=str(fixtures[kind])))
        assert not (root / 'escape').exists()
        assert (config / 'signing.keystore').read_text() == 'keep signature'
    run(['bash', str(shell), 'build'], env=dict(env, FAIL_FIRST='1'))
    assert 'https://gh-proxy.org/' in (root / 'downloads').read_text()
    for kind in ['html', 'traversal', 'link']:
        result = subprocess.run(['bash', str(shell), 'build'], env=dict(env, TEST_ARCHIVE=str(fixtures[kind])), capture_output=True)
        assert result.returncode != 0
    assert 'api.github.com' not in (root / 'downloads').read_text()
    failed = subprocess.run(['bash', str(shell), 'build'], env=dict(env, FAKE_NETWORK_FAIL='1'), capture_output=True)
    assert failed.returncode != 0
    assert not (user_root / '.cache/.ibudaoa-oneclick.lock').exists(), 'failed run leaked lock'

    output = root / '输出 space'
    output.mkdir()
    apk = output / 'ibudaoa.apk'
    apk.write_bytes(b'our apk')
    (output / 'keep.txt').write_text('user file')
    (config / 'output.path').write_text(str(output))
    (config / 'output.created').write_text(str(output))
    (config / 'output.sha256').write_text(hashlib.sha256(apk.read_bytes()).hexdigest())
    offline = dict(env, FAKE_NETWORK_FAIL='1', FAKE_JAVA_RESULT='99')
    calls_before = (root / 'calls').read_bytes()
    downloads_before = (root / 'downloads').read_bytes()
    local = cache / 'uninstall.sh'
    assert '已取消' in run(['bash', str(local), 'uninstall'], input='\n', env=offline)
    assert apk.exists() and config.exists()
    apk.write_bytes(b'replaced')
    replaced = subprocess.run(['bash', str(local), 'uninstall'], input='y\n', env=offline, text=True, capture_output=True)
    assert replaced.returncode != 0 and apk.exists() and config.exists()
    apk.write_bytes(b'our apk')
    cleanup = run(['bash', str(local), 'uninstall'], input='y\n', env=offline)
    assert '清理完成' in cleanup and not cache.exists() and not config.exists() and not apk.exists()
    assert (output / 'keep.txt').read_text() == 'user file'
    assert calls_before == (root / 'calls').read_bytes(), 'uninstall invoked Java'
    assert downloads_before == (root / 'downloads').read_bytes(), 'uninstall used network'
    assert '没有需要清理' in run(['bash', str(shell), 'uninstall'], env=offline)
    print('Bash archive fallback / invalid HTML / traversal / links / snapshot / config preservation / fully offline native cleanup: PASS')

    if PWSH:
        runtime = root / 'ps-runtime'
        runtime.mkdir(mode=0o700)
        psenv = dict(os.environ, XDG_CACHE_HOME=str(root / 'ps-cache'),
                     XDG_CONFIG_HOME=str(root / 'ps-config'), XDG_DATA_HOME=str(root / 'ps-data'),
                     XDG_RUNTIME_DIR=str(runtime))
        # Parse both the script and the exact documented one-line commands.
        checker = root / 'parse.ps1'
        checker.write_text('''param([string]$Project)
$tokens=$null; $errors=$null
[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $Project 'scripts/oneclick.ps1'),[ref]$tokens,[ref]$errors) > $null
if($errors.Count){throw ($errors | Out-String)}
$readme=[IO.File]::ReadAllText((Join-Path $Project 'README.md'))
foreach($match in [regex]::Matches($readme,'(?s)```powershell\\r?\\n(.*?)```')) {
 [System.Management.Automation.Language.Parser]::ParseInput($match.Groups[1].Value,[ref]$tokens,[ref]$errors) > $null
 if($errors.Count){throw ($errors | Out-String)}
}
'PowerShell entry + README command syntax: PASS'
''')
        print(run([str(PWSH), '-NoProfile', '-File', str(checker), str(PROJECT)], env=psenv).strip())
        cleanup = run([str(PWSH), '-NoProfile', '-File', str(PROJECT / 'tests/oneclick/Test-Cleanup.ps1'),
                       str(PROJECT), str(root / 'cleanup-regression')], env=psenv)
        print(cleanup.splitlines()[-1])
        # Execute the PowerShell cleanup path on Linux with a fake Windows environment.
        # This validates script control flow, not Windows cmd.exe or ACL behavior.
        appdata = root / 'local app data'
        appdata.mkdir()
        psenv.update(OS='Windows_NT', PROCESSOR_ARCHITECTURE='AMD64', LOCALAPPDATA=str(appdata))
        original_ps = (PROJECT / 'scripts/oneclick.ps1').read_text(encoding='utf-8-sig')
        start = original_ps.index('function Get-Download(')
        end = original_ps.index('function Get-SourceSnapshot', start)
        transport = root / 'transport.ps1'
        transport.write_text(original_ps[start:end], encoding='utf-8-sig')
        # Control-flow fixtures replace transport only; the exact real helper is tested over HTTPS separately.
        mock_transport = '''function Get-Download([string[]]$Urls,[string]$Destination,[string]$Sha256='') {
 Invoke-WebRequest -Uri $Urls[0] -OutFile $Destination
}
'''
        ps_script = root / 'oneclick-fixture.ps1'
        ps_script.write_text(original_ps[:start] + mock_transport + original_ps[end:], encoding='utf-8-sig')
        wrapper = root / 'entry-fixture.ps1'
        wrapper.write_text('''param([string]$Mode, [switch]$Recover)
if($env:TEST_ASCII_CONSOLE -eq '1') {
 [Console]::OutputEncoding=[Text.Encoding]::ASCII
 [Console]::InputEncoding=[Text.Encoding]::ASCII
 $OutputEncoding=[Text.Encoding]::ASCII
}
function Get-CimInstance {
 if($env:TEST_ACTIVE_WORKER -eq '1') {
  [pscustomobject]@{ProcessId=999999;ParentProcessId=0;Name='java.exe';CommandLine=$env:LOCALAPPDATA+'/ibudaoa/tools/jdk/bin/java.exe OneClick.java'}
 }
}
function Invoke-WebRequest {
 param($Uri,$OutFile)
 if($env:TEST_NETWORK_FAIL -eq '1'){throw 'fixture network failure'}
 if($env:TEST_FAIL_FIRST -eq '1' -and $Uri -like 'https://ghfast.top/*'){throw 'fixture first-source failure'}
 if($Uri -like '*main.zip') {
  if($env:TEST_GATE) {
   [IO.File]::WriteAllText($env:TEST_GATE,'ready')
   while($true){[Threading.Thread]::Sleep(100)}
  }
  $archive=$env:TEST_ARCHIVE
  if($env:TEST_BAD_FIRST -and $Uri -like 'https://ghfast.top/*'){$archive=$env:TEST_BAD_FIRST}
  [IO.File]::Copy($archive,$OutFile,$true)
 } else {throw 'unexpected download'}
}
& $env:TEST_ENTRY $Mode -Recover:$Recover
exit $LASTEXITCODE
''')
        psenv.update(TEST_ENTRY=str(ps_script), TEST_CALLS=str(root / 'ps-calls'), TEST_ARCHIVE=str(zip_fixture))
        command = [str(PWSH), '-NoProfile', '-File', str(wrapper)]
        first = run(command + ['uninstall'], env=psenv)
        assert '没有需要清理' in first, repr(first)
        ps_cache = appdata / 'ibudaoa'
        (ps_cache / 'tools/jdk/bin').mkdir(parents=True)
        (ps_cache / '.owner').write_text(OWNER)
        (ps_cache / 'OneClick.java').write_text('// fixture')
        ps_java = ps_cache / 'tools/jdk/bin/java.exe'
        ps_java.write_text(fake_java.read_text())
        ps_java.chmod(0o755)
        javac = ps_java.parent / 'javac.exe'
        javac.write_text('#!/usr/bin/env bash\necho "javac 17.0.1"\n')
        javac.chmod(0o755)
        config = ps_cache / 'config'
        config.mkdir()
        (config / '.owner').write_text(OWNER)
        (config / 'amap.key').write_text('a' * 32)
        (config / 'signing.keystore').write_bytes(b'keep original signing key')

        # Existing 0557c0b lock directories must be retired without manual deletion.
        legacy_lock = appdata / '.ibudaoa-oneclick.lock'
        legacy_lock.mkdir()
        partial_bootstrap = ps_cache / 'bootstrap'
        partial_bootstrap.mkdir()
        (partial_bootstrap / 'jdk.zip').write_bytes(b'interrupted archive')
        (partial_bootstrap / 'unpacked').mkdir()
        (partial_bootstrap / 'unpacked/partial').write_bytes(b'incomplete extraction')
        resumed = run(command + ['build'], env=psenv)
        assert '已恢复上次中断' in resumed and not legacy_lock.exists(), resumed
        assert not partial_bootstrap.exists(), 'stale download/extraction was not cleaned'
        assert (config / 'amap.key').read_text() == 'a' * 32
        assert (config / 'signing.keystore').read_bytes() == b'keep original signing key'

        run(command + ['build'], env=dict(psenv, TEST_FAIL_FIRST='1'))
        for bad_archive in bad_zips:
            run(command + ['build'], env=dict(psenv, TEST_BAD_FIRST=str(bad_archive)))
            invalid = subprocess.run(command + ['build'], env=dict(psenv, TEST_ARCHIVE=str(bad_archive)), capture_output=True, text=True)
            assert invalid.returncode != 0 and '所有源码下载来源均失败' in invalid.stdout, invalid.stdout
            assert not (root / 'escape').exists()
            assert (config / 'signing.keystore').read_bytes() == b'keep original signing key'

        # A legacy worker or orphan JVM is still a real running task; never overwrite its files.
        legacy_lock.mkdir()
        busy = subprocess.run(command + ['build'], env=dict(psenv, TEST_ACTIVE_WORKER='1'), capture_output=True, text=True)
        assert busy.returncode != 0 and '仍在运行' in busy.stdout, busy.stdout
        assert legacy_lock.exists()
        run(command + ['build'], env=psenv)

        # Hold the real named mutex, reject a second operation, then kill its owner without finally.
        gate = root / 'mutex-ready'
        owner = subprocess.Popen(command + ['build'], env=dict(psenv, TEST_GATE=str(gate)),
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 15
            while not gate.exists() and owner.poll() is None and time.monotonic() < deadline:
                time.sleep(0.05)
            assert gate.exists(), owner.communicate(timeout=2)[0].decode(errors='replace')
            for mode in ['build', 'uninstall']:
                busy = subprocess.run(command + [mode], env=psenv, capture_output=True, text=True)
                assert busy.returncode != 0 and '另一个构建或清理正在运行' in busy.stdout, busy.stdout
            owner.kill()
            owner.wait(timeout=5)
        finally:
            if owner.poll() is None:
                owner.kill()
            owner.communicate(timeout=5)
        run(command + ['build'], env=psenv)

        # Ctrl+C also allows another invocation; PowerShell may run finally or abandon the mutex.
        gate.unlink()
        owner = subprocess.Popen(command + ['build'], env=dict(psenv, TEST_GATE=str(gate)),
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 15
            while not gate.exists() and owner.poll() is None and time.monotonic() < deadline:
                time.sleep(0.05)
            assert gate.exists()
            import signal
            owner.send_signal(signal.SIGINT)
            try:
                owner.wait(timeout=5)
            except subprocess.TimeoutExpired:
                owner.kill()
                owner.wait(timeout=5)
        finally:
            if owner.poll() is None:
                owner.kill()
            owner.communicate(timeout=5)
        run(command + ['build'], env=psenv)

        failed = subprocess.run(command + ['build'], env=dict(psenv, TEST_NETWORK_FAIL='1', TEST_ASCII_CONSOLE='1'), capture_output=True, text=True)
        assert failed.returncode != 0 and '失败阶段' in failed.stdout, failed.stdout
        assert '????' not in failed.stdout
        run(command + ['build'], env=psenv)
        ps_output = root / 'PS 输出 space'
        ps_output.mkdir()
        ps_apk = ps_output / 'ibudaoa.apk'
        ps_apk.write_bytes(b'our apk')
        (ps_output / 'keep.txt').write_text('keep')
        (config / 'output.path').write_text(str(ps_output))
        (config / 'output.created').write_text(str(ps_output))
        (config / 'output.sha256').write_text(hashlib.sha256(ps_apk.read_bytes()).hexdigest())
        offline = dict(psenv, TEST_NETWORK_FAIL='1', FAKE_JAVA_RESULT='99')
        assert '已取消' in run(command + ['uninstall'], input='\n', env=offline)
        ps_apk.write_bytes(b'replaced')
        refused = subprocess.run(command + ['uninstall'], input='y\n', env=offline, text=True, capture_output=True)
        assert refused.returncode != 0 and ps_apk.exists() and config.exists(), refused.stdout
        ps_apk.write_bytes(b'our apk')
        assert '清理完成' in run(command + ['uninstall'], input='y\n', env=offline)
        assert not ps_apk.exists() and (ps_output / 'keep.txt').is_file()
        assert not ps_cache.exists()
        assert not (appdata / '.ibudaoa-oneclick.lock').exists()
        # A legacy uninstall may have deleted .owner before failing on a long cache path.
        (ps_cache / 'gradle/deep').mkdir(parents=True)
        (ps_cache / 'gradle/deep/leftover.pom').write_text('leftover')
        invalid_mode = subprocess.run(command + ['build', '-Recover'], env=offline, text=True, capture_output=True)
        assert invalid_mode.returncode != 0 and ps_cache.exists()
        missing_marker = subprocess.run(command + ['uninstall'], env=offline, text=True, capture_output=True)
        assert missing_marker.returncode != 0 and '-Recover' in missing_marker.stdout and '失败阶段：清理' in missing_marker.stdout
        assert '已取消' in run(command + ['uninstall', '-Recover'], input='y\n', env=offline)
        assert ps_cache.exists()
        assert '清理完成' in run(command + ['uninstall', '-Recover'], input=str(ps_cache) + '\n', env=offline)
        assert not ps_cache.exists()
        external = root / 'outside-recovery'
        external.mkdir()
        (external / 'keep').write_text('keep')
        ps_cache.symlink_to(external, target_is_directory=True)
        linked = subprocess.run(command + ['uninstall', '-Recover'], input=str(ps_cache) + '\n', env=offline, text=True, capture_output=True)
        assert linked.returncode != 0 and (external / 'keep').read_text() == 'keep'
        ps_cache.unlink()
        print('PowerShell cleanup control flow (Linux fixture): PASS')
        print('PowerShell stale lock / active worker / kill + interrupt recovery / concurrency / UTF-8 / config preservation: PASS')

    certificate = root / 'localhost.p12'
    run([str(JDK / 'bin/keytool'), '-genkeypair', '-keystore', str(certificate), '-storetype', 'PKCS12',
         '-alias', 'test', '-storepass', 'changeit', '-keypass', 'changeit', '-keyalg', 'RSA',
         '-keysize', '2048', '-validity', '2', '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost', '-noprompt'])
    transport_command = [str(JDK / 'bin/java'), '-cp', str(classes), 'DownloadTest', str(root), str(certificate)]
    if PWSH:
        transport_command += [str(PWSH), str(PROJECT / 'tests/oneclick/Test-Transport.ps1')]
    print(run(transport_command).strip())
