param([string]$Gradle = '', [string]$Project = (Split-Path $PSScriptRoot -Parent), [switch]$Offline)
$ErrorActionPreference = 'Stop'
if (-not $Gradle) { $Gradle = Join-Path $Project 'gradlew.bat' }
$tasks = @('-p', $Project, 'testDebugUnitTest', 'lintDebug', 'assembleDebug', 'assembleDebugAndroidTest', 'assembleRelease')
if ($Offline) { $tasks += '--offline' }
& $Gradle @tasks
if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed.' }
Write-Output "Builds: $Project\app\build\outputs\apk"
