@echo off
setlocal
set "ROOT=%~dp0"
set "JAVA_HOME=D:\env\jdk-17"
set "ANDROID_HOME=D:\env\Android"
set "ANDROID_SDK_ROOT=D:\env\Android"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "D:\tools\gradle-8.7\bin\gradle.bat" (
  echo [ERROR] Gradle not found: D:\tools\gradle-8.7\bin\gradle.bat
  exit /b 1
)

pushd "%ROOT%"
echo [1/2] Packing audio/config/lines into app assets...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%pack_assets.ps1"
if not "%ERRORLEVEL%"=="0" (
  echo [ERROR] pack_assets.ps1 failed.
  popd
  exit /b %ERRORLEVEL%
)
echo [2/2] Building debug APK...
call D:\tools\gradle-8.7\bin\gradle.bat assembleDebug
set "RET=%ERRORLEVEL%"
popd

if not "%RET%"=="0" exit /b %RET%
echo.
echo APK path:
echo %ROOT%app\build\outputs\apk\debug\app-debug.apk
exit /b 0
