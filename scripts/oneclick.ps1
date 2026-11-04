param([ValidateSet('build', 'uninstall')][string]$Mode = 'build', [switch]$Recover)
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

function Initialize-Cleanup {
    if (!('IbudaoaCleanup' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;

public static class IbudaoaCleanup {
    static readonly bool Windows = Environment.OSVersion.Platform == PlatformID.Win32NT;
    const uint Missing = 0xffffffff, Directory = 16, Reparse = 1024, ReadOnly = 1;
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct FindData {
        public uint Attributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME Creation, Access, Write;
        public uint SizeHigh, SizeLow, Reserved0, Reserved1;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string Name;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 14)] public string Alternate;
    }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern uint GetFullPathNameW(string path, uint size, StringBuilder buffer, IntPtr last);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern uint GetFileAttributesW(string path);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool SetFileAttributesW(string path, uint attributes);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr FindFirstFileW(string path, out FindData data);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool FindNextFileW(IntPtr handle, out FindData data);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool FindClose(IntPtr handle);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool DeleteFileW(string path);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool RemoveDirectoryW(string path);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    static bool IsMissing(int error) { return error == 2 || error == 3; }
    static IOException Error(string action, string path, int error) {
        return new IOException(action + "：" + path + "（Windows 错误 " + error + "：" + new Win32Exception(error).Message + "）", new Win32Exception(error));
    }
    public static string FullPath(string path) {
        if (String.IsNullOrWhiteSpace(path)) throw new IOException("路径为空");
        if (!Windows) return Path.GetFullPath(path);
        if (path.StartsWith(@"\\?\") || path.StartsWith(@"\\.\")) throw new IOException("不接受设备命名空间路径：" + path);
        StringBuilder result = new StringBuilder(32768);
        uint length = GetFullPathNameW(path, (uint)result.Capacity, result, IntPtr.Zero);
        if (length == 0) throw Error("无法解析路径", path, Marshal.GetLastWin32Error());
        if (length >= result.Capacity) throw new IOException("路径过长：" + path);
        return result.ToString();
    }
    static string Extended(string full) {
        if (!Windows) return full;
        return full.StartsWith(@"\\") ? @"\\?\UNC\" + full.Substring(2) : @"\\?\" + full;
    }
    static uint? Attributes(string full) {
        if (Windows) {
            uint attributes = GetFileAttributesW(Extended(full));
            if (attributes != Missing) return attributes;
            int error = Marshal.GetLastWin32Error();
            if (IsMissing(error)) return null;
            throw Error("无法读取属性", full, error);
        }
        try { return (uint)File.GetAttributes(full); }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }
    }
    public static bool Exists(string path) { return Attributes(FullPath(path)).HasValue; }
    public static void AssertNoLinks(string path) {
        string current = FullPath(path);
        while (!String.IsNullOrEmpty(current)) {
            uint? attributes = Attributes(current);
            if (attributes.HasValue && (attributes.Value & Reparse) != 0)
                throw new IOException("路径不能经过符号链接或连接点：" + current);
            if (!Windows) { current = Path.GetDirectoryName(current); continue; }
            current = current.TrimEnd('\\');
            int cut = current.LastIndexOf('\\');
            if (cut < 2) break;
            // Do not query the server-only part of a UNC root.
            if (current.StartsWith(@"\\") && current.IndexOf('\\', 2) == cut) break;
            current = cut == 2 ? current.Substring(0, 3) : current.Substring(0, cut);
            if (current.Length == 3 && current[1] == ':') {
                attributes = Attributes(current);
                if (attributes.HasValue && (attributes.Value & Reparse) != 0) throw new IOException("路径不能经过连接点：" + current);
                break;
            }
        }
    }
    public static string[] Children(string path) {
        string full = FullPath(path);
        uint? attributes = Attributes(full);
        if (!attributes.HasValue) return new string[0];
        if ((attributes.Value & Reparse) != 0 || (attributes.Value & Directory) == 0)
            throw new IOException("不能遍历链接或普通文件：" + full);
        if (!Windows) {
            try { return System.IO.Directory.GetFileSystemEntries(full); }
            catch (DirectoryNotFoundException) { return new string[0]; }
        }
        FindData data;
        IntPtr handle = FindFirstFileW(Extended(full).TrimEnd('\\') + @"\*", out data);
        if (handle == new IntPtr(-1)) {
            int error = Marshal.GetLastWin32Error();
            if (IsMissing(error)) return new string[0];
            throw Error("无法列出目录", full, error);
        }
        List<string> children = new List<string>();
        try {
            do {
                if (data.Name != "." && data.Name != "..") children.Add(full.TrimEnd('\\') + "\\" + data.Name);
            } while (FindNextFileW(handle, out data));
            int error = Marshal.GetLastWin32Error();
            if (error != 18) throw Error("读取目录失败", full, error);
        } finally { FindClose(handle); }
        return children.ToArray();
    }
    static void RemoveOne(string full, bool directory) {
        if (Windows) {
            bool removed = directory ? RemoveDirectoryW(Extended(full)) : DeleteFileW(Extended(full));
            if (!removed) {
                int error = Marshal.GetLastWin32Error();
                if (!IsMissing(error)) throw Error("删除失败", full, error);
            }
        } else {
            try { if (directory) System.IO.Directory.Delete(full); else File.Delete(full); }
            catch (FileNotFoundException) { }
            catch (DirectoryNotFoundException) { }
        }
    }
    public static void RemoveEmptyDirectory(string path) { RemoveOne(FullPath(path), true); }
    public static void RemoveDirectoryIfEmpty(string path) {
        string full = FullPath(path);
        if (Children(full).Length != 0) return;
        if (Windows) {
            if (!RemoveDirectoryW(Extended(full))) {
                int error = Marshal.GetLastWin32Error();
                if (!IsMissing(error) && error != 145 && error != 183) throw Error("删除空目录失败", full, error);
            }
        } else {
            try { RemoveOne(full, true); }
            catch (IOException) { if (Children(full).Length == 0) throw; }
        }
    }
    public static void Delete(string path) {
        string full = FullPath(path);
        uint? attributes = Attributes(full);
        if (!attributes.HasValue) return;
        bool directory = (attributes.Value & Directory) != 0;
        if ((attributes.Value & Reparse) != 0) { RemoveOne(full, directory); return; }
        if (directory) {
            foreach (string child in Children(full)) Delete(child);
        } else if ((attributes.Value & ReadOnly) != 0) {
            uint next = attributes.Value & ~ReadOnly;
            if (next == 0) next = 128;
            if (Windows) {
                if (!SetFileAttributesW(Extended(full), next)) {
                    int error = Marshal.GetLastWin32Error();
                    if (IsMissing(error)) return;
                    throw Error("无法清除只读属性", full, error);
                }
            } else {
                try { File.SetAttributes(full, (FileAttributes)next); }
                catch (FileNotFoundException) { return; }
                catch (DirectoryNotFoundException) { return; }
            }
        }
        RemoveOne(full, directory);
    }
    public static string Hash(string path) {
        string full = FullPath(path);
        AssertNoLinks(full);
        if (Windows) {
            // Opening the reparse point itself prevents following a replaced final link.
            using (SafeFileHandle handle = CreateFileW(Extended(full), 0x80000000, 1, IntPtr.Zero, 3, 0x00200000, IntPtr.Zero)) {
                if (handle.IsInvalid) throw Error("无法读取文件", full, Marshal.GetLastWin32Error());
                using (FileStream stream = new FileStream(handle, FileAccess.Read))
                using (SHA256 sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
            }
        }
        using (FileStream stream = File.OpenRead(full))
        using (SHA256 sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
    }
}

'@
    }
}
function Assert-NoLink([string]$Path) {
    Initialize-Cleanup
    [IbudaoaCleanup]::AssertNoLinks($Path)
}
function Assert-Owned {
    Assert-NoLink $cache
    $marker = Join-Path $cache '.owner'
    Assert-NoLink $marker
    if (!(Test-Path -LiteralPath $marker) -or [IO.File]::ReadAllText($marker) -ne $owner) {
        throw "管理目录标记缺失或不匹配，已停止：$cache。若是旧版卸载留下的残余，请使用 uninstall -Recover 并核对完整目录。"
    }
}
function Remove-OwnedTree([string]$Path) {
    Initialize-Cleanup
    [IbudaoaCleanup]::Delete($Path)
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
    Initialize-Cleanup
    $root = [IbudaoaCleanup]::FullPath($cache)
    $expectedRoot = [IbudaoaCleanup]::FullPath((Join-Path $env:LOCALAPPDATA 'ibudaoa'))
    if (!$root.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw '残留清理仅允许处理本账号固定的 ibudaoa 管理目录。' }
    Assert-NoLink $root
    $config = Join-Path $cache 'config'
    Assert-NoLink $config
    $configMarker = Join-Path $config '.owner'
    Assert-NoLink $configMarker
    $configTrusted = (Test-Path -LiteralPath $configMarker -PathType Leaf) -and [IO.File]::ReadAllText($configMarker) -eq $owner
    if ([IbudaoaCleanup]::Exists($config) -and !$configTrusted -and !$Recover) {
        throw "配置目录标记缺失或不匹配，已停止：$config。若是旧版卸载残余，请使用 uninstall -Recover。"
    }
    Write-Host "将删除本工具保存的源码、工具、缓存、Key、签名及输出 APK。`n缓存目录：$root`n配置目录：$config"
    $output = $null
    $apk = $null
    $expectedHash = $null
    $removeOutputFolder = $false
    foreach ($record in @('output.path', 'output.sha256', 'output.created')) { Assert-NoLink (Join-Path $config $record) }
    $record = Join-Path $config 'output.path'
    $fingerprint = Join-Path $config 'output.sha256'
    if (Test-Path -LiteralPath $record -PathType Leaf) {
        $recordedOutput = [IO.File]::ReadAllText($record)
        if ($Recover -and (!$configTrusted -or !(Test-Path -LiteralPath $fingerprint -PathType Leaf))) {
            Write-Host "输出记录不完整或缺少配置标记，管理目录外的 APK 不会删除。记录位置：$recordedOutput"
        } else {
            if (![IO.Path]::IsPathRooted($recordedOutput)) { throw '输出路径无效。' }
            $output = [IbudaoaCleanup]::FullPath($recordedOutput)
            Assert-NoLink $output
            $apk = Join-Path $output 'ibudaoa.apk'
            Assert-NoLink $apk
            if ([IbudaoaCleanup]::Exists($apk)) {
                if (Test-Path -LiteralPath $fingerprint -PathType Leaf) { $expectedHash = [IO.File]::ReadAllText($fingerprint) }
                if (!$expectedHash -or $expectedHash -notmatch '^[a-fA-F0-9]{64}$') {
                    if ($Recover) {
                        Write-Host "输出指纹缺失或无效，管理目录外的 APK 不会删除：$apk"
                        $apk = $null; $output = $null
                    } else { throw "输出 APK 缺少有效指纹，保留全部文件并停止清理：$apk" }
                } elseif ([IbudaoaCleanup]::Hash($apk) -ine $expectedHash) {
                    throw "输出 APK 已被替换，保留全部文件并停止清理：$apk"
                }
            }
            if ($output) {
                Write-Host "输出 APK：$(Join-Path $output 'ibudaoa.apk')"
                $created = Join-Path $config 'output.created'
                $removeOutputFolder = (Test-Path -LiteralPath $created -PathType Leaf) -and [IO.File]::ReadAllText($created) -eq $output
            }
        }
    } elseif ($Recover) { Write-Host '没有完整的输出路径记录，管理目录外的 APK 不会删除。' }
    Write-Host "删除签名后，将无法用新签名覆盖升级原来安装的应用。`n如需保留升级能力，请先备份整个配置目录：$config"
    if ($Recover) {
        Write-Host '残留清理无法恢复此前已删除的签名或配置。请核对上面的完整管理目录。'
        $answer = Read-Host '确认清理请输入上面的完整缓存目录；直接回车取消'
        if (!$answer -or !$answer.Trim().Equals($root, [StringComparison]::OrdinalIgnoreCase)) { Write-Host '已取消，未删除文件。'; return }
    } else {
        $answer = Read-Host '确认全部删除？[y/N]'
        if (!$answer -or $answer.Trim() -ine 'y') { Write-Host '已取消，未删除文件。'; return }
    }
    # Keep identity, configuration, and the local retry entry until disposable files are gone.
    foreach ($child in [IbudaoaCleanup]::Children($cache)) {
        $name = [IO.Path]::GetFileName($child)
        if ($name -in @('.owner', 'config', 'uninstall.ps1')) { continue }
        $stageName = switch ($name) {
            'gradle' { 'Gradle 缓存' }
            'work' { '源码' }
            'tools' { '构建工具' }
            'bootstrap' { '下载临时文件' }
            default { $name }
        }
        $script:stage = "清理 $stageName"
        Remove-OwnedTree $child
    }
    $script:stage = '清理输出 APK'
    if ($apk -and [IbudaoaCleanup]::Exists($apk)) {
        Assert-NoLink $apk
        # Recheck after cache cleanup in case another application replaced the APK.
        if (!$expectedHash -or [IbudaoaCleanup]::Hash($apk) -ine $expectedHash) { throw "输出 APK 已被替换，保留该文件及配置：$apk" }
        Remove-OwnedTree $apk
    }
    if ($output -and $removeOutputFolder -and [IbudaoaCleanup]::Exists($output)) {
        Assert-NoLink $output
        [IbudaoaCleanup]::RemoveDirectoryIfEmpty($output)
    }
    $script:stage = '清理配置与签名'
    Assert-NoLink $config
    if ([IbudaoaCleanup]::Exists($config)) {
        foreach ($child in [IbudaoaCleanup]::Children($config)) {
            if ([IO.Path]::GetFileName($child) -ne '.owner') { Remove-OwnedTree $child }
        }
        Remove-OwnedTree $configMarker
        [IbudaoaCleanup]::RemoveEmptyDirectory($config)
    }
    $script:stage = '清理卸载入口与标记'
    Assert-NoLink $cache
    Remove-OwnedTree (Join-Path $cache 'uninstall.ps1')
    Remove-OwnedTree (Join-Path $cache '.owner')
    [IbudaoaCleanup]::RemoveEmptyDirectory($cache)
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
    if ($Recover -and $Mode -ne 'uninstall') { throw '-Recover 只能用于 uninstall 模式。' }
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
    if ($Mode -eq 'uninstall') { $stage = '清理'; Initialize-Cleanup }
    if ($Mode -eq 'uninstall' -and ![IbudaoaCleanup]::Exists($cache)) {
        Write-Host '没有需要清理的构建文件。'
        return
    }
    Assert-NoLink $cache
    if ($Mode -eq 'uninstall') {
        $stage = '清理'
        if (!$Recover) { Assert-Owned }
        Invoke-OfflineUninstall
        return
    }
    if (Test-Path -LiteralPath $cache) { Assert-Owned }
    else {
        New-Item -ItemType Directory -Path $cache | Out-Null
        [IO.File]::WriteAllText((Join-Path $cache '.owner'), $owner)
    }
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
            if (Test-Path -LiteralPath $jdk) { Remove-OwnedTree $jdk }
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
    if ($Mode -eq 'uninstall') {
        Write-Host ('处理上述问题后可重试：powershell -NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" uninstall' + $(if ($Recover) { ' -Recover' } else { '' })) -ForegroundColor Red
    } else { Write-Host '请处理上述问题后重新执行同一命令；中断后无需手动删除锁文件。' -ForegroundColor Red }
    $exitCode = 1
} finally {
    try {
        if ($scratch) {
            try { Remove-OwnedTree $scratch }
            catch { Write-Host "临时文件清理失败：$($_.Exception.Message)" -ForegroundColor Red; $exitCode = 1 }
        }
    } finally {
        if ($locked) { $mutex.ReleaseMutex() }
        if ($mutex) { $mutex.Dispose() }
        [Console]::OutputEncoding = $previousConsoleOutput
        [Console]::InputEncoding = $previousConsoleInput
        $OutputEncoding = $previousOutputEncoding
    }
}
exit $exitCode
