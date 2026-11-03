"""Offline entrypoint integration tests; requires Python 3 and a JDK 17.

Run: python3 tests/oneclick/test_entrypoints.py /path/to/jdk17 [/path/to/pwsh]
All managed paths are redirected to temporary fixtures. No real user files are touched.
"""
import json
import os
from pathlib import Path
import pty
import select
import subprocess
import sys
import tempfile
import time

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
         str(PROJECT / 'tests/oneclick/OneClickTest.java')])
    output = run([str(JDK / 'bin/java'), '-cp', str(classes), 'OneClickTest'])
    assert 'PASS 21 checks' in output, output
    print('Shared engine: PASS 21 checks')

    # An actual terminal is necessary to prove readPassword hides the key.
    master, slave = pty.openpty()
    process = subprocess.Popen([str(JDK / 'bin/java'), '-cp', str(classes), 'OneClickTest', 'key'],
                               stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
    os.close(slave)
    transcript = b''
    sent_invalid = sent_key = False
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
            if not sent_invalid and '请输入高德'.encode() in transcript:
                os.write(master, b'invalid\n')
                sent_invalid = True
            if sent_invalid and not sent_key and '请重新输入'.encode() in transcript:
                # Wait for the next prompt so input cannot arrive before echo is disabled.
                if transcript.count('请输入高德'.encode()) >= 2:
                    os.write(master, b'a' * 32 + b'\n')
                    sent_key = True
    os.close(master)
    assert process.wait(timeout=5) == 0, transcript.decode(errors='replace')
    assert b'KEY_PASS' in transcript and b'a' * 32 not in transcript, transcript.decode(errors='replace')
    print('Hidden Key + invalid input retry (real PTY): PASS')

    # Exercise Bash control flow with network/Java stubbed, including post-JVM cleanup.
    fake = root / 'bin'
    fake.mkdir()
    fake_java = fake / 'java'
    fake_java.write_text('''#!/usr/bin/env bash
if [ "$1" = -version ]; then echo 'openjdk version "17.0.1"'; exit 0; fi
if [ "${FAKE_JAVA_RESULT:-0}" = 10 ]; then exit 10; fi
printf '%s\\n' "$@" > "$TEST_CALLS"
exit "${FAKE_JAVA_RESULT:-0}"
''')
    fake_java.chmod(0o755)
    (fake / 'javac').write_text('')
    (fake / 'javac').chmod(0o755)
    curl = fake / 'curl'
    curl.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args=sys.argv[1:]
destination=Path(args[args.index('-o')+1])
if os.environ.get('FAKE_NETWORK_FAIL') == '1': sys.exit(22)
if any('/commits?' in a for a in args):
 destination.write_text(json.dumps([{'sha':'a'*40}],indent=2))
else: destination.write_text('// fixture engine')
''')
    curl.chmod(0o755)
    shell = root / 'oneclick.sh'
    shell.write_text((PROJECT / 'scripts/oneclick.sh').read_text().replace('$HOME', '${TEST_USER_ROOT}'))
    user_root = root / '用户 space'
    user_root.mkdir()
    env = dict(os.environ, TEST_USER_ROOT=str(user_root), JAVA_HOME=str(root),
               TEST_CALLS=str(root / 'calls'), PATH=str(fake) + os.pathsep + os.environ['PATH'])
    # JAVA_HOME must contain bin/java and bin/javac, provided by the fixture above.
    assert '没有需要清理' in run(['bash', str(shell), 'uninstall'], env=env)
    run(['bash', str(shell), 'build'], env=env)
    assert 'a' * 40 in (root / 'calls').read_text()
    cache = user_root / '.cache/ibudaoa'
    assert (cache / 'OneClick.java').is_file()
    env['FAKE_JAVA_RESULT'] = '10'
    env['FAKE_NETWORK_FAIL'] = '1'
    cleanup_output = run(['bash', str(shell), 'uninstall'], env=env)
    assert '失败阶段' not in cleanup_output, cleanup_output
    assert not cache.exists(), 'post-JVM removal failed'
    assert not (user_root / '.cache/.ibudaoa-oneclick.lock').exists()
    env['FAKE_JAVA_RESULT'] = '0'
    failed = subprocess.run(['bash', str(shell), 'build'], env=env, capture_output=True)
    assert failed.returncode != 0
    assert not (user_root / '.cache/.ibudaoa-oneclick.lock').exists(), 'failed run leaked lock'
    print('Bash build / offline cleanup / repeat cleanup / network failure: PASS')

    if PWSH:
        psenv = dict(os.environ, XDG_CACHE_HOME=str(root / 'ps-cache'),
                     XDG_CONFIG_HOME=str(root / 'ps-config'), XDG_DATA_HOME=str(root / 'ps-data'))
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
        # Execute the PowerShell cleanup path on Linux with a fake Windows environment.
        # This validates script control flow, not Windows cmd.exe or ACL behavior.
        appdata = root / 'local app data'
        appdata.mkdir()
        psenv.update(OS='Windows_NT', PROCESSOR_ARCHITECTURE='AMD64', LOCALAPPDATA=str(appdata))
        ps_script = PROJECT / 'scripts/oneclick.ps1'
        first = run([str(PWSH), '-NoProfile', '-File', str(ps_script), 'uninstall'], env=psenv)
        assert '没有需要清理' in first
        ps_cache = appdata / 'ibudaoa'
        (ps_cache / 'tools/jdk/bin').mkdir(parents=True)
        (ps_cache / '.owner').write_text(OWNER)
        (ps_cache / 'OneClick.java').write_text('// fixture')
        ps_java = ps_cache / 'tools/jdk/bin/java.exe'
        ps_java.write_text(fake_java.read_text())
        ps_java.chmod(0o755)
        (ps_java.parent / 'javac.exe').write_text('')
        psenv['FAKE_JAVA_RESULT'] = '10'
        assert '清理完成' in run([str(PWSH), '-NoProfile', '-File', str(ps_script), 'uninstall'], env=psenv)
        assert not ps_cache.exists()
        assert not (appdata / '.ibudaoa-oneclick.lock').exists()
        print('PowerShell cleanup control flow (Linux fixture): PASS')
