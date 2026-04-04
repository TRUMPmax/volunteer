$ErrorActionPreference = "SilentlyContinue"

$ports = 5500, 8000, 8001, 8002, 8003, 8004
$connections = Get-NetTCPConnection -LocalPort $ports -State Listen
$pids = $connections | Select-Object -ExpandProperty OwningProcess -Unique

if ($pids) {
    Stop-Process -Id $pids -Force
    Write-Host "Local service processes stopped."
} else {
    Write-Host "No local service process detected."
}
