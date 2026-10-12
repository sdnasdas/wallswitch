@echo off
rem Pure-JVM harness for the backup zip round-trip (check/BackupZipRoundtripTest.java).
rem Usage: check\backupzip.cmd ^<library.json^> ^<libraries.json^>
rem   (pull both files from the phone first; the prefs-parse half of the old
rem    BackupRoundtripTest now lives in app/src/test as BackupPrefsParseTest)
rem org.json comes from the Gradle cache -- the very jar app/build.gradle declares
rem as testImplementation, so there is no third-party-extension private lib here.
rem Keep this file ASCII-only: the console codepage is GBK.
set JAVAC=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\javac.exe
set JAVA=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\java.exe
set JSONJAR=
for /f "delims=" %%J in ('dir /s /b "%USERPROFILE%\.gradle\caches\modules-2\files-2.1\org.json\json\json-*.jar" 2^>nul ^| findstr /v /c:"-sources"') do set "JSONJAR=%%J"
if not defined JSONJAR (
    echo [backupzip] org.json jar not found in the Gradle cache
    echo [backupzip] run gradle testDebugUnitTest once online ^(it fetches org.json^), then retry
    exit /b 1
)
if not exist "%~dp0work\backupzip" mkdir "%~dp0work\backupzip"
"%JAVAC%" -encoding UTF-8 -cp "%JSONJAR%" -d "%~dp0work\backupzip" "%~dp0BackupZipRoundtripTest.java"
if errorlevel 1 exit /b 1
"%JAVA%" -Dfile.encoding=UTF-8 -cp "%~dp0work\backupzip;%JSONJAR%" BackupZipRoundtripTest %1 %2
if errorlevel 1 exit /b 1
