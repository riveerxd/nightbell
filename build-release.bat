@echo off
rem Build the SIGNED RELEASE APK of the Chinese Nightbell build.
rem JDK / Android SDK / Gradle cache all live on D:.
rem IMPORTANT: keep this file ASCII-only and CRLF. cmd.exe mis-parses UTF-8
rem comments and LF-only batch files, which silently breaks "cd" and every path.
chcp 65001 >nul
setlocal
set "ANDROID_HOME=D:\Android\Sdk"
set "ANDROID_SDK_ROOT=D:\Android\Sdk"
set "GRADLE_USER_HOME=D:\Android\gradle-home"
rem Prefer the standalone JDK 21. Gradle 8.13 cannot run on Studio's JBR 25.
set "JAVA_HOME=D:\Android\jdk-21"
if exist "%JAVA_HOME%\bin\java.exe" goto :jdk_ok
if exist "D:\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=D:\Android\Android Studio\jbr"
:jdk_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"
if not exist "tools\gen_zh.py" (
  echo [ERROR] Project root not found at %~dp0
  exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK not found. Tried D:\Android\jdk-21 and the Studio JBR.
  exit /b 1
)
if not exist "%ANDROID_HOME%\platforms\android-36" (
  echo [ERROR] Android SDK platform 36 not found at %ANDROID_HOME%
  exit /b 1
)
if not exist "keystore\keystore.properties" (
  echo [ERROR] keystore\keystore.properties is missing.
  echo Without it Gradle produces an UNSIGNED release APK that cannot install.
  exit /b 1
)

echo [1/3] Generating Zh.kt from tools\zh_*.tsv ...
rem The WindowsApps python.exe is a Microsoft Store stub: it exits 9009 and
rem prints nothing. Pick a real interpreter instead.
set "PYEXE="
if defined NIGHTBELL_PYTHON if exist "%NIGHTBELL_PYTHON%" set "PYEXE=%NIGHTBELL_PYTHON%"
if not defined PYEXE (
  for /d %%D in ("%USERPROFILE%\.workbuddy\binaries\python\versions\*") do (
    if exist "%%D\python.exe" set "PYEXE=%%D\python.exe"
  )
)
if not defined PYEXE (
  for /f "delims=" %%P in ('where python 2^>nul') do (
    if not defined PYEXE (
      "%%~P" -c "import sys" >nul 2>nul
      if not errorlevel 1 set "PYEXE=%%~P"
    )
  )
)
if not defined PYEXE (
  echo [ERROR] No usable Python 3 found.
  echo Set NIGHTBELL_PYTHON to the full path of a real python.exe and retry.
  exit /b 1
)
echo       Python: %PYEXE%
"%PYEXE%" tools\gen_zh.py
if errorlevel 1 exit /b 1

echo [2/3] Building SIGNED RELEASE APK (R8 minify + resource shrink) ...
call gradlew.bat :app:assembleRelease --console=plain
if errorlevel 1 (
  echo [ERROR] Build failed.
  exit /b 1
)

echo [3/3] Collecting APK ...
if not exist "app\build\outputs\apk\release\app-release.apk" (
  echo [ERROR] Gradle reported success but app-release.apk is missing.
  echo Check whether the release signing config was applied.
  exit /b 1
)
if not exist "dist" mkdir dist
copy /y "app\build\outputs\apk\release\app-release.apk" "dist\Nightbell-zh-release.apk" >nul
echo.
echo Done: %~dp0dist\Nightbell-zh-release.apk
echo NOTE: the release package is me.river.nightbell (no .debug suffix).
echo       It is signed with keystore\nightbell-zh-release.jks. Keep that file:
echo       every future update must be signed with the same key.
endlocal
