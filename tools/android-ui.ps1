param(
    [ValidateSet('Start', 'Status', 'Stop', 'Install')][string]$Action = 'Status',
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$AvdHome,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$AvdName = 'termdesk-ui-api35',
    [int]$Port = 5556,
    [string]$Apk
)

$ErrorActionPreference = 'Stop'
if (!$SdkRoot) { throw 'Set ANDROID_HOME or pass -SdkRoot.' }
if (!$AvdHome) { $AvdHome = Join-Path (Split-Path $SdkRoot -Parent) 'Avd' }
if ($Port -lt 5554 -or $Port -gt 5682 -or ($Port % 2) -ne 0) { throw 'Use an even emulator port between 5554 and 5682.' }
$env:ANDROID_AVD_HOME = $AvdHome
$adbTool = Join-Path $SdkRoot 'platform-tools\adb.exe'
$emulatorTool = Join-Path $SdkRoot 'emulator\emulator.exe'
$serial = "emulator-$Port"
if (!(Test-Path -LiteralPath $adbTool)) { throw 'Android platform-tools not found.' }

if ($Action -eq 'Start') {
    if (!(Test-Path -LiteralPath $emulatorTool)) { throw 'Android Emulator not installed.' }
    if (!(Test-Path -LiteralPath (Join-Path $AvdHome "$AvdName.avd\config.ini"))) { throw 'Create the named AVD first; this script never overwrites a device.' }
    $devices = & $adbTool devices
    if ($devices -match "(?m)^$serial\s") { throw "$serial is already in use. Check it with Status." }
    & $emulatorTool -accel-check
    if ($LASTEXITCODE -ne 0) { throw 'Hardware acceleration is not available. No system settings were changed.' }
    Start-Process -FilePath $emulatorTool -WindowStyle Hidden -ArgumentList @(
        '-avd', $AvdName, '-port', "$Port", '-memory', '4096', '-cores', '4',
        '-gpu', 'auto', '-timezone', 'Asia/Shanghai', '-no-window', '-no-audio', '-no-boot-anim'
    ) | Out-Null
    Write-Output "Started $AvdName as $serial without a desktop window. Wait for sys.boot_completed=1."
    exit 0
}

$identity = & $adbTool -s $serial emu avd name 2>$null
if ($LASTEXITCODE -ne 0 -or $identity -notcontains $AvdName) { throw "$serial is not the expected $AvdName device. No device was modified." }
switch ($Action) {
    'Status' {
        Write-Output "$AvdName / $serial"
        & $adbTool -s $serial shell getprop sys.boot_completed
        & $adbTool -s $serial shell getprop ro.build.version.release
    }
    'Stop' { & $adbTool -s $serial emu kill }
    'Install' {
        if (!$Apk -or !(Test-Path -LiteralPath $Apk)) { throw 'Pass -Apk with an existing APK.' }
        & $adbTool -s $serial install -r -t $Apk
        if ($LASTEXITCODE -ne 0) { throw 'Install failed; no uninstall or data wipe was attempted.' }
    }
}
