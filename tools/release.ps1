# Publishes a Painter release: bumps the version, builds a signed APK, tags and uploads it.
# Usage: .\tools\release.ps1 -Version 0.2.0 [-Notes .\notes\0.2.0.md] [-Prerelease]
#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [switch]$Prerelease
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# Runs a native command; judges it by exit code only. Under "Stop", PowerShell 5.1 turns any
# stderr line (git CRLF warnings, gradle progress) into a terminating error.
function Invoke-Native([string]$what, [scriptblock]$cmd) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $cmd } finally { $ErrorActionPreference = $old }
    if ($LASTEXITCODE -ne 0) { throw "$what failed with exit code $LASTEXITCODE" }
}
Set-Location $root

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version must look like 0.2.0 (got '$Version')"
}
$parts = $Version.Split('.')
$versionCode = ([int]$parts[0] * 10000) + ([int]$parts[1] * 100) + [int]$parts[2]

if ($Notes -and -not (Test-Path $Notes)) {
    throw "Notes file not found: $Notes"
}

# 1. Bump versionName / versionCode in the app module.
$gradleFile = Join-Path $root "app\build.gradle.kts"
$utf8 = New-Object System.Text.UTF8Encoding($false)
$text = [IO.File]::ReadAllText($gradleFile, $utf8)
$text = [regex]::Replace($text, 'versionCode\s*=\s*\d+', "versionCode = $versionCode")
$text = [regex]::Replace($text, 'versionName\s*=\s*"[^"]*"', "versionName = `"$Version`"")
[IO.File]::WriteAllText($gradleFile, $text, $utf8)
Write-Host "Version set to $Version ($versionCode)"

# 2. Build the signed release APK.
. (Join-Path $root "tools\env.ps1")
Invoke-Native "gradlew :app:assembleRelease" { & (Join-Path $root "gradlew.bat") ":app:assembleRelease" "--console=plain" }

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) {
    throw "Signed APK not found: $apk (is .tools/signing/keystore.properties configured?)"
}

# 3. Keep a copy next to the other local release artifacts.
$releasesDir = Join-Path $root ".tools\releases"
New-Item -ItemType Directory -Force -Path $releasesDir | Out-Null
$artifact = Join-Path $releasesDir "painter-$Version.apk"
Copy-Item -Path $apk -Destination $artifact -Force
Write-Host "APK copied to $artifact"

# 4. Commit, tag and push.
Invoke-Native "git commit" { git commit -am "Release v$Version" }
Invoke-Native "git tag" { git tag "v$Version" }
Invoke-Native "git push" { git push origin main --tags }

# 5. Publish the GitHub release.
$ghArgs = @(
    "release", "create", "v$Version", $artifact
    "--repo", "IWatman/Painter"
    "--title", "Painter $Version"
)
if ($Notes) {
    $ghArgs += @("--notes-file", (Resolve-Path $Notes).Path)
} else {
    $ghArgs += "--generate-notes"
}
if ($Prerelease) {
    $ghArgs += "--prerelease"
}
Invoke-Native "gh release create" { gh @ghArgs }

Write-Host "Released v$Version"
