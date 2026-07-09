$ErrorActionPreference = "Stop"

$adb = "C:\Users\lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"

& $adb devices
& $adb install -r $apk
