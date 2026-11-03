param([string]$Base,[string]$Checksum,[string]$Root,[string]$Fingerprint)
$ErrorActionPreference='Stop'
$utf8=New-Object Text.UTF8Encoding($false)
[Console]::OutputEncoding=$utf8
# The exact production helper is extracted by the Python harness, without executing the entrypoint.
. (Join-Path $Root 'transport.ps1')
Add-Type -TypeDefinition @'
#pragma warning disable SYSLIB0014
public static class LocalTestTrust {
 public static void Configure(string fingerprint) {
  System.Net.ServicePointManager.ServerCertificateValidationCallback = (s,c,ch,e) =>
   c != null && c.GetCertHashString().Equals(fingerprint,System.StringComparison.OrdinalIgnoreCase);
 }
}
'@
[LocalTestTrust]::Configure($Fingerprint)
$destination=Join-Path $Root 'ps-download.bin'
Get-Download @("$Base/missing","$Base/bad","$Base/redirect") $destination $Checksum
if([IO.File]::ReadAllText($destination) -ne 'verified payload'){throw 'Fallback did not select verified file'}
[IO.File]::WriteAllText($destination,'old file')
$failed=$false
try {Get-Download @("$Base/bad","$Base/missing") $destination $Checksum} catch {$failed=$true}
if(!$failed -or [IO.File]::ReadAllText($destination) -ne 'old file'){throw 'Failed download overwrote destination'}
foreach($route in @('short','stall','insecure')) {
 $failed=$false
 $clock=[Diagnostics.Stopwatch]::StartNew()
 try {Get-Download @("$Base/$route") $destination '' 1000 150} catch {$failed=$true}
 if(!$failed){throw "Expected failure: $route"}
 if($route -eq 'stall' -and $clock.ElapsedMilliseconds -gt 1800){throw 'Read timeout did not stop stalled transfer'}
 if(Test-Path -LiteralPath ($destination+'.part')){throw 'Partial file leaked'}
}
Write-Host 'PowerShell real HTTPS fallback / checksum / timeout / short file / redirect protection: PASS'
