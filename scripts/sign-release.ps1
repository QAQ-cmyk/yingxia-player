param([Parameter(Mandatory)][string]$Sdk, [Parameter(Mandatory)][string]$KeyStore, [Parameter(Mandatory)][string]$PasswordFile, [string]$Alias = 'yingxia', [string]$Version = '1.2.1', [string]$Project = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$signer = Join-Path $Sdk 'build-tools/35.0.0/apksigner.bat'
$unsigned = Join-Path $Project 'app/build/outputs/apk/release/app-release-unsigned.apk'
$output = Join-Path $Project "delivery/YingXia-$Version.apk"
& $signer sign --ks $KeyStore --ks-key-alias $Alias --ks-pass "file:$PasswordFile" --out $output $unsigned
if ($LASTEXITCODE -ne 0) { throw 'Signing failed.' }
& $signer verify --verbose --print-certs $output
if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
Get-FileHash -Algorithm SHA256 -LiteralPath $output
