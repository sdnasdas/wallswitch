# 本地 Java 类型检查：JDK17 + 本机 android.jar + androidx 桩类型（无需 Gradle/完整 SDK）
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File check\javacheck.ps1
$ErrorActionPreference = 'Continue'
$javac = 'C:\Users\EDY\.jdks\corretto-17.0.20.1\bin\javac.exe'
$androidJar = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platforms\android-34\android.jar'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $root '..\app\src\main\java\com\example\wallswitch'
$stubs = Join-Path $root 'stubs'
$work = Join-Path $root 'work'
New-Item -ItemType Directory -Force (Join-Path $work 'stubs\com\example\wallswitch') | Out-Null

if (-not (Test-Path $javac)) {
    Write-Host "未找到 javac: $javac" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $androidJar)) {
    Write-Host "未找到 android.jar: $androidJar" -ForegroundColor Red
    exit 1
}

# 1) 从源码收集 R.* 引用，生成 R 桩（真实 R 由构建工具生成，本地用桩代替）
$types = 'id', 'string', 'layout', 'style', 'drawable', 'array'
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('package com.example.wallswitch;')
[void]$sb.AppendLine('public final class R {')
$counter = 0
foreach ($type in $types) {
    $names = Select-String -Path (Join-Path $src '*.java') -Pattern "\bR\.$type\.(\w+)" -AllMatches |
        ForEach-Object { $_.Matches } |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique
    if (-not $names) { continue }
    [void]$sb.AppendLine("  public static final class $type {")
    foreach ($name in $names) {
        $counter++
        [void]$sb.AppendLine("    public static final int $name = $counter;")
    }
    [void]$sb.AppendLine('  }')
}
[void]$sb.AppendLine('}')
Set-Content -Path (Join-Path $work 'stubs\com\example\wallswitch\R.java') -Value $sb.ToString() -Encoding ASCII

# 2) javac 类型检查（sourcepath 挂上桩目录，androidx 引用由桩解析）
#    用参数数组传递，避免 PowerShell 5.1 对 native 参数的解析问题
$files = @(Get-ChildItem $src -Filter '*.java' | ForEach-Object { $_.FullName })
$javacArgs = @(
    '-J-Duser.language=en',
    '-J-Duser.country=US',
    '-proc:none',
    '-encoding', 'UTF-8',
    '-nowarn',
    '-d', (Join-Path $work 'out'),
    '-cp', $androidJar,
    '-sourcepath', "$src;$(Join-Path $work 'stubs');$stubs"
) + $files
& $javac $javacArgs
if ($LASTEXITCODE -eq 0) {
    Write-Host '== Java type check PASSED =='
    exit 0
} else {
    Write-Host '== Java type check FAILED =='
    exit 1
}