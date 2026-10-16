$ErrorActionPreference = 'Stop'
$appJar = Join-Path $PSScriptRoot 'app\reconciliation-center.jar'
$pidFile = Join-Path $PSScriptRoot 'logs\server.pid'
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Output 'No saved server PID.'; exit 0 }
$serverProcessId = [int](Get-Content -LiteralPath $pidFile)
$owned = Get-CimInstance Win32_Process -Filter "ProcessId = $serverProcessId"
if (-not $owned) { Write-Output 'The server has already stopped.'; exit 0 }
if (-not $owned.CommandLine.Contains($appJar)) { throw 'The saved PID belongs to a different process. No process was stopped.' }
Stop-Process -Id $serverProcessId -ErrorAction Stop
Write-Output "Stopped Hengzhang PID $serverProcessId. Database files are preserved."
