# Starts the painter_tab emulator from the project's own SDK and waits until it has booted.
# Usage: .\tools\emu.ps1   (prints the serial of painter_tab)
# The AVD's system image lives in .tools\android-sdk, not in the build SDK of local.properties, so the emulator
# needs ANDROID_SDK_ROOT pointed there; without it it exits with "Cannot find AVD system path".
$root = Split-Path -Parent $PSScriptRoot
$sdk = "$root\.tools\android-sdk"
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_AVD_HOME = "$root\.tools\avd"
$adb = "$sdk\platform-tools\adb.exe"

function Find-PainterTab {
    foreach ($d in (& $adb devices | Select-String "emulator-\d+" | ForEach-Object { $_.Matches.Value })) {
        if ((& $adb -s $d emu avd name | Select-Object -First 1) -eq "painter_tab") { return $d }
    }
    return $null
}

$serial = Find-PainterTab
if (-not $serial) {
    Start-Process -FilePath "$sdk\emulator\emulator.exe" -ArgumentList "-avd", "painter_tab", "-no-snapshot-save" `
        -WindowStyle Minimized | Out-Null
    foreach ($i in 1..60) {
        Start-Sleep 3
        $serial = Find-PainterTab
        if ($serial) { break }
    }
    if (-not $serial) { throw "painter_tab did not come up" }
}
foreach ($i in 1..80) {
    if ("$(& $adb -s $serial shell getprop sys.boot_completed 2>$null)" -match "1") { break }
    Start-Sleep 3
}
$serial
