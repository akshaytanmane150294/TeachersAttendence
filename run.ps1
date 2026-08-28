# ==========================================
# School Attendance - Development Runner
# ==========================================

$ErrorActionPreference = "Stop"

# ------------------------------------------
# Configuration
# ------------------------------------------

$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

$APP_PACKAGE = "com.school.attendance"

$SERVER_PORT = 8000

$APK_PATH = "app\build\outputs\apk\debug\app-debug.apk"

$SERVER_URL = "http://127.0.0.1:$SERVER_PORT"

$HEALTH_URL = "$SERVER_URL/schools"


# ==========================================
# START
# ==========================================

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " School Attendance Development Runner" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""


# ==========================================
# [1/10] CHECK ADB
# ==========================================

Write-Host "[1/10] Checking ADB..." -ForegroundColor Yellow

if (-not (Test-Path $ADB)) {

    Write-Host ""
    Write-Host "ERROR: ADB not found:" -ForegroundColor Red
    Write-Host $ADB
    Write-Host ""

    exit 1
}

Write-Host "ADB found." -ForegroundColor Green


# ==========================================
# GET CONNECTED DEVICES
# ==========================================

Write-Host ""
Write-Host "Checking connected Android devices..." -ForegroundColor Yellow

$ADBOutput = & $ADB devices

$DeviceLines = @(
    $ADBOutput | Where-Object {
        $_ -match "^\S+\s+device$"
    }
)


# ==========================================
# NO DEVICE
# ==========================================

if ($DeviceLines.Count -eq 0) {

    Write-Host ""
    Write-Host "ERROR: No Android device/emulator detected." -ForegroundColor Red
    Write-Host ""

    Write-Host "Run:" -ForegroundColor Yellow
    Write-Host "adb devices"

    Write-Host ""

    exit 1
}


# ==========================================
# EXTRACT DEVICE IDS
# ==========================================

$DeviceIDs = @()

foreach ($line in $DeviceLines) {

    $deviceId = ($line -split "\s+")[0]

    if ($deviceId) {

        $DeviceIDs += $deviceId
    }
}


# ==========================================
# SELECT DEVICE
# ==========================================

Write-Host ""
Write-Host "Connected Android devices:" -ForegroundColor Green

for ($i = 0; $i -lt $DeviceIDs.Count; $i++) {

    Write-Host "[$($i + 1)] $($DeviceIDs[$i])"
}


# ==========================================
# AUTOMATIC DEVICE SELECTION
# ==========================================

$DEVICE_ID = $DeviceIDs[0]

Write-Host ""
Write-Host "Selected device:" -ForegroundColor Green
Write-Host $DEVICE_ID -ForegroundColor Cyan


# ==========================================
# [2/10] ADB REVERSE
# ==========================================

Write-Host ""
Write-Host "[2/10] Setting ADB reverse for port $SERVER_PORT..." -ForegroundColor Yellow

& $ADB -s $DEVICE_ID reverse tcp:$SERVER_PORT tcp:$SERVER_PORT

if ($LASTEXITCODE -ne 0) {

    Write-Host ""
    Write-Host "ERROR: ADB reverse failed." -ForegroundColor Red
    Write-Host ""

    exit 1
}

Write-Host "ADB reverse configured successfully." -ForegroundColor Green

Write-Host ""
Write-Host "ADB reverse configuration:" -ForegroundColor Green

& $ADB -s $DEVICE_ID reverse --list


# ==========================================
# SERVER PATH
# ==========================================

$ServerPath = Join-Path (Get-Location) "server"

if (-not (Test-Path $ServerPath)) {

    Write-Host ""
    Write-Host "ERROR: server folder not found." -ForegroundColor Red

    Write-Host ""
    Write-Host "Expected:" -ForegroundColor Yellow
    Write-Host $ServerPath

    Write-Host ""

    exit 1
}


# ==========================================
# [3/10] CHECK EXISTING FASTAPI
# ==========================================

Write-Host ""
Write-Host "[3/10] Checking existing FastAPI server..." -ForegroundColor Yellow

$ServerRunning = $false


# ------------------------------------------
# HTTP CHECK
# ------------------------------------------

try {

    $Response = Invoke-WebRequest `
        -Uri $HEALTH_URL `
        -UseBasicParsing `
        -TimeoutSec 2 `
        -ErrorAction Stop

    if ($Response.StatusCode -ge 200 -and $Response.StatusCode -lt 500) {

        $ServerRunning = $true
    }

}
catch {

    $ServerRunning = $false
}


# ------------------------------------------
# PORT CHECK
# ------------------------------------------

if (-not $ServerRunning) {

    $PortCheck = Get-NetTCPConnection `
        -LocalPort $SERVER_PORT `
        -State Listen `
        -ErrorAction SilentlyContinue

    if ($PortCheck) {

        $ServerRunning = $true
    }
}


# ==========================================
# START FASTAPI IF NEEDED
# ==========================================

if ($ServerRunning) {

    Write-Host ""
    Write-Host "FastAPI is already running." -ForegroundColor Green

    Write-Host "URL: $SERVER_URL" -ForegroundColor Cyan

}
else {

    Write-Host ""
    Write-Host "FastAPI is not running." -ForegroundColor Yellow

    Write-Host "Starting FastAPI..." -ForegroundColor Yellow


    $UvicornCommand = @"
Set-Location '$ServerPath'

python -m uvicorn main:app --host 0.0.0.0 --port $SERVER_PORT --reload
"@


    Start-Process powershell.exe `
        -ArgumentList "-NoExit", "-Command", $UvicornCommand `
        -WindowStyle Normal


    Write-Host ""
    Write-Host "FastAPI process started." -ForegroundColor Green
}


# ==========================================
# [4/10] WAIT FOR FASTAPI
# ==========================================

Write-Host ""
Write-Host "[4/10] Waiting for FastAPI..." -ForegroundColor Yellow

$ServerReady = $false


for ($i = 1; $i -le 30; $i++) {

    Start-Sleep -Seconds 1


    # --------------------------------------
    # PORT CHECK
    # --------------------------------------

    $PortReady = Get-NetTCPConnection `
        -LocalPort $SERVER_PORT `
        -State Listen `
        -ErrorAction SilentlyContinue


    if ($PortReady) {

        try {

            $Response = Invoke-WebRequest `
                -Uri $HEALTH_URL `
                -UseBasicParsing `
                -TimeoutSec 2 `
                -ErrorAction Stop


            if ($Response.StatusCode -ge 200 -and $Response.StatusCode -lt 500) {

                $ServerReady = $true

                break
            }

        }
        catch {

            # API is still starting
        }
    }


    Write-Host "." -NoNewline
}


Write-Host ""


# ==========================================
# SERVER NOT READY
# ==========================================

if (-not $ServerReady) {

    Write-Host ""
    Write-Host "ERROR: FastAPI did not become ready." -ForegroundColor Red

    Write-Host ""
    Write-Host "Check the FastAPI PowerShell window." -ForegroundColor Yellow

    exit 1
}


Write-Host ""
Write-Host "FastAPI is ready." -ForegroundColor Green

Write-Host "URL: $SERVER_URL" -ForegroundColor Cyan


# ==========================================
# [5/10] SERVER HEALTH CHECK
# ==========================================

Write-Host ""
Write-Host "[5/10] Checking server health..." -ForegroundColor Yellow


try {

    $HealthResponse = Invoke-WebRequest `
        -Uri $HEALTH_URL `
        -UseBasicParsing `
        -TimeoutSec 5 `
        -ErrorAction Stop


    Write-Host ""
    Write-Host "Server status: $($HealthResponse.StatusCode)" -ForegroundColor Green

}
catch {

    Write-Host ""
    Write-Host "WARNING: /schools endpoint returned an error." -ForegroundColor Yellow

    Write-Host "Server is listening on port $SERVER_PORT." -ForegroundColor Yellow
}


# ==========================================
# [6/10] CHECK PORT
# ==========================================

Write-Host ""
Write-Host "[6/10] Checking port $SERVER_PORT..." -ForegroundColor Yellow


$PortInfo = Get-NetTCPConnection `
    -LocalPort $SERVER_PORT `
    -State Listen `
    -ErrorAction SilentlyContinue


if ($PortInfo) {

    Write-Host ""
    Write-Host "Port $SERVER_PORT is active." -ForegroundColor Green

    $PortInfo | Format-Table -AutoSize

}
else {

    Write-Host ""
    Write-Host "ERROR: Port $SERVER_PORT is not listening." -ForegroundColor Red

    exit 1
}


# ==========================================
# [7/10] STOP EXISTING ANDROID APP
# ==========================================

Write-Host ""
Write-Host "[7/10] Stopping existing Android app..." -ForegroundColor Yellow


& $ADB -s $DEVICE_ID shell am force-stop $APP_PACKAGE


if ($LASTEXITCODE -eq 0) {

    Write-Host "Existing Android app stopped." -ForegroundColor Green
}


# ==========================================
# [8/10] BUILD APK
# ==========================================

Write-Host ""
Write-Host "[8/10] Building Android APK..." -ForegroundColor Yellow


cmd.exe /c "gradlew.bat assembleDebug"


if ($LASTEXITCODE -ne 0) {

    Write-Host ""
    Write-Host "ERROR: Gradle build failed." -ForegroundColor Red

    exit 1
}


Write-Host ""
Write-Host "Gradle build successful." -ForegroundColor Green


# ==========================================
# CHECK APK
# ==========================================

if (-not (Test-Path $APK_PATH)) {

    Write-Host ""
    Write-Host "ERROR: APK not found:" -ForegroundColor Red

    Write-Host $APK_PATH

    exit 1
}


Write-Host ""
Write-Host "APK found:" -ForegroundColor Green
Write-Host $APK_PATH


# ==========================================
# INSTALL APK
# ==========================================

Write-Host ""
Write-Host "Installing APK..." -ForegroundColor Yellow


& $ADB -s $DEVICE_ID install -r $APK_PATH


if ($LASTEXITCODE -ne 0) {

    Write-Host ""
    Write-Host "ERROR: APK installation failed." -ForegroundColor Red

    exit 1
}


Write-Host ""
Write-Host "APK installed successfully." -ForegroundColor Green


# ==========================================
# [9/10] START ANDROID APP
# ==========================================

Write-Host ""
Write-Host "[9/10] Starting Android application..." -ForegroundColor Yellow


& $ADB -s $DEVICE_ID shell am start -n "$APP_PACKAGE/.SplashActivity"


if ($LASTEXITCODE -ne 0) {

    Write-Host ""
    Write-Host "ERROR: Could not start Android application." -ForegroundColor Red

    exit 1
}


Write-Host ""
Write-Host "Android application started." -ForegroundColor Green


# ==========================================
# [10/10] START LOGCAT
# ==========================================

Write-Host ""
Write-Host "[10/10] Starting Android Logcat..." -ForegroundColor Yellow


$LOGCAT_FILTER = @(
    "ATTENDANCE_STEP:D",
    "COORD_LOG:D",
    "DATA_LOG:D",
    "PREP_LOG:D",
    "PAPER_LOG:D",
    "COL_LOG:D",
    "*:S"
)


$LogcatCommand = @"
& '$ADB' -s '$DEVICE_ID' logcat -s $($LOGCAT_FILTER -join ' ')
"@


Start-Process powershell.exe `
    -ArgumentList "-NoExit", "-Command", $LogcatCommand `
    -WindowStyle Normal


Write-Host ""
Write-Host "Logcat started in separate PowerShell window." -ForegroundColor Green


# ==========================================
# DONE
# ==========================================

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host " DONE!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""

Write-Host "Device  : $DEVICE_ID" -ForegroundColor Cyan
Write-Host "FastAPI : $SERVER_URL" -ForegroundColor Cyan
Write-Host "Swagger : $SERVER_URL/docs" -ForegroundColor Cyan
Write-Host "Health  : $HEALTH_URL" -ForegroundColor Cyan
Write-Host "Android : $APP_PACKAGE" -ForegroundColor Cyan

Write-Host ""

Write-Host "ADB Reverse:" -ForegroundColor Cyan
Write-Host "  tcp:$SERVER_PORT -> tcp:$SERVER_PORT"

Write-Host ""

Write-Host "Logcat filters:" -ForegroundColor Cyan
Write-Host "  ATTENDANCE_STEP"
Write-Host "  COORD_LOG"
Write-Host "  DATA_LOG"
Write-Host "  PREP_LOG"
Write-Host "  PAPER_LOG"
Write-Host "  COL_LOG"

Write-Host ""

Write-Host "Development runner completed successfully." -ForegroundColor Green

Write-Host ""