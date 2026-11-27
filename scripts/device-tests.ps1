param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][string]$Sdk,
    [ValidateSet('offline', 'sync')][string]$Edition = 'offline',
    [string]$Project = (Split-Path $PSScriptRoot -Parent)
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$appApk = Join-Path $Project "app/build/outputs/apk/$Edition/debug/app-$Edition-debug.apk"
$testApk = Join-Path $Project "app/build/outputs/apk/androidTest/$Edition/debug/app-$Edition-debug-androidTest.apk"
$testRunner = if ($Edition -eq 'sync') { 'cn.yingxia.player.sync.test/androidx.test.runner.AndroidJUnitRunner' } else { 'cn.yingxia.player.test/androidx.test.runner.AndroidJUnitRunner' }

& $adb -s $Serial install -r $appApk
if ($LASTEXITCODE -ne 0) { throw 'Installing the debug app failed. A release app with a different signing certificate must be removed manually first.' }
& $adb -s $Serial install -r $testApk
if ($LASTEXITCODE -ne 0) { throw 'Installing tests failed.' }
$result = & $adb -s $Serial shell am instrument -w $testRunner
$result
if (($result -join "`n") -notmatch 'OK \(\d+ tests\)') { throw 'Device checks did not pass.' }
