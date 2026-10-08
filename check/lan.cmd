@echo off
rem Pure-JVM harness for the LAN sync helpers (check/LanSyncTest.java).
rem Compiles the PRODUCTION sources too: LanPackager / LanPair / LanClient / LanHttp /
rem ScanTransform are android-free on purpose, so assertions run against real code
rem instead of a hand-copied formula (same reason check/motion.cmd compiles MotionSource).
rem Do NOT add -sourcepath here: BackupStore and MainActivity import android types and
rem would drag the whole app tree into a plain javac run.
rem Keep this file ASCII-only: cmd reads no-BOM files in the ANSI codepage, Chinese here
rem corrupts the parser mid-line (see check/realmeta.ps1 header note).
set JAVAC=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\javac.exe
set JAVA=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\java.exe
set SRC=%~dp0..\app\src\main\java\com\example\wallswitch
if not exist "%~dp0work\lan" mkdir "%~dp0work\lan"
"%JAVAC%" -encoding UTF-8 -d "%~dp0work\lan" ^
  "%~dp0LanSyncTest.java" ^
  "%SRC%\LanPackager.java" "%SRC%\LanPair.java" ^
  "%SRC%\LanClient.java" "%SRC%\LanHttp.java" "%SRC%\ScanTransform.java"
if errorlevel 1 exit /b 1
"%JAVA%" -cp "%~dp0work\lan" com.example.wallswitch.LanSyncTest
if errorlevel 1 exit /b 1
