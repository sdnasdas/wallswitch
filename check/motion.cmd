@echo off
rem Pure-JVM harness for motion-photo byte-range parsing (check/MotionParseTest.java).
rem Unlike croprect.cmd this compiles the PRODUCTION source too: MotionSource.java is
rem android-free on purpose, so the assertions run against the real code, not a copy.
set JAVAC=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\javac.exe
set JAVA=C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\java.exe
if not exist "%~dp0work\motion" mkdir "%~dp0work\motion"
"%JAVAC%" -encoding UTF-8 -d "%~dp0work\motion" "%~dp0MotionParseTest.java" "%~dp0..\app\src\main\java\com\example\wallswitch\gl\MotionSource.java"
if errorlevel 1 exit /b 1
"%JAVA%" -cp "%~dp0work\motion" com.example.wallswitch.gl.MotionParseTest
if errorlevel 1 exit /b 1
