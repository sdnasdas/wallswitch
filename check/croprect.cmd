@echo off
rem Pure-JVM harness for the crop-rect round-trip (check/CropRectTest.java).
rem Uses the same JDK17 as javacheck.ps1; the machine's default java is 1.8.
set JAVAC=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\javac.exe
set JAVA=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\java.exe
if not exist "%~dp0work\croprect" mkdir "%~dp0work\croprect"
"%JAVAC%" -encoding UTF-8 -d "%~dp0work\croprect" "%~dp0CropRectTest.java"
if errorlevel 1 exit /b 1
"%JAVA%" -cp "%~dp0work\croprect" CropRectTest
if errorlevel 1 exit /b 1
