# Builds the debug APK and installs it to the connected phone.
# Usage:  .\build.ps1          build + install
#         .\build.ps1 -NoInstall    build only

param([switch]$NoInstall)

$ErrorActionPreference = 'Stop'
# Portable JDK 17 + Android SDK folder; override with the ANDROID_TOOLS environment variable.
$tools = if ($env:ANDROID_TOOLS) { $env:ANDROID_TOOLS } else { Join-Path $env:USERPROFILE '.androidtools' }

$env:JAVA_HOME = (Get-ChildItem $tools -Directory -Filter 'jdk-17*' | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$tools\sdk"
$adb = "$tools\sdk\platform-tools\adb.exe"

Set-Location $PSScriptRoot

Write-Host 'Building...' -ForegroundColor Cyan
& .\gradlew.bat assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host ("Built {0:N1} MB -> {1}" -f ((Get-Item $apk).Length / 1MB), $apk) -ForegroundColor Green

if ($NoInstall) { return }

$devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
if (-not $devices) {
    Write-Host 'No device on adb. Plug the phone in, enable USB debugging, and accept the prompt.' -ForegroundColor Yellow
    return
}
if ($devices -match 'unauthorized') {
    Write-Host 'Device is unauthorized. Accept the USB debugging prompt on the phone.' -ForegroundColor Yellow
    return
}

Write-Host 'Installing...' -ForegroundColor Cyan
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }


$ErrorActionPreference = "Continue"
& $adb shell monkey -p com.kuek.budscontrol -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Write-Host 'Installed and launched.' -ForegroundColor Green
