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
function Get-LatestRevision {
    $metadata = Join-Path $scratch 'commit.json'
    Get-Download 'https://api.github.com/repos/imzihuailin/ibudaoa/commits?per_page=1' $metadata
    $commit = @([IO.File]::ReadAllText($metadata) | ConvertFrom-Json)
    $value = $commit[0].sha
    if ($value -notmatch '^[a-f0-9]{40}$') { throw '无法确定最新源码版本。' }
    return $value
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
    # Keep partial downloads/extraction inside the owned cache and overwrite them on retry.
    $scratch = Join-Path $cache 'bootstrap'
    Assert-NoLink $scratch
    if (Test-Path -LiteralPath $scratch) { Remove-OwnedTree $scratch }
    New-Item -ItemType Directory -Path $scratch | Out-Null
    Write-Host 'ibudaoa 一键工具'
    $stage = '查询最新版本'
    $engine = Join-Path $cache 'OneClick.java'
    $revision = '0000000000000000000000000000000000000000'
    if ($Mode -eq 'build' -or !(Test-Path -LiteralPath $engine)) {
        $revision = Get-LatestRevision
    }
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
        $java = Join-Path $jdk 'bin\java.exe'
        if (!(Test-Jdk $java)) {
            Write-Host '正在下载 Java 17……'
            if ($revision -eq '0000000000000000000000000000000000000000') { $revision = Get-LatestRevision }
            $manifest = Join-Path $scratch 'download-manifest.properties'
            Get-Download "https://raw.githubusercontent.com/imzihuailin/ibudaoa/$revision/scripts/download-manifest.properties" $manifest
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
    $stage = '下载构建与清理程序'
    $localEngine = Join-Path $scratch 'OneClick.java'
    if ($Mode -eq 'uninstall' -and (Test-Path -LiteralPath $engine)) {
        Copy-Item -LiteralPath $engine -Destination $localEngine
    } else {
        Get-Download "https://raw.githubusercontent.com/imzihuailin/ibudaoa/$revision/scripts/OneClick.java" $localEngine
        $stagedEngine = Join-Path $cache 'OneClick.java.new'
        Assert-NoLink $stagedEngine
        Assert-NoLink $engine
        Copy-Item -LiteralPath $localEngine -Destination $stagedEngine -Force
        if (Test-Path -LiteralPath $engine) { [IO.File]::Replace($stagedEngine, $engine, [NullString]::Value) }
        else { [IO.File]::Move($stagedEngine, $engine) }
    }
    $stage = $Mode
    & $java '-Dfile.encoding=UTF-8' $localEngine $Mode $revision $cache
    $result = $LASTEXITCODE
    if ($result -eq 10) {
        # Wait until Java exits before deleting its cached executable and DLLs.
        Assert-Owned
        foreach ($child in Get-ChildItem -Force -LiteralPath $cache) {
            if ($child.Name -ne '.owner') { Remove-OwnedTree $child.FullName }
        }
        Remove-Item -LiteralPath (Join-Path $cache '.owner') -Force
        [IO.Directory]::Delete($cache)
        Write-Host '清理完成。'
    } elseif ($result -ne 0) { throw "操作未完成，返回码：$result" }
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
