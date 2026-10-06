@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
set "ADB=D:\tools\android-sdk\platform-tools\adb.exe"
set "SERIAL="
if not exist "%ADB%" goto fail
for /f "tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  echo %%A | findstr /b "emulator-" >nul && if "%%B"=="device" set "SERIAL=%%A"
)
if not defined SERIAL (
  echo No running emulator found. Run run_debug.bat first.
  goto fail
)
if not exist "%~dp0app\build\outputs\apk\debug\app-debug.apk" (
  echo APK not found. Run run_debug.bat first.
  goto fail
)
"%ADB%" -s "!SERIAL!" install -r "%~dp0app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 goto fail
"%ADB%" -s "!SERIAL!" shell am force-stop com.myp.sleepplayer
"%ADB%" -s "!SERIAL!" shell monkey -p com.myp.sleepplayer 1
echo App installed and started.
pause
exit /b 0
:fail
pause
exit /b 1
