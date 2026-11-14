# Vade 客户端真链路端到端（PowerShell 版，逻辑与 tools/e2e-run.sh 一致）。
#
# 用法：
#   powershell -NoProfile -File tools/e2e-run.ps1
#
# 退出码：gradle 测试的退出码原样透出（0 = 全过，非 0 = 红）。
param()

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
$Gradle = if ($env:GRADLE) { $env:GRADLE } else { "D:/Hermes/tools/gradle-9.6.1/bin/gradle.bat" }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "D:/Hermes/tools/jdk21" }
if (-not $env:VADE_PYTHON) { $env:VADE_PYTHON = "D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe" }
if (-not $env:VADE_CORE_PKG_DIR) { $env:VADE_CORE_PKG_DIR = "D:/HermesWorkspace/Vade/Vade/core" }
if (-not $env:VADE_PROTOCOL_PY_DIR) { $env:VADE_PROTOCOL_PY_DIR = "D:/HermesWorkspace/Vade/Vade/protocol/core/python" }

Write-Host "== e2e-run: RealCoreE2ETest (real python core via ProcessBuilder)"
Write-Host "   VADE_PYTHON=$env:VADE_PYTHON"

Push-Location $Root
try {
    & $Gradle :composeApp:desktopTest --tests "vade.ui.e2e.RealCoreE2ETest" --console=plain
    $rc = $LASTEXITCODE
} finally {
    Pop-Location
}

# 收尾：只杀本 e2e 拉起的孤儿（按命令行里的 launcher 文件名精确匹配）
$orphans = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -like '*e2e_core_launcher.py*' }
if ($orphans) {
    Write-Host "WARN: orphan core launcher(s) detected, killing: $($orphans.ProcessId)"
    foreach ($o in $orphans) { Stop-Process -Id $o.ProcessId -Force -ErrorAction SilentlyContinue }
} else {
    Write-Host "cleanup: no orphan core launcher processes"
}

if ($rc -eq 0) { Write-Host "E2E RESULT: PASS (exit=0)" } else { Write-Host "E2E RESULT: FAIL (exit=$rc)" }
exit $rc
