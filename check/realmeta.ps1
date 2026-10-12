# Run MetaFiles' atomic-write + truncation-salvage checks against REAL files pulled from the phone.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File check\realmeta.ps1 <dir-with-pulled-json>
# Keep this file ASCII-only: Windows PowerShell reads no-BOM files as the ANSI codepage and
# Chinese comments here corrupt the parser mid-string.
$ErrorActionPreference = 'Stop'
$jdk  = 'C:\Users\EDY\.jdks\corretto-17.0.20.1'
# org.json comes from the Gradle cache -- the very jar app/build.gradle declares as
# testImplementation. The old hardcoded path pointed into a third-party extension's
# private lib (vmware.vscode-spring-boot); that breaks the moment the extension is
# upgraded or uninstalled. Glob the cache instead, and skip -sources jars.
$json = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.json\json" -Recurse -Filter 'json-*.jar' |
    Where-Object { $_.Name -notlike '*-sources.jar' } |
    Select-Object -First 1
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dir  = $args[0]
if (-not $dir) { Write-Host 'usage: realmeta.ps1 <dir>  (dir must contain library.json / libraries.json)'; exit 2 }
if (-not (Test-Path -PathType Container $dir)) { Write-Host "not a directory: $dir"; exit 2 }
if (-not $json -or -not (Test-Path $json.FullName)) {
    Write-Host 'reference org.json jar not found in the Gradle cache'
    Write-Host 'run gradle testDebugUnitTest once online (it fetches org.json), then retry'
    exit 2
}
$json = $json.FullName
if (-not (Test-Path $jdk))  { Write-Host "JDK17 not found: $jdk"; exit 2 }
$out = Join-Path $root 'work\realmeta'
New-Item -ItemType Directory -Force $out | Out-Null
Write-Host '== compile =='
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -cp $json -d $out (Join-Path $root 'RealMetaTest.java')
if ($LASTEXITCODE -ne 0) { exit 1 }
Write-Host '== run =='
& (Join-Path $jdk 'bin\java.exe') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$out;$json" RealMetaTest $dir
exit $LASTEXITCODE
