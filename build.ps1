param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$appJar = Join-Path $projectRoot 'app\reconciliation-center.jar'
$pidFile = Join-Path $projectRoot 'logs\server.pid'
if (Test-Path -LiteralPath $pidFile) {
    $serverProcessId = [int](Get-Content -LiteralPath $pidFile)
    $owned = Get-CimInstance Win32_Process -Filter "ProcessId = $serverProcessId"
    if ($owned -and $owned.CommandLine.Contains($appJar)) { throw 'Stop the app with stop.ps1 before rebuilding.' }
}
Get-Command java,node,npm.cmd,mvn.cmd -ErrorAction Stop | Out-Null
Push-Location (Join-Path $projectRoot 'frontend')
try {
    & npm.cmd ci --no-fund --no-audit
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $projectRoot 'backend')
try {
    $mavenArguments = @('-B','-q','clean','package')
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    & mvn.cmd @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
} finally { Pop-Location }
New-Item -ItemType Directory -Path (Join-Path $projectRoot 'app') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend\target\reconciliation-center-1.0.0.jar') -Destination $appJar -Force
Write-Output "Build complete: $appJar"
Write-Output 'Run start.bat or ./start.ps1 to launch.'
