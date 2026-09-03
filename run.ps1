# ==========================================
# School Attendance - Development Runner (Zero API / Standalone)
# ==========================================

$ErrorActionPreference = "Stop"

# ------------------------------------------
# Configuration
# ------------------------------------------

$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$APP_PACKAGE = "com.school.attendance"
$APK_PATH = "app\build\outputs\apk\debug\app-debug.apk"
$DB_PORT = 5432

# ==========================================
# START
# ==========================================

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " School Attendance Standalone Runner (No API)" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# ==========================================
# [1/6] CHECK ADB
# ==========================================

Write-Host "[1/6] Checking ADB..." -ForegroundColor Yellow

if (-not (Test-Path $ADB)) {
    # Fallback check on PATH
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) {
        $ADB = $adbCmd.Source
    } else {
        Write-Host ""
        Write-Host "ERROR: ADB not found at $ADB" -ForegroundColor Red
        Write-Host ""
        exit 1
    }
}

Write-Host "ADB found: $ADB" -ForegroundColor Green

# ==========================================
# GET CONNECTED DEVICES
# ==========================================

Write-Host ""
Write-Host "Checking connected Android devices..." -ForegroundColor Yellow

$ADBOutput = & $ADB devices

$DeviceLines = @(
    $ADBOutput | Where-Object {
        ($_ -match "\s+device$") -and ($_ -notmatch "^List of")
    }
)

if ($DeviceLines.Count -eq 0) {
    Write-Host ""
    Write-Host "ERROR: No Android device detected." -ForegroundColor Red
    Write-Host "Please connect your phone via USB or Wireless ADB." -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

$DeviceIDs = @()
foreach ($line in $DeviceLines) {
    $deviceId = ($line -replace "\s+device$", "").Trim()
    if ($deviceId) {
        $DeviceIDs += $deviceId
    }
}

Write-Host ""
Write-Host "Connected Android devices:" -ForegroundColor Green
for ($i = 0; $i -lt $DeviceIDs.Count; $i++) {
    Write-Host "[$($i + 1)] $($DeviceIDs[$i])"
}

$DEVICE_ID = $DeviceIDs[0]
Write-Host ""
Write-Host "Selected device: $DEVICE_ID" -ForegroundColor Cyan

# ==========================================
# [2/6] ADB REVERSE FOR POSTGRESQL (5432)
# ==========================================

Write-Host ""
Write-Host "[2/6] Setting ADB reverse for PostgreSQL (Port $DB_PORT)..." -ForegroundColor Yellow

& $ADB -s $DEVICE_ID reverse tcp:$DB_PORT tcp:$DB_PORT

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "WARNING: ADB reverse for PostgreSQL returned code $LASTEXITCODE" -ForegroundColor Yellow
} else {
    Write-Host "ADB reverse configured for PostgreSQL (5432)." -ForegroundColor Green
}

# ==========================================
# [3/6] STOP EXISTING APP
# ==========================================

Write-Host ""
Write-Host "[3/6] Stopping existing Android app..." -ForegroundColor Yellow

& $ADB -s $DEVICE_ID shell am force-stop $APP_PACKAGE
Write-Host "Existing app stopped." -ForegroundColor Green

# ==========================================
# [4/6] BUILD ANDROID APK
# ==========================================

Write-Host ""
Write-Host "[4/6] Building Android APK (gradlew.bat assembleDebug)..." -ForegroundColor Yellow

cmd.exe /c "gradlew.bat assembleDebug"

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build issue encountered, resetting daemons and retrying..." -ForegroundColor Yellow
    cmd.exe /c "gradlew.bat --stop"
    Start-Sleep -Seconds 2
    cmd.exe /c "gradlew.bat assembleDebug"
}

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: Gradle build failed." -ForegroundColor Red
    exit 1
}

Write-Host "Gradle build successful." -ForegroundColor Green

if (-not (Test-Path $APK_PATH)) {
    Write-Host "ERROR: APK not found at $APK_PATH" -ForegroundColor Red
    exit 1
}

# ==========================================
# [5/6] INSTALL APK
# ==========================================

Write-Host ""
Write-Host "[5/6] Installing APK onto $DEVICE_ID..." -ForegroundColor Yellow

& $ADB -s $DEVICE_ID install -r $APK_PATH

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: APK installation failed." -ForegroundColor Red
    exit 1
}

Write-Host "APK installed successfully." -ForegroundColor Green

# ==========================================
# [6/6] LAUNCH APP & START LOGCAT
# ==========================================

Write-Host ""
Write-Host "[6/6] Starting Application and Logcat..." -ForegroundColor Yellow

& $ADB -s $DEVICE_ID shell am start -n "$APP_PACKAGE/.SplashActivity"

if ($LASTEXITCODE -ne 0) {
    Write-Host "WARNING: Could not start SplashActivity via am start" -ForegroundColor Yellow
} else {
    Write-Host "Android application started." -ForegroundColor Green
}

# ==========================================
# START LIVE LOGCAT WITH AUTO IMAGE SYNC
# ==========================================

$LOGCAT_FILTER = @(
    "ATTENDANCE_STEP:D",
    "COORD_LOG:D",
    "DATA_LOG:D",
    "PREP_LOG:D",
    "PAPER_LOG:D",
    "COL_LOG:D",
    "DirectDbManager:D",
    "*:S"
)

$LogcatCommand = @"
`$outputDir = Join-Path (Get-Location) 'output'
if (-not (Test-Path `$outputDir)) { New-Item -ItemType Directory -Path `$outputDir | Out-Null }
Write-Host '=====================================================' -ForegroundColor Cyan
Write-Host ' Live Android Logcat (Zero API / Standalone)' -ForegroundColor Cyan
Write-Host ' Auto-Image Sync is ACTIVE -> output/debug_latest.png' -ForegroundColor Green
Write-Host '=====================================================' -ForegroundColor Cyan
Write-Host ''
& '$ADB' -s '$DEVICE_ID' logcat -s $($LOGCAT_FILTER -join ' ') | ForEach-Object {
    `$line = `$_
    Write-Host `$line
    if (`$line -match 'Saved debug image to:') {
        Write-Host ''
        Write-Host '📥 [AUTO-SYNC] New scan detected! Pulling debug_latest.png ...' -ForegroundColor Cyan
        & '$ADB' -s '$DEVICE_ID' pull /sdcard/Download/SchoolAttendance/output/debug_latest.png `$outputDir\debug_latest.png
        Write-Host '✅ [AUTO-SYNC] Synced ONLY latest image: output/debug_latest.png' -ForegroundColor Green
        Write-Host ''
    }
}
"@

Start-Process powershell.exe `
    -ArgumentList "-NoExit", "-Command", $LogcatCommand `
    -WindowStyle Normal

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host " APPLICATION RUNNING (100% STANDALONE)" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Device      : $DEVICE_ID" -ForegroundColor Cyan
Write-Host "Package     : $APP_PACKAGE" -ForegroundColor Cyan
Write-Host "Database    : PostgreSQL Direct (Port 5432)" -ForegroundColor Cyan
Write-Host "Logcat      : Live window opened with auto-image sync" -ForegroundColor Cyan
Write-Host ""
Write-Host "Ready!" -ForegroundColor Green
Write-Host ""