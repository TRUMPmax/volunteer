param(
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

$sqlPath = Join-Path $repoRoot "infrastructure\\mysql\\init.sql"
$mysql = (Get-Command mysql -ErrorAction Stop).Source

if (Test-Path $sqlPath) {
    Write-Host "Importing MySQL initialization script..."
    $sqlPathForCmd = $sqlPath -replace "\\", "/"
    $mysqlForCmd = $mysql -replace "\\", "/"
    $mysqlCommand = "`"$mysqlForCmd`" --host=$MySqlHost --port=$MySqlPort --user=$MySqlUser --password=$MySqlPassword --default-character-set=utf8mb4 < `"$sqlPathForCmd`""
    cmd /c $mysqlCommand
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL initialization script import failed with exit code $LASTEXITCODE"
    }
} else {
    Write-Warning "Initialization script not found: $sqlPath"
    Write-Host "Falling back to inline database bootstrap..."
    & $mysql --host=$MySqlHost --port=$MySqlPort --user=$MySqlUser --password=$MySqlPassword --default-character-set=utf8mb4 `
        --execute="CREATE DATABASE IF NOT EXISTS volunteer_user_db DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS volunteer_volunteer_db DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS volunteer_activity_db DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS volunteer_community_db DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL inline bootstrap failed with exit code $LASTEXITCODE"
    }
}

Write-Host "MySQL initialization completed."
