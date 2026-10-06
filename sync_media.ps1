$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$mediaRoot = Join-Path $projectRoot "test-media"
$adb = "D:\tools\android-sdk\platform-tools\adb.exe"
$remoteRoot = "/sdcard/Movies/SleepVideoPlayer"
$remoteParent = "/sdcard/Movies"
$backupRoot = "/sdcard/Movies/.SleepVideoPlayer-backups"
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$incomingRoot = "$remoteRoot.incoming-$stamp"
$backupPath = "$backupRoot/$stamp"

function Invoke-Adb([string[]]$Arguments) {
    # adb writes normal transfer progress to stderr; do not let PowerShell's
    # Stop preference turn that progress text into a terminating error.
    $savedErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $adb @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorAction
    }
    if ($exitCode -ne 0) {
        throw ("adb failed: " + ($output -join "`n"))
    }
    return ($output | ForEach-Object { $_.ToString() })
}

if (-not (Test-Path -LiteralPath $adb)) {
    throw "Android SDK was not found at $adb"
}

if (-not (Test-Path -LiteralPath $mediaRoot)) {
    New-Item -ItemType Directory -Path $mediaRoot | Out-Null
    Write-Host "Created host media folder: $mediaRoot"
    Write-Host "Put MP4 and VTT files there, then run this script again."
    exit 0
}

$emulators = @(& $adb devices | Select-Object -Skip 1 | ForEach-Object {
    if ($_ -match '^(emulator-[^\s]+)\s+device(?:\s|$)') { $matches[1] }
})

if ($emulators.Count -eq 0) {
    throw "No running emulator found. Start run_debug.bat first. Physical phones are not used by this sync script."
}

$serial = $emulators[0]
if ($emulators.Count -gt 1) {
    Write-Host "Multiple emulators found; using $serial."
}

$files = @(Get-ChildItem -LiteralPath $mediaRoot -Force -File -Recurse)
Write-Host "Host source : $mediaRoot"
Write-Host "Emulator    : $serial"
Write-Host "Device path : $remoteRoot"
Write-Host "Files       : $($files.Count)"
Write-Host ""

# Stage the new tree first. The current device tree stays untouched if push fails.
Invoke-Adb @("-s", $serial, "shell", "mkdir", "-p", $remoteParent, $backupRoot, $incomingRoot) | Out-Null
if ($files.Count -gt 0) {
    Invoke-Adb @("-s", $serial, "push", (Join-Path $mediaRoot "."), "$incomingRoot/") | Out-Null
}

# Archive the previous tree instead of deleting it, then promote the staged tree.
$swapCommand = "if [ -d '$remoteRoot' ]; then mv '$remoteRoot' '$backupPath'; fi; mv '$incomingRoot' '$remoteRoot'"
Invoke-Adb @("-s", $serial, "shell", $swapCommand) | Out-Null

Write-Host ""
Write-Host "Sync completed."
Write-Host "Previous device files were archived at: $backupPath"
Write-Host "The app should use the directory: $remoteRoot"
