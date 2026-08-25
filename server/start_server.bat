@echo off
title Attendance Recognition API Server
echo ========================================================
echo Starting Attendance Recognition Python API on port 8000
echo ========================================================

:: Check for connected ADB device and set reverse port forwarding
where adb >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo Setting up USB port forwarding (adb reverse tcp:8000 tcp:8000)...
    adb reverse tcp:8000 tcp:8000
) else (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        echo Setting up USB port forwarding via Android SDK adb...
        "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" reverse tcp:8000 tcp:8000
    )
)

echo.
echo Server running! You can scan attendance from the Android app now.
echo API Docs: http://localhost:8000/docs
echo ========================================================
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
pause
