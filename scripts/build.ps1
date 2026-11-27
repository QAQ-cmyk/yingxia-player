param([string]$Gradle = '', [string]$Project = (Split-Path $PSScriptRoot -Parent), [switch]$Offline)
$ErrorActionPreference = 'Stop'
if (-not $Gradle) { $Gradle = Join-Path $Project 'gradlew.bat' }
$tasks = @(
    '-p', $Project,
    ':shared-protocol:test',
    ':server:test',
    ':app:testOfflineDebugUnitTest',
    ':app:testSyncDebugUnitTest',
    ':app:lintOfflineDebug',
    ':app:assembleOfflineDebug',
    ':app:assembleOfflineDebugAndroidTest',
    ':app:assembleOfflineRelease',
    ':app:assembleSyncRelease'
)
if ($Offline) { $tasks += '--offline' }
& $Gradle @tasks
if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed.' }
Write-Output "Builds: $Project\app\build\outputs\apk"
