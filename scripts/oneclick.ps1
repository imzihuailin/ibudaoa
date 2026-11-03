param([ValidateSet('build', 'uninstall')][string]$Mode = 'build')
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$owner = 'ibudaoa-oneclick-v1'
$cache = Join-Path $env:LOCALAPPDATA 'ibudaoa'
$lockPath = Join-Path $env:LOCALAPPDATA '.ibudaoa-oneclick.lock'
$scratch = $null
$locked = $false
$stage = '准备入口'
$exitCode = 0

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
function Get-Download([string]$Url, [string]$Destination) {
    if (!$Url.StartsWith('https://')) { throw '下载地址必须使用 HTTPS。' }
    $lastFailure = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination -TimeoutSec 900 -Headers @{'User-Agent'='ibudaoa-oneclick'}
            return
        } catch { $lastFailure = $_ }
    }
    throw $lastFailure
}
function Test-Jdk([string]$Java) {
    if (!$Java -or !(Test-Path -LiteralPath $Java)) { return $false }
    $javac = Join-Path ([IO.Path]::GetDirectoryName($Java)) 'javac.exe'
    if (!(Test-Path -LiteralPath $javac)) { return $false }
    # Windows PowerShell 5.1 treats native stderr specially. Merge it for java -version.
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $version = (& $Java -version 2>&1 | Out-String) }
    finally { $ErrorActionPreference = $oldPreference }
    return ($version -match 'version "17\.')
}

try {
    if ($env:OS -ne 'Windows_NT' -or [Environment]::Is64BitOperatingSystem -eq $false -or
        $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw '此入口支持 Windows x64，请在 64 位 PowerShell 中运行。' }
    Assert-NoLink $lockPath
    # New-Item without -Force fails atomically if another operation already owns the lock.
    New-Item -ItemType Directory -Path $lockPath -ErrorAction Stop | Out-Null
    $locked = $true
    $scratch = Join-Path ([IO.Path]::GetTempPath()) ('ibudaoa-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $scratch | Out-Null
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
    Write-Host 'ibudaoa 一键工具'
    $stage = '查询最新版本'
    $engine = Join-Path $cache 'OneClick.java'
    $revision = '0000000000000000000000000000000000000000'
    if ($Mode -eq 'build' -or !(Test-Path -LiteralPath $engine)) {
        $commit = Invoke-RestMethod -Uri 'https://api.github.com/repos/imzihuailin/ibudaoa/commits?per_page=1' -Headers @{'User-Agent'='ibudaoa-oneclick'}
        $revision = @($commit)[0].sha
        if ($revision -notmatch '^[a-f0-9]{40}$') { throw '无法确定最新源码版本。' }
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
            $assets = @(Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse')
            if ($assets.Count -ne 1) { throw 'Java 下载元数据异常。' }
            $package = $assets[0].binary.package
            if ($package.checksum -notmatch '^[a-f0-9]{64}$' -or $package.link -notlike 'https://github.com/adoptium/*') { throw 'Java 下载元数据异常。' }
            $archive = Join-Path $scratch 'jdk.zip'
            Get-Download $package.link $archive
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash -ne $package.checksum) { throw 'Java 下载校验失败。' }
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
        Copy-Item -LiteralPath $localEngine -Destination $engine -Force
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
    [Console]::Error.WriteLine("失败阶段：$stage。$($_.Exception.Message)")
    [Console]::Error.WriteLine("请处理错误后重新执行同一命令；若上次被强制终止，请确认没有任务运行，再删除锁目录：$lockPath")
    $exitCode = 1
} finally {
    if ($scratch -and (Test-Path -LiteralPath $scratch)) { Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction Continue }
    if ($locked) { Remove-Item -LiteralPath $lockPath -Force -ErrorAction Continue }
}
exit $exitCode
