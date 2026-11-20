# Builds Buds Control for Windows into windows\publish\BudsControl.exe (one file, ~200 KB).
# Needs the .NET 9 SDK; the exe needs the .NET 9 Desktop Runtime. No NuGet packages are used.
# Usage:  .\build.ps1          build
#         .\build.ps1 -Run     build, then start it (closes a running copy first)

param([switch]$Run)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_NOLOGO = '1'

if ($Run) { Get-Process BudsControl -ErrorAction SilentlyContinue | Stop-Process }

dotnet publish -c Release -r win-x64 --self-contained false `
    -p:PublishSingleFile=true -p:EnableSingleFileAnalyzer=false -o publish
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }

$exe = Join-Path $PSScriptRoot 'publish\BudsControl.exe'
Write-Host ("Built {0:N0} KB -> {1}" -f ((Get-Item $exe).Length / 1KB), $exe) -ForegroundColor Green
if ($Run) { Start-Process $exe }
