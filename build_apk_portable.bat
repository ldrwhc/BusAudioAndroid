@echo off
setlocal
chcp 65001>nul

set "ROOT=%~dp0"
for %%I in ("%ROOT%..") do set "ASSET_ROOT=%%~fI"

echo [INFO] 工程目录: %ROOT%
echo [INFO] 资源目录: %ASSET_ROOT%

if not exist "%ASSET_ROOT%\00concat" (
  echo [ERROR] 缺少目录: %ASSET_ROOT%\00concat
  exit /b 1
)
if not exist "%ASSET_ROOT%\template" (
  echo [ERROR] 缺少目录: %ASSET_ROOT%\template
  exit /b 1
)
if not exist "%ASSET_ROOT%\00config" (
  echo [ERROR] 缺少目录: %ASSET_ROOT%\00config
  exit /b 1
)
if not exist "%ASSET_ROOT%\00lines" (
  echo [ERROR] 缺少目录: %ASSET_ROOT%\00lines
  exit /b 1
)

if not exist "%ASSET_ROOT%\00concatEng" (
  echo [WARN] 未找到 %ASSET_ROOT%\00concatEng，将按无英文资源继续打包
)

set "GRADLE_BIN=%GRADLE_BIN%"
if "%GRADLE_BIN%"=="" set "GRADLE_BIN=D:\tools\gradle-8.7\bin\gradle.bat"

if not exist "%GRADLE_BIN%" (
  where gradle >nul 2>nul
  if "%ERRORLEVEL%"=="0" (
    set "GRADLE_BIN=gradle"
  ) else (
    echo [ERROR] 未找到 Gradle。请设置环境变量 GRADLE_BIN 指向 gradle.bat
    exit /b 1
  )
)

pushd "%ROOT%"
echo [1/2] 打包资源为加密 payload...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%pack_assets.ps1" -BbDir "%ROOT%" -PackMode split
if not "%ERRORLEVEL%"=="0" (
  echo [ERROR] pack_assets.ps1 执行失败
  popd
  exit /b %ERRORLEVEL%
)

echo [2/2] 构建 APK...
call "%GRADLE_BIN%" clean assembleDebug
set "RET=%ERRORLEVEL%"
popd

if not "%RET%"=="0" exit /b %RET%
echo.
echo [DONE] APK 输出:
echo %ROOT%app\build\outputs\apk\debug\app-debug.apk
exit /b 0

