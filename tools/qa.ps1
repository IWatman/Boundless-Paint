# QA helper for the emulator. Usage: .\tools\qa.ps1 <command> [args]
#   tap x y | swipe x1 y1 x2 y2 [ms] | ui | shot name | text "..." | back | log
param([string]$cmd, [Parameter(ValueFromRemainingArguments)] $rest)
$root = Split-Path -Parent $PSScriptRoot
. "$root\tools\env.ps1" | Out-Null
# Serial of the painter_tab AVD: ports change between runs and other projects run their own emulators.
$serial = $env:PAINTER_SERIAL
if (-not $serial) {
    foreach ($d in (adb devices | Select-String "emulator-\d+" | ForEach-Object { $_.Matches.Value })) {
        if ((adb -s $d emu avd name | Select-Object -First 1) -eq "painter_tab") { $serial = $d; break }
    }
}
if (-not $serial) { throw "painter_tab emulator is not running" }
$qa = "C:/Users/dushc/.claude/plugins/cache/anthropic-plugin-directory/android-emulator-qa-plugin/1.0.0-18e69487874c/scripts/android-qa.py"
$out = "$root\.tools\qa"
New-Item -ItemType Directory -Force $out | Out-Null
switch ($cmd) {
    "tap"   { adb -s $serial shell input tap $rest[0] $rest[1] }
    "swipe" { $ms = if ($rest.Count -ge 5) { $rest[4] } else { 400 }; adb -s $serial shell input swipe $rest[0] $rest[1] $rest[2] $rest[3] $ms }
    "text"  { adb -s $serial shell input text $rest[0] }
    "back"  { adb -s $serial shell input keyevent 4 }
    "ui"    {
        python $qa dump-ui --serial $serial --out "$out/ui.xml" | Out-Null
        python $qa summarize-ui --input "$out/ui.xml" --out "$out/ui-summary.txt" | Out-Null
        Get-Content "$out\ui-summary.txt"
    }
    "shot"  { python $qa screenshot --serial $serial --out "$out/$($rest[0]).png" }
    "log"   { adb -s $serial logcat -d -b crash; adb -s $serial logcat -d -s AndroidRuntime:E Painter:* | Select-Object -Last 40 }
}
