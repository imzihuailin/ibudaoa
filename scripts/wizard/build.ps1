$ErrorActionPreference = 'Stop'
$framework = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (!(Test-Path -LiteralPath $framework)) { throw '需要 Windows x64 和 .NET Framework 4.x 编译器。' }
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& $framework /nologo /target:winexe /platform:x64 /codepage:65001 /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /resource:"$root\oneclick.ps1,oneclick.ps1" /out:"$output\ibudaoa-setup.exe" "$PSScriptRoot\Installer.cs"
if ($LASTEXITCODE -ne 0) { throw '向导编译失败。' }
Write-Host "已生成：$output\ibudaoa-setup.exe"
