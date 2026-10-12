@echo off
rem Pure-JVM harness for the restore simulation (check/RestoreSimTest.java).
rem Replays BackupStore.restore() on the JVM against a real exported backup zip and
rem the pre-restore metadata snapshot, then diffs what restore actually changed.
rem Usage: check\restoresim.cmd ^<backup.zip^> ^<snapshot-dir^>
rem org.json comes from the Gradle cache -- the very jar app/build.gradle declares
rem as testImplementation, so there is no third-party-extension private lib here.
rem Keep this file ASCII-only: the console codepage is GBK.
set JAVAC=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\javac.exe
set JAVA=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\java.exe
set JSONJAR=
for /f "delims=" %%J in ('dir /s /b "%USERPROFILE%\.gradle\caches\modules-2\files-2.1\org.json\json\json-*.jar" 2^>nul ^| findstr /v /c:"-sources"') do set "JSONJAR=%%J"
if not defined JSONJAR (
    echo [restoresim] org.json jar not found in the Gradle cache
    echo [restoresim] run gradle testDebugUnitTest once online ^(it fetches org.json^), then retry
    exit /b 1
)
if not exist "%~dp0work\restoresim" mkdir "%~dp0work\restoresim"
"%JAVAC%" -encoding UTF-8 -cp "%JSONJAR%" -d "%~dp0work\restoresim" "%~dp0RestoreSimTest.java"
if errorlevel 1 exit /b 1
"%JAVA%" -Dfile.encoding=UTF-8 -cp "%~dp0work\restoresim;%JSONJAR%" RestoreSimTest %1 %2
if errorlevel 1 exit /b 1
