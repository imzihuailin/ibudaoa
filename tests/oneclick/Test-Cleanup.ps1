param([Parameter(Mandatory=$true)][string]$Project, [Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference = 'Stop'
$previousLocal = $env:LOCALAPPDATA
$source = [IO.File]::ReadAllText((Join-Path $Project 'scripts/oneclick.ps1'))
$begin = $source.IndexOf('function Initialize-Cleanup')
$end = $source.IndexOf('function Test-Jdk')
Invoke-Expression $source.Substring($begin, $end - $begin)
Initialize-Cleanup
$Root = [IbudaoaCleanup]::FullPath($Root)
[IbudaoaCleanup]::AssertNoLinks($Root)
if ([IbudaoaCleanup]::Exists($Root)) { throw '测试目录必须尚不存在，拒绝清理已有目录。' }
$native = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
# Native fixture creation bypasses PowerShell/.NET MAX_PATH as well, on real Windows.
if (!('CleanupFixture' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class CleanupFixture {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CreateDirectoryW(string path, IntPtr security);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetFileAttributesW(string path, uint attrs);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    public static void FileAt(string path, bool readOnly) {
        bool win = Environment.OSVersion.Platform == PlatformID.Win32NT;
        string parent = path.Substring(0, path.LastIndexOf(win ? '\\' : '/'));
        if (!win) { Directory.CreateDirectory(parent); File.WriteAllText(path, "fixture"); if(readOnly) File.SetAttributes(path, FileAttributes.ReadOnly); return; }
        string root = path.StartsWith(@"\\") ? path.Substring(0, path.IndexOf('\\', path.IndexOf('\\', 2) + 1) + 1) : path.Substring(0, 3);
        string current = root;
        foreach(string part in parent.Substring(root.Length).Split('\\')) {
            current = Path.Combine(current, part);
            if(!CreateDirectoryW(@"\\?\" + current, IntPtr.Zero)) {
                int error = Marshal.GetLastWin32Error(); if(error != 183) throw new Win32Exception(error);
            }
        }
        using(SafeFileHandle handle = CreateFileW(@"\\?\" + path, 0x40000000, 0, IntPtr.Zero, 2, 128, IntPtr.Zero)) {
            if(handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            using(FileStream stream = new FileStream(handle, FileAccess.Write)) stream.WriteByte(1);
        }
        if(readOnly && !SetFileAttributesW(@"\\?\" + path, 1)) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
}
'@
}
$owner = 'ibudaoa-oneclick-v1'
$script:checks = 0
function Check([bool]$Value, [string]$Message) {
    $script:checks++
    if (!$Value) { throw "FAILED: $Message" }
}
function Reject([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    Check $rejected $Message
}
function Read-Host {
    param([string]$Prompt)
    if ($script:answers.Count -eq 0) { throw "Unexpected prompt: $Prompt" }
    $answer = $script:answers[0]
    $script:answers = @($script:answers | Select-Object -Skip 1)
    return $answer
}
function New-Case([string]$Name) {
    $env:LOCALAPPDATA = Join-Path $Root $Name
    $script:cache = Join-Path $env:LOCALAPPDATA 'ibudaoa'
    $script:config = Join-Path $cache 'config'
    [IO.Directory]::CreateDirectory($config) > $null
    [IO.File]::WriteAllText((Join-Path $cache '.owner'), $owner)
    [IO.File]::WriteAllText((Join-Path $config '.owner'), $owner)
    [IO.File]::WriteAllText((Join-Path $config 'signing.keystore'), 'original signature')
    [IO.File]::WriteAllText((Join-Path $config 'amap.key'), ('a' * 32))
    [IO.File]::WriteAllText((Join-Path $cache 'uninstall.ps1'), 'local retry entry')
    $script:Recover = $false
    $script:stage = '清理'
}
function Long-File([int]$Length) {
    $parent = Join-Path $cache 'gradle'
    $separator = [IO.Path]::DirectorySeparatorChar
    while ($Length - $parent.Length - 1 -gt 180) { $parent += $separator + ('d' * 40) }
    $path = $parent + $separator + ('f' * ($Length - $parent.Length - 5)) + '.pom'
    [CleanupFixture]::FileAt($path, $true)
    return $path
}
function Add-Output {
    $script:output = Join-Path $env:LOCALAPPDATA '下载 space'
    [IO.Directory]::CreateDirectory($output) > $null
    $script:apk = Join-Path $output 'ibudaoa.apk'
    [IO.File]::WriteAllText($apk, 'our apk')
    [IO.File]::WriteAllText((Join-Path $output 'keep.txt'), 'user file')
    [IO.File]::WriteAllText((Join-Path $config 'output.path'), $output)
    [IO.File]::WriteAllText((Join-Path $config 'output.created'), $output)
    [IO.File]::WriteAllText((Join-Path $config 'output.sha256'), [IbudaoaCleanup]::Hash($apk))
}
try {
    New-Case 'normal'
    $p262 = Long-File 262
    $p550 = Long-File 550
    Check ($p262.Length -eq 262 -and $p550.Length -eq 550) 'fixtures have exact long lengths'
    Add-Output
    $outside = Join-Path $Root 'outside'
    [IO.Directory]::CreateDirectory($outside) > $null
    [IO.File]::WriteAllText((Join-Path $outside 'keep.txt'), 'untouched')
    $link = Join-Path $cache 'linked-outside'
    if ($native) { New-Item -ItemType Junction -Path $link -Target $outside > $null }
    else { New-Item -ItemType SymbolicLink -Path $link -Target $outside > $null }
    Reject { Assert-NoLink (Join-Path $link 'keep.txt') } 'reject linked ancestors'
    $script:answers = @('')
    Invoke-OfflineUninstall
    Check ([IbudaoaCleanup]::Exists($p550)) 'cancel preserves long files'

    # Simulate a cache operation failure, using the production uninstall ordering.
    $originalRemove = (Get-Command Remove-OwnedTree).ScriptBlock
    function Remove-OwnedTree([string]$Path) {
        if ([IO.Path]::GetFileName($Path) -eq 'gradle') { throw [IO.IOException]::new("fixture cache failure: $Path") }
        & $originalRemove $Path
    }
    $script:answers = @('y')
    Reject { Invoke-OfflineUninstall } 'cache failure stops cleanup'
    Set-Item Function:Remove-OwnedTree -Value $originalRemove
    Check ($stage -eq '清理 Gradle 缓存') 'failure stage names Gradle cache'
    foreach ($protected in @('.owner', 'config/.owner', 'config/signing.keystore', 'config/amap.key', 'uninstall.ps1')) {
        Check ([IbudaoaCleanup]::Exists((Join-Path $cache $protected))) "cache failure preserves $protected"
    }
    Check ([IbudaoaCleanup]::Exists($apk)) 'cache failure preserves output APK'
    $script:answers = @('y')
    Invoke-OfflineUninstall
    Check (![IbudaoaCleanup]::Exists($cache)) 'retry removes long cache and config'
    Check (![IbudaoaCleanup]::Exists($apk)) 'matching APK removed after caches'
    Check ([IO.File]::ReadAllText((Join-Path $output 'keep.txt')) -eq 'user file') 'other output files preserved'
    Check ([IO.File]::ReadAllText((Join-Path $outside 'keep.txt')) -eq 'untouched') 'junction target preserved'
    Remove-OwnedTree (Join-Path $Root 'already-gone')
    Check $true 'missing files are harmless'

    New-Case 'replaced'
    Add-Output
    $sentinel = Join-Path $cache 'gradle/sentinel'
    [CleanupFixture]::FileAt($sentinel, $false)
    [IO.File]::WriteAllText($apk, 'replacement')
    $script:answers = @('y')
    Reject { Invoke-OfflineUninstall } 'APK preflight rejects replacement'
    Check ([IbudaoaCleanup]::Exists($sentinel)) 'APK preflight precedes any cache deletion'
    Check ([IO.File]::ReadAllText((Join-Path $config 'signing.keystore')) -eq 'original signature') 'replacement preserves signing'

    New-Case 'recovery'
    Add-Output
    Remove-OwnedTree (Join-Path $cache '.owner')
    Remove-OwnedTree (Join-Path $config '.owner')
    Reject { Assert-Owned } 'missing root marker rejects normal cleanup'
    $script:Recover = $true
    $script:answers = @('y')
    Invoke-OfflineUninstall
    Check ([IbudaoaCleanup]::Exists($cache)) 'recovery does not accept y'
    $script:answers = @([IbudaoaCleanup]::FullPath($outside))
    Invoke-OfflineUninstall
    Check ([IbudaoaCleanup]::Exists($cache)) 'recovery rejects another directory confirmation'
    $script:answers = @([IbudaoaCleanup]::FullPath($cache))
    Invoke-OfflineUninstall
    Check (![IbudaoaCleanup]::Exists($cache)) 'recovery removes markerless leftovers'
    Check ([IbudaoaCleanup]::Exists($apk)) 'untrusted output record does not authorize outside APK deletion'

    New-Case 'missing-fingerprint'
    Add-Output
    Remove-OwnedTree (Join-Path $config 'output.sha256')
    $script:Recover = $true
    $script:answers = @([IbudaoaCleanup]::FullPath($cache))
    Invoke-OfflineUninstall
    Check (![IbudaoaCleanup]::Exists($cache) -and [IbudaoaCleanup]::Exists($apk)) 'recovery without fingerprint preserves outside APK'

    New-Case 'disappearing'
    $one = Join-Path $cache 'gradle/one'
    [CleanupFixture]::FileAt($one, $false)
    $listing = [IbudaoaCleanup]::Children((Join-Path $cache 'gradle'))
    Remove-OwnedTree $one
    foreach ($child in $listing) { Remove-OwnedTree $child }
    Remove-OwnedTree (Join-Path $cache 'gradle')
    Check (![IbudaoaCleanup]::Exists((Join-Path $cache 'gradle'))) 'disappearing entries do not block directory removal'

    if ($native) {
        New-Case 'locked'
        $file = Join-Path $cache 'gradle/locked.bin'
        [CleanupFixture]::FileAt($file, $false)
        $handle = [IO.File]::Open($file, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        try {
            $script:answers = @('y')
            $failed = $false
            try { Invoke-OfflineUninstall } catch {
                $failed = $true
                Check ($_.Exception.ToString().Contains('32') -and $_.Exception.ToString().Contains('locked.bin')) 'locked file reports Windows error and path'
            }
            Check $failed 'native sharing violation stops cleanup'
            Check ([IbudaoaCleanup]::Exists((Join-Path $config 'signing.keystore'))) 'native failure preserves signing'
        } finally { $handle.Dispose() }
        $script:answers = @('y')
        Invoke-OfflineUninstall
        Check (![IbudaoaCleanup]::Exists($cache)) 'native retry after releasing file succeeds'
        New-Case 'access-denied'
        $parent = Join-Path $cache 'gradle/denied'
        $file = Join-Path $parent 'protected.bin'
        [CleanupFixture]::FileAt($file, $false)
        $originalAcl = Get-Acl -LiteralPath $parent
        $restricted = Get-Acl -LiteralPath $parent
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity,
            ([Security.AccessControl.FileSystemRights]::Delete -bor [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles),
            ([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),
            [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Deny)
        $restricted.AddAccessRule($rule)
        Set-Acl -LiteralPath $parent -AclObject $restricted
        try {
            $script:answers = @('y')
            $failed = $false
            try { Invoke-OfflineUninstall } catch {
                $failed = $true
                Check ($_.Exception.ToString().Contains('Windows 错误 5') -and $_.Exception.ToString().Contains('protected.bin')) 'access denied reports error 5 and path'
            }
            Check $failed 'native access denied stops cleanup'
            Check ([IbudaoaCleanup]::Exists((Join-Path $config 'signing.keystore'))) 'access denial preserves signing'
        } finally { Set-Acl -LiteralPath $parent -AclObject $originalAcl }
        $script:answers = @('y')
        Invoke-OfflineUninstall
        Check (![IbudaoaCleanup]::Exists($cache)) 'native retry after restoring fixture ACL succeeds'

    }
    Write-Host "Cleanup tests: PASS $checks checks; native Windows backend=$native"
} finally {
    $env:LOCALAPPDATA = $previousLocal
    if ($originalRemove) { Set-Item Function:Remove-OwnedTree -Value $originalRemove }
    Remove-OwnedTree $Root
}
