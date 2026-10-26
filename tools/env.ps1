# Окружение разработки Painter. Всё локально в папке проекта.
# Использование: . .\tools\env.ps1
$root = Split-Path -Parent $PSScriptRoot
$env:PAINTER_ROOT = $root
# In a git worktree .tools/.venv are absent: use the main checkout's copies.
if (-not (Test-Path "$root\.tools")) {
    $common = (git -C $root rev-parse --path-format=absolute --git-common-dir) 2>$null
    if ($common) { $root = Split-Path -Parent $common }
}
# Build SDK for worktrees without local.properties. The emulator needs its own SDK: set
# ANDROID_HOME = ANDROID_SDK_ROOT = $env:PAINTER_EMU_SDK before starting it.
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
$env:GRADLE_USER_HOME = "$root\.tools\gradle"
$env:ANDROID_AVD_HOME = "$root\.tools\avd"
$env:ANDROID_EMULATOR_HOME = "$root\.tools"
$env:PAINTER_EMU_SDK = "$root\.tools\android-sdk"
$env:Path = "$root\.venv\Scripts;$root\.tools\gradle-8.11.1\bin;$root\.tools\android-sdk\platform-tools;$root\.tools\android-sdk\emulator;$env:JAVA_HOME\bin;$env:Path"
Write-Host "Painter env: GRADLE_USER_HOME=$env:GRADLE_USER_HOME AVD=$env:ANDROID_AVD_HOME"
