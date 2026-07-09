$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:GRADLE_USER_HOME = "E:\android-gradle-cache"

Set-Location $PSScriptRoot
& "E:\android-tools\gradle-8.7\bin\gradle.bat" testDebugUnitTest assembleDebug --no-daemon --console=plain

Write-Host ""
Write-Host "APK: $PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
