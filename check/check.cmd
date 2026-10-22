@echo off
call "%~dp0gradlecheck.cmd"
if errorlevel 1 exit /b 1
echo == Java type check PASSED ==
