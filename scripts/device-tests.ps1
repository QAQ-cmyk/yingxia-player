param([Parameter(Mandatory)][string]$Serial, [Parameter(Mandatory)][string]$Sdk, [string]$Project = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
& $adb -s $Serial install -r (Join-Path $Project 'app/build/outputs/apk/debug/app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'Installing the debug app failed. A release app with a different signing certificate must be removed manually first.' }
& $adb -s $Serial install -r (Join-Path $Project 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Installing tests failed.' }
$result = & $adb -s $Serial shell am instrument -w cn.yingxia.player.test/androidx.test.runner.AndroidJUnitRunner
$result
if (($result -join "`n") -notmatch 'OK \(\d+ tests\)') { throw 'Device checks did not pass.' }
