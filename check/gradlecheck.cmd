@echo off
rem ============================================================================
rem Gradle layered acceptance (daily layer, takes over check.cmd's quick-screen role).
rem   Real dependencies + real R (AAPT2 generated from XML, catches "id removed from
rem   XML but still referenced in code") + compileSdk 35 + warnings visible.
rem   Strictly broader coverage than the hand-written stub route, and with a warm
rem   daemon the incremental run (~2s) is also faster than check.cmd.
rem
rem Four pinned things, do not remove any (read before editing):
rem   1) JAVA_HOME pinned to corretto-17.0.20.1 -- bare runs default to java 1.8,
rem      Gradle refuses to start
rem   2) Gradle pinned to the 8.7 distribution -- the wrapper is 9.3.x; mismatch
rem      with CI's 8.7 means "green locally, red in CI"
rem   3) --offline -- no network; a failure signal is a compile error, not a
rem      download timeout
rem   4) NEVER add --no-daemon -- warm daemon incremental ~2s; cold start 28s,
rem      full rebuild 49s
 rem
rem Keep this file ASCII-only: the console codepage is GBK and non-ASCII bytes
 rem misparse into stray commands.
 rem
rem Usage: check\gradlecheck.cmd
rem Unit tests: change compileDebugJavaWithJavac below to testDebugUnitTest
rem   (fixtures now live in app/src/test; the first run needs network to fetch
rem   junit/org.json, after that --offline works)
rem ============================================================================
setlocal
set "JAVA_HOME=C:\Users\EDY\.jdks\corretto-17.0.20.1"
set "GRADLE=C:\Users\EDY\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat"
if not exist "%GRADLE%" (
    echo [gradlecheck] Gradle 8.7 not found: %GRADLE%
    echo [gradlecheck] Fix the GRADLE path above; do NOT switch to .\gradlew ^(9.3.x, out of sync with CI^)
    exit /b 1
)
pushd "%~dp0.."
call "%GRADLE%" compileDebugJavaWithJavac --offline
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (
    echo [gradlecheck] BUILD FAILED
    exit /b 1
)
echo [gradlecheck] BUILD SUCCESSFUL
