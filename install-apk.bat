@echo off
setlocal
cd /d "%~dp0"

set ADB=%~dp0.android-sdk\platform-tools\adb.exe
set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk

if not exist "%ADB%" (
    echo adb not found at .android-sdk\platform-tools\adb.exe
    echo Install the APK manually from: %APK%
    exit /b 1
)

if not exist "%APK%" (
    echo APK not built yet. Run: gradlew.bat assembleDebug
    exit /b 1
)

echo Installing %APK%
"%ADB%" install -r -d "%APK%"
if errorlevel 1 (
    echo.
    echo Install failed. If signatures do not match, uninstall Gkeys once, then run this again.
    exit /b 1
)

echo.
echo Installed. Open Settings and pick Gkeys in your keyboard list if needed.
exit /b 0
