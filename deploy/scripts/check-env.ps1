[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\..\.env"),
    [string]$ComposeFile = (Join-Path $PSScriptRoot "..\compose.prod.yml")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"
$failures = [System.Collections.Generic.List[string]]::new()

function Test-Command {
    param([string]$Name, [scriptblock]$VersionCommand)
    try {
        $output = & $VersionCommand 2>&1 | Select-Object -First 1
        Write-Host "OK   $Name - $output"
    } catch {
        Write-Host "FAIL $Name - $($_.Exception.Message)"
        $script:failures.Add($Name)
    }
}

Test-Command "docker" { docker version --format "{{.Server.Version}}" }
Test-Command "docker compose" { docker compose version --short }
Test-Command "java" { java -version }
Test-Command "maven" { mvn -version }
Test-Command "node" { node --version }
Test-Command "npm" { npm --version }
Test-Command "uv" { uv --version }

if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Host "FAIL env file - $EnvFile does not exist"
    $failures.Add("env")
} else {
    $required = @(
        "MYSQL_PASSWORD",
        "MYSQL_ROOT_PASSWORD",
        "REDIS_PASSWORD",
        "QDRANT_API_KEY",
        "INTERNAL_TOKEN",
        "APP_MASTER_KEY"
    )
    foreach ($name in $required) {
        $line = Get-Content $EnvFile |
            Where-Object { $_ -match "^\s*$([regex]::Escape($name))\s*=" } |
            Select-Object -Last 1
        $value = if ($line) { $line.Substring($line.IndexOf("=") + 1).Trim() } else { "" }
        if (-not $value -or $value -match "replace-with|change-me|changeme|123456|your-password") {
            Write-Host "FAIL $name - missing or placeholder"
            $failures.Add($name)
        } else {
            Write-Host "OK   $name - configured (value hidden)"
        }
    }
}

if (Test-Path -LiteralPath $ComposeFile) {
    docker compose --env-file $EnvFile -f $ComposeFile config --quiet
    if ($LASTEXITCODE -ne 0) {
        Write-Host "FAIL compose config"
        $failures.Add("compose")
    } else {
        Write-Host "OK   compose config"
    }
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "Environment check failed: $($failures -join ', ')"
    exit 1
}
Write-Host ""
Write-Host "Environment check passed."
