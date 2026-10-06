@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
set "SDK_ROOT=D:\tools\android-sdk"
set "ADB=%SDK_ROOT%\platform-tools\adb.exe"
set "EMULATOR=%SDK_ROOT%\emulator\emulator.exe"
set "AVD=northward_api36"
set "GRADLE_USER_HOME=D:\tools\gradle-user-home"
set "ANDROID_HOME=%SDK_ROOT%"
set "ANDROID_SDK_ROOT=%SDK_ROOT%"

echo ========================================
echo   Nightingale Player debug launcher
echo ========================================
if not exist "%ADB%" goto missing
if not exist "%EMULATOR%" goto missing
call :find_device
if not defined SERIAL (
  echo Starting emulator %AVD% ...
  start "Nightingale Player Emulator" /min "%EMULATOR%" -avd "%AVD%" -no-snapshot -no-boot-anim -gpu auto
  for /l %%N in (1,1,60) do (
    call :find_device
    if defined SERIAL goto boot
    timeout /t 2 /nobreak >nul
  )
  echo Emulator connection timed out.
  goto fail
)
:boot
for /l %%N in (1,1,90) do (
  "%ADB%" -s "!SERIAL!" shell getprop sys.boot_completed 2>nul | findstr /r /c:"^1$" >nul && goto build
  timeout /t 2 /nobreak >nul
)
echo Android boot timed out.
goto fail
:build
call "%~dp0gradlew.bat" --console=plain assembleDebug
if errorlevel 1 goto fail
"%ADB%" -s "!SERIAL!" install -r "%~dp0app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 goto fail
"%ADB%" -s "!SERIAL!" shell am force-stop com.myp.sleepplayer
"%ADB%" -s "!SERIAL!" shell monkey -p com.myp.sleepplayer 1
echo.
echo App started on !SERIAL!.
echo Put videos in the shared folder and choose it in the app: /sdcard/Movies/SleepVideoPlayer
pause
exit /b 0
:find_device
set "SERIAL="
for /f "tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  echo %%A | findstr /b "emulator-" >nul && if "%%B"=="device" set "SERIAL=%%A"
)
exit /b 0
:missing
echo Android SDK tools were not found under %SDK_ROOT%.
:fail
pause
exit /b 1
