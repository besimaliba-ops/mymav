# Install MyMav (real SDK) onto a physical Android phone — never the emulator.
# Usage (PowerShell), from the repo root:
#   .\tools\install-phone.ps1
# Optional:
#   .\tools\install-phone.ps1 -Serial YOUR_ADB_SERIAL

param(
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$jbrCandidates = @(
    (Join-Path ${env:ProgramFiles} "Android\Android Studio\jbr"),
    (Join-Path $env:LOCALAPPDATA "Programs\Android\Android Studio\jbr")
)
$jbr = $jbrCandidates | Where-Object { Test-Path (Join-Path $_ "bin\java.exe") } | Select-Object -First 1
if (-not $jbr) {
    Write-Error "JAVA_HOME candidate missing (install Android Studio or set JAVA_HOME to a JDK 17+)."
}
$env:JAVA_HOME = $jbr
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
}
$env:Path = "$env:JAVA_HOME\bin;$(Split-Path $adb -Parent);" + $env:Path

function Get-PhysicalSerials {
    $lines = & $adb devices | Select-Object -Skip 1
    $serials = @()
    foreach ($line in $lines) {
        if ($line -match '^\s*$') { continue }
        if ($line -notmatch '^(\S+)\s+device\b') { continue }
        $id = $Matches[1]
        if ($id -like "emulator-*") { continue }
        $serials += $id
    }
    return $serials
}

$physical = @(Get-PhysicalSerials)
if ($physical.Count -eq 0) {
    Write-Error "No physical phone online (emulator-only does not count). Connect USB debugging and unlock the phone."
}

if ($Serial) {
    if ($physical -notcontains $Serial) {
        Write-Error "Requested serial '$Serial' is not an online physical device. Online: $($physical -join ', ')"
    }
    $target = $Serial
} else {
    $target = $physical[0]
    if ($physical.Count -gt 1) {
        Write-Warning "Multiple physical devices; using first: $target (pass -Serial to pick). Online: $($physical -join ', ')"
    }
}

$env:ANDROID_SERIAL = $target
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "Installing MyMav on physical device: $target"
& $adb -s $target get-state | Out-Host

& .\gradlew.bat `
    ":composeApp:installDebug" `
    "-Pmaverick.useSdk=true" `
    "-Pandroid.injected.invoked.from.ide=true"
exit $LASTEXITCODE
