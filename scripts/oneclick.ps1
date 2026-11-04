param([ValidateSet('build', 'uninstall')][string]$Mode = 'build')
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$owner = 'ibudaoa-oneclick-v1'
$cache = Join-Path $env:LOCALAPPDATA 'ibudaoa'
$lockPath = Join-Path $env:LOCALAPPDATA '.ibudaoa-oneclick.lock'
$mutex = $null
$scratch = $null
$locked = $false
$stage = '准备入口'
$exitCode = 0
$previousConsoleOutput = [Console]::OutputEncoding
$previousConsoleInput = [Console]::InputEncoding
$previousOutputEncoding = $OutputEncoding

function Assert-NoLink([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -Force -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "路径不能经过符号链接或连接点：$cursor"
            }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
}
function Assert-Owned {
    Assert-NoLink $cache
    $marker = Join-Path $cache '.owner'
    Assert-NoLink $marker
    if (!(Test-Path -LiteralPath $marker) -or [IO.File]::ReadAllText($marker) -ne $owner) {
        throw "目录不是本工具创建的，停止以保护原文件：$cache"
    }
}
function Remove-OwnedTree([string]$Path) {
    # Do not recurse through junctions, including on Windows PowerShell 5.1.
    $item = Get-Item -Force -LiteralPath $Path
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        if ($item.PSIsContainer) { [IO.Directory]::Delete($item.FullName) }
        else { [IO.File]::Delete($item.FullName) }
        return
    }
    if ($item.PSIsContainer) {
        foreach ($child in Get-ChildItem -Force -LiteralPath $Path) { Remove-OwnedTree $child.FullName }
        [IO.Directory]::Delete($item.FullName)
    } else { Remove-Item -LiteralPath $Path -Force }
}
function Get-Download([string[]]$Urls, [string]$Destination, [string]$Sha256 = '', [int]$ConnectTimeoutMs = 15000, [int]$ReadTimeoutMs = 30000) {
    if ($Sha256 -and $Sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw '下载清单校验值无效。' }
    $partial = $Destination + '.part'
    $lastFailure = $null
    Write-Host "下载文件：$([IO.Path]::GetFileName($Destination))"
    foreach ($url in $Urls) {
        for ($attempt = 1; $attempt -le 2; $attempt++) {
            $response = $null; $stream = $null; $file = $null
            try {
                $uri = [Uri]$url
                if ($uri.Scheme -ne 'https') { throw '下载地址必须使用 HTTPS。' }
                Write-Host "下载来源：$($uri.Host)（尝试 $attempt/2）"
                try {
                    for ($redirect = 0; $redirect -le 5; $redirect++) {
                        if ($uri.Scheme -ne 'https') { throw '拒绝跳转至非 HTTPS 地址。' }
                        $request = [Net.HttpWebRequest]::Create($uri)
                        $request.Timeout = $ConnectTimeoutMs
                        $request.ReadWriteTimeout = $ReadTimeoutMs
                        $request.AllowAutoRedirect = $false
                        $request.UserAgent = 'ibudaoa-oneclick'
                        $response = $request.GetResponse()
                        $status = [int]$response.StatusCode
                        if ($status -ge 300 -and $status -lt 400) {
                            $next = New-Object Uri($uri, $response.Headers['Location'])
                            $response.Close(); $response = $null
                            $uri = $next
                            continue
                        }
                        if ($status -ne 200) { throw "下载失败，HTTP $status" }
                        break
                    }
                    if (!$response) { throw '下载跳转次数过多。' }
                    $total = $response.ContentLength
                    $stream = $response.GetResponseStream()
                    if ($stream.CanTimeout) { $stream.ReadTimeout = $ReadTimeoutMs }
                    $file = [IO.File]::Open($partial, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
                    $buffer = New-Object byte[] 65536
                    $clock = [Diagnostics.Stopwatch]::StartNew()
                    [long]$count = 0; [long]$lastTime = 0; [long]$lastBytes = 0
                    while (($n = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                        $file.Write($buffer, 0, $n); $count += $n
                        $now = $clock.ElapsedMilliseconds
                        if ($now - $lastTime -ge 1000) {
                            $speed = ($count - $lastBytes) * 1000.0 / ($now - $lastTime) / 1024
                            $percent = if ($total -gt 0) { '{0:F0}%' -f ($count * 100.0 / $total) } else { '大小未知' }
                            Write-Host -NoNewline ("`r下载中…… {0:F1} MB  {1}  {2:F0} KB/s    " -f ($count / 1MB), $percent, $speed)
                            $lastTime = $now; $lastBytes = $count
                        }
                    }
                } finally {
                    if ($file) { $file.Dispose() }
                    if ($stream) { $stream.Dispose() }
                    if ($response) { $response.Close() }
                }
                if ($total -ge 0 -and $count -ne $total) { throw '下载文件不完整。' }
                if ($Sha256 -and (Get-FileHash -Algorithm SHA256 -LiteralPath $partial).Hash -ne $Sha256) { throw '文件校验失败。' }
                if (Test-Path -LiteralPath $Destination) { [IO.File]::Replace($partial, $Destination, [NullString]::Value) }
                else { [IO.File]::Move($partial, $Destination) }
                Write-Host "`r✓ 下载完成                                          "
                return
            } catch {
                $lastFailure = $_.Exception.Message
                if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
                Write-Host "`n下载失败或长时间无进展：$lastFailure。正在重试或切换来源。"
            }
        }
    }
    throw "所有下载来源均失败：$lastFailure"
}
function Get-SourceSnapshot {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $original = 'https://github.com/imzihuailin/ibudaoa/archive/refs/heads/main.zip'
    $archive = Join-Path $scratch 'source.zip'
    foreach ($prefix in @('https://ghfast.top/', 'https://gh-proxy.org/', '')) {
        $zip = $null
        try {
            Get-Download ($prefix + $original) $archive
            $zip = [IO.Compression.ZipFile]::OpenRead($archive)
            if ($zip.Entries.Count -eq 0) { throw '源码包为空。' }
            foreach ($entry in $zip.Entries) {
                $name = $entry.FullName
                $type = ($entry.ExternalAttributes -shr 16) -band 0xF000
                if ($name -notmatch '^ibudaoa-main(/|$)' -or $name -match '(^|/)\.\.(/|$)|[\\:\x00-\x1f]' -or
                    $type -notin @(0, 0x4000, 0x8000)) { throw '源码包含不安全路径或链接。' }
            }
            $unpack = Join-Path $scratch 'source'
            if (Test-Path -LiteralPath $unpack) { Remove-OwnedTree $unpack }
            New-Item -ItemType Directory -Path $unpack | Out-Null
            foreach ($entry in $zip.Entries) {
                $destination = Join-Path $unpack $entry.FullName
                if ($entry.FullName.EndsWith('/')) {
                    New-Item -ItemType Directory -Force -Path $destination | Out-Null
                } else {
                    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($destination)) | Out-Null
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $false)
                }
            }
            $root = Join-Path $unpack 'ibudaoa-main'
            foreach ($required in @('gradlew', 'app/build.gradle.kts', 'scripts/OneClick.java', 'scripts/download-manifest.properties')) {
                if (!(Test-Path -LiteralPath (Join-Path $root $required) -PathType Leaf)) { throw '源码包内容不完整。' }
            }
            $revision = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
            $work = Join-Path $cache 'work'
            Assert-NoLink $work
            if (Test-Path -LiteralPath $work) { Remove-OwnedTree $work }
            Move-Item -LiteralPath $unpack -Destination $work
            [IO.File]::WriteAllText((Join-Path $work 'source.sha256'), $revision)
            return $revision
        } catch {
            Write-Host "源码来源不可用：$($_.Exception.Message)。正在切换来源。"
        } finally { if ($zip) { $zip.Dispose() } }
    }
    throw '所有源码下载来源均失败。'
}
function Invoke-OfflineUninstall {
    $config = Join-Path $cache 'config'
    Assert-NoLink $config
    if (Test-Path -LiteralPath $config) {
        $marker = Join-Path $config '.owner'
        Assert-NoLink $marker
        if (!(Test-Path -LiteralPath $marker -PathType Leaf) -or [IO.File]::ReadAllText($marker) -ne $owner) {
            throw "不是本工具创建的配置目录：$config"
        }
    }
    Write-Host "将删除本工具保存的源码、工具、缓存、Key、签名及输出 APK。`n缓存目录：$cache`n配置目录：$config"
    foreach ($record in @('output.path', 'output.sha256', 'output.created')) { Assert-NoLink (Join-Path $config $record) }
    $output = $null
    $record = Join-Path $config 'output.path'
    if (Test-Path -LiteralPath $record -PathType Leaf) {
        $output = [IO.File]::ReadAllText($record)
        if (![IO.Path]::IsPathRooted($output)) { throw '输出路径无效。' }
        Assert-NoLink $output
        Write-Host "输出 APK：$(Join-Path $output 'ibudaoa.apk')"
    }
    Write-Host "删除签名后，将无法用新签名覆盖升级原来安装的应用。`n如需保留升级能力，请先备份整个配置目录：$config"
    $answer = Read-Host '确认全部删除？[y/N]'
    if (!$answer -or $answer.Trim() -ine 'y') { Write-Host '已取消，未删除文件。'; return }
    if ($output) {
        $apk = Join-Path $output 'ibudaoa.apk'
        Assert-NoLink $apk
        if (Test-Path -LiteralPath $apk) {
            $fingerprint = Join-Path $config 'output.sha256'
            if (!(Test-Path -LiteralPath $fingerprint -PathType Leaf) -or
                (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash -ine [IO.File]::ReadAllText($fingerprint)) {
                throw "输出 APK 已被替换，保留该文件并停止清理：$apk"
            }
            Remove-Item -LiteralPath $apk
        }
        $created = Join-Path $config 'output.created'
        if ((Test-Path -LiteralPath $created -PathType Leaf) -and [IO.File]::ReadAllText($created) -eq $output -and
            (Test-Path -LiteralPath $output -PathType Container) -and @(Get-ChildItem -Force -LiteralPath $output).Count -eq 0) {
            [IO.Directory]::Delete($output)
        }
    }
    Assert-Owned
    Remove-OwnedTree $cache
    Write-Host '清理完成。'
}
function Test-Jdk([string]$Java) {
    if (!$Java -or !(Test-Path -LiteralPath $Java)) { return $false }
    $javac = Join-Path ([IO.Path]::GetDirectoryName($Java)) 'javac.exe'
    if (!(Test-Path -LiteralPath $javac)) { return $false }
    # Windows PowerShell 5.1 treats native stderr specially. Merge it for java -version.
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $version = (& $Java -version 2>&1 | Out-String)
        $javaResult = $LASTEXITCODE
        $compilerVersion = (& $javac -version 2>&1 | Out-String)
        $compilerResult = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $oldPreference }
    return ($javaResult -eq 0 -and $compilerResult -eq 0 -and $version -match 'version "17\.' -and $compilerVersion -match 'javac 17\.')
}
function Assert-NoOtherWorker {
    # Also detect a JVM still finishing after its PowerShell parent was forcibly closed.
    $processes = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
    $ancestors = @{}
    $cursor = $PID
    while ($cursor -and !$ancestors.ContainsKey($cursor)) {
        $ancestors[$cursor] = $true
        $parent = $processes | Where-Object { $_.ProcessId -eq $cursor } | Select-Object -First 1
        if (!$parent) { break }
        $cursor = [int]$parent.ParentProcessId
    }
    $cachePattern = [regex]::Escape($cache)
    foreach ($process in $processes) {
        if ($ancestors.ContainsKey([int]$process.ProcessId)) { continue }
        $command = [string]$process.CommandLine
        if ($process.Name -match '^(powershell|pwsh|java|javaw|cmd)\.exe$' -and
            ($command -match $cachePattern -or $command -match '(?i)(?:ibudaoa-[a-f0-9-]+|oneclick)\.ps1(?:["\s]|$)')) {
            throw '另一个构建任务仍在运行，请等待它结束或关闭原构建窗口后重试。'
        }
    }
}
function Remove-LegacyLock {
    if (!(Test-Path -LiteralPath $lockPath)) { return }
    Assert-NoLink $lockPath
    # The historical lock was empty. Never recursively remove unexpected user content.
    [IO.Directory]::Delete($lockPath)
    Write-Host '✓ 已恢复上次中断的构建，可以直接重试。'
}

try {
    # Console.Error used an ASCII encoder on some Windows hosts. Set both native and PS encodings.
    $utf8 = New-Object Text.UTF8Encoding($false)
    [Console]::OutputEncoding = $utf8
    [Console]::InputEncoding = $utf8
    $OutputEncoding = $utf8
    if ($env:OS -ne 'Windows_NT' -or [Environment]::Is64BitOperatingSystem -eq $false -or
        $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw '此入口支持 Windows x64，请在 64 位 PowerShell 中运行。' }
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $pathBytes = [Text.Encoding]::UTF8.GetBytes([IO.Path]::GetFullPath($cache).ToUpperInvariant())
        $identity = ([BitConverter]::ToString($hasher.ComputeHash($pathBytes))).Replace('-', '')
    } finally { $hasher.Dispose() }
    # Kernel mutexes disappear when their owner exits, even when finally never runs.
    $mutex = New-Object Threading.Mutex($false, "Global\ibudaoa-oneclick-$identity")
    try { $locked = $mutex.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $locked = $true }
    if (!$locked) { throw '另一个构建或清理正在运行，请等待结束后重试。' }
    Assert-NoOtherWorker
    Remove-LegacyLock
    if ($Mode -eq 'uninstall' -and !(Test-Path -LiteralPath $cache)) {
        Write-Host '没有需要清理的构建文件。'
        return
    }
    Assert-NoLink $cache
    if (Test-Path -LiteralPath $cache) { Assert-Owned }
    else {
        New-Item -ItemType Directory -Path $cache | Out-Null
        [IO.File]::WriteAllText((Join-Path $cache '.owner'), $owner)
    }
    if ($Mode -eq 'uninstall') { Invoke-OfflineUninstall; return }
    # Persist the complete local entry before downloads, so uninstall is always offline.
    $localUninstall = Join-Path $cache 'uninstall.ps1'
    Assert-NoLink $localUninstall
    if ([IO.Path]::GetFullPath($PSCommandPath) -ne [IO.Path]::GetFullPath($localUninstall)) {
        Copy-Item -LiteralPath $PSCommandPath -Destination $localUninstall -Force
    }
    $scratch = Join-Path $cache 'bootstrap'
    Assert-NoLink $scratch
    if (Test-Path -LiteralPath $scratch) { Remove-OwnedTree $scratch }
    New-Item -ItemType Directory -Path $scratch | Out-Null
    Write-Host 'ibudaoa 一键工具'
    $stage = '下载最新源码'
    Write-Host '[1/5] 下载最新源码'
    $revision = Get-SourceSnapshot
    $source = Join-Path $cache 'work/ibudaoa-main'
    $stage = '准备 Java 17'
    $java = $null
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Jdk $candidate) { $java = $candidate }
    }
    if (!$java) {
        $candidate = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($candidate -and (Test-Jdk $candidate.Source)) { $java = $candidate.Source }
    }
    if (!$java) {
        $jdk = Join-Path $cache 'tools\jdk'
        Assert-NoLink $jdk
        $java = Join-Path $jdk 'bin\java.exe'
        if (!(Test-Jdk $java)) {
            Write-Host '正在下载 Java 17……'
            $manifest = Join-Path $source 'scripts/download-manifest.properties'
            $downloads = ConvertFrom-StringData ([IO.File]::ReadAllText($manifest))
            $checksum = $downloads['jdk.windows.x64.sha256']
            $sources = @($downloads['jdk.windows.x64.mirror'], $downloads['jdk.windows.x64.official'])
            if ($checksum -notmatch '^[a-f0-9]{64}$' -or !$sources[0] -or !$sources[1]) { throw 'Java 下载清单异常。' }
            $archive = Join-Path $scratch 'jdk.zip'
            Get-Download $sources $archive $checksum
            $unpack = Join-Path $scratch 'jdk'
            Expand-Archive -LiteralPath $archive -DestinationPath $unpack
            $roots = @(Get-ChildItem -Directory -LiteralPath $unpack)
            if ($roots.Count -ne 1 -or !(Test-Jdk (Join-Path $roots[0].FullName 'bin\java.exe'))) { throw 'Java 压缩包内容异常。' }
            New-Item -ItemType Directory -Force -Path (Split-Path $jdk) | Out-Null
            if (Test-Path -LiteralPath $jdk) { Remove-Item -LiteralPath $jdk -Recurse -Force }
            Move-Item -LiteralPath $roots[0].FullName -Destination $jdk
        }
    }
    $stage = '构建'
    $engine = Join-Path $cache 'OneClick.java'
    Assert-NoLink $engine
    Copy-Item -LiteralPath (Join-Path $source 'scripts/OneClick.java') -Destination $engine -Force
    & $java '-Dfile.encoding=UTF-8' $engine build $revision $cache
    if ($LASTEXITCODE -ne 0) { throw "操作未完成，返回码：$LASTEXITCODE" }

} catch {
    Write-Host "失败阶段：$stage。$($_.Exception.Message)" -ForegroundColor Red
    Write-Host '请处理上述问题后重新执行同一命令；中断后无需手动删除锁文件。' -ForegroundColor Red
    $exitCode = 1
} finally {
    try {
        if ($scratch -and (Test-Path -LiteralPath $scratch)) { Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction Continue }
    } finally {
        if ($locked) { $mutex.ReleaseMutex() }
        if ($mutex) { $mutex.Dispose() }
        [Console]::OutputEncoding = $previousConsoleOutput
        [Console]::InputEncoding = $previousConsoleInput
        $OutputEncoding = $previousOutputEncoding
    }
}
exit $exitCode
