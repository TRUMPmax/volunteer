param(
    [switch]$SkipFrontend,
    [string]$MySqlHost,
    [int]$MySqlPort = 0,
    [string]$MySqlUser,
    [string]$MySqlPassword
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\\..")
$envFile = Join-Path $repoRoot ".env.local"

if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ([string]::IsNullOrWhiteSpace($_) -or $_.Trim().StartsWith("#")) {
            return
        }

        $parts = $_ -split "=", 2
        if ($parts.Count -eq 2) {
            $name = $parts[0].Trim()
            $value = $parts[1].Trim()
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

if (-not $MySqlHost) {
    $MySqlHost = if ($env:LOCAL_MYSQL_HOST) { $env:LOCAL_MYSQL_HOST } else { "127.0.0.1" }
}

if ($MySqlPort -le 0) {
    $MySqlPort = if ($env:LOCAL_MYSQL_PORT) { [int]$env:LOCAL_MYSQL_PORT } else { 3306 }
}

if (-not $MySqlUser -and $env:LOCAL_MYSQL_USER) {
    $MySqlUser = $env:LOCAL_MYSQL_USER
}

if (-not $MySqlPassword -and $env:LOCAL_MYSQL_PASSWORD) {
    $MySqlPassword = $env:LOCAL_MYSQL_PASSWORD
}

if (-not $MySqlUser) {
    $MySqlUser = Read-Host "Enter local MySQL username"
}

if (-not $MySqlPassword) {
    $MySqlPassword = Read-Host "Enter local MySQL password"
}

$env:LOCAL_MYSQL_HOST = $MySqlHost
$env:LOCAL_MYSQL_PORT = "$MySqlPort"
$env:LOCAL_MYSQL_USER = $MySqlUser
$env:LOCAL_MYSQL_PASSWORD = $MySqlPassword

function Wait-ForPort {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $listening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($listening) {
            return $true
        }
        Start-Sleep -Milliseconds 400
    }

    return $false
}

$runtimeDir = Join-Path $repoRoot ".local-runtime"
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

$services = @(
    @{ Name = "user-service"; Dir = Join-Path $repoRoot "services\\user-service"; Command = "mvnw.cmd"; Args = @("spring-boot:run") },
    @{ Name = "volunteer-service"; Dir = Join-Path $repoRoot "services\\volunteer-service"; Command = "mvnw.cmd"; Args = @("spring-boot:run") },
    @{ Name = "activity-service"; Dir = Join-Path $repoRoot "services\\activity-service"; Command = "mvnw.cmd"; Args = @("spring-boot:run") },
    @{ Name = "community-service"; Dir = Join-Path $repoRoot "services\\community-service"; Command = "mvnw.cmd"; Args = @("spring-boot:run") },
    @{ Name = "gateway"; Dir = Join-Path $repoRoot "gateway"; Command = "mvnw.cmd"; Args = @("spring-boot:run") }
)

foreach ($service in $services) {
    $stdout = Join-Path $runtimeDir "$($service.Name).out.log"
    $stderr = Join-Path $runtimeDir "$($service.Name).err.log"

    Start-Process `
        -FilePath (Join-Path $service.Dir $service.Command) `
        -ArgumentList $service.Args `
        -WindowStyle Hidden `
        -WorkingDirectory $service.Dir `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr | Out-Null

    Start-Sleep -Seconds 2
}

if (-not $SkipFrontend) {
    $frontendOut = Join-Path $runtimeDir "web.out.log"
    $frontendErr = Join-Path $runtimeDir "web.err.log"

    Start-Process `
        -FilePath "python" `
        -ArgumentList @("-m", "http.server", "5500", "--directory", (Join-Path $repoRoot "apps\\web")) `
        -WindowStyle Hidden `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $frontendOut `
        -RedirectStandardError $frontendErr | Out-Null
}

$requiredPorts = @(8001, 8002, 8003, 8004, 8000)
if (-not $SkipFrontend) {
    $requiredPorts += 5500
}

$missingPorts = @()
foreach ($port in $requiredPorts) {
    if (-not (Wait-ForPort -Port $port -TimeoutSeconds 70)) {
        $missingPorts += $port
    }
}

if ($missingPorts.Count -gt 0) {
    throw "Failed to start local services on port(s): $($missingPorts -join ', '). Check .local-runtime\\*.log"
}

Write-Host ""
Write-Host "Local services started:"
Write-Host "  Frontend: http://127.0.0.1:5500"
Write-Host "  Gateway:  http://127.0.0.1:8000/api/portal"
Write-Host "  Logs:     $runtimeDir"
Write-Host ""
Write-Host "MySQL connection: ${MySqlUser}@${MySqlHost}:$MySqlPort"
Write-Host "Make sure local MySQL is running and scripts/local/init-mysql.ps1 has completed."
