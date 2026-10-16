param([ValidateRange(1024,65535)][int]$Port = 18180, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$appJar = Join-Path $projectRoot 'app\reconciliation-center.jar'
$logsPath = Join-Path $projectRoot 'logs'
$pidFile = Join-Path $logsPath 'server.pid'
$appUrl = "http://127.0.0.1:$Port"
if (-not (Test-Path -LiteralPath $appJar)) { & (Join-Path $projectRoot 'build.ps1') }
$javaCommand = Get-Command java -ErrorAction Stop
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    $owned = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener[0].OwningProcess)"
    if (-not $owned -or -not $owned.CommandLine.Contains($appJar)) { throw "Port $Port is used by another service. Use -Port to choose a different port." }
    Write-Output "Already running: $appUrl"
    if (-not $NoBrowser) { Start-Process $appUrl }
    exit 0
}
New-Item -ItemType Directory -Path $logsPath -Force | Out-Null
$server = Start-Process -FilePath $javaCommand.Source -ArgumentList @('-jar',"`"$appJar`"","--server.port=$Port") -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logsPath 'server.log') -RedirectStandardError (Join-Path $logsPath 'server-error.log') -PassThru
Set-Content -LiteralPath $pidFile -Value $server.Id
$healthy = $false
for ($attempt=0; $attempt -lt 60; $attempt++) {
    if ($server.HasExited) { throw "Server exited. Read $logsPath\server.log and server-error.log." }
    try {
        $null = Invoke-RestMethod -Uri "$appUrl/api/session" -TimeoutSec 2
        $healthy = $true
        break
    } catch { Start-Sleep -Milliseconds 300 }
}
if (-not $healthy) { throw "Startup did not finish in time. Read logs at $logsPath. PID: $($server.Id)" }
Write-Output "Hengzhang is running at $appUrl"
Write-Output 'Default accounts (unless passwords are overridden with environment variables):'
Write-Output 'Operator: admin / demo123456; reviewer: reviewer / demo123456'
Write-Output "PID: $($server.Id). Stop with ./stop.ps1"
if (-not $NoBrowser) { Start-Process $appUrl }
