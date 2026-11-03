[CmdletBinding()]
param(
    [string]$ComposeFile = (Join-Path $PSScriptRoot "..\compose.prod.yml"),
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\..\.env"),
    [string]$BaseUrl = "https://127.0.0.1",
    [switch]$ResetVolumes
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $ResetVolumes) {
    throw "Clean-room acceptance requires -ResetVolumes. It removes all Docker volumes for this project."
}

function Invoke-Compose {
    param([Parameter(Mandatory)][string[]]$Arguments)
    & docker compose --project-name knowbase-prod --env-file $EnvFile -f $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($Arguments -join ' ')"
    }
}

Write-Host "[1/5] Checking environment"
& (Join-Path $PSScriptRoot "check-env.ps1") -EnvFile $EnvFile -ComposeFile $ComposeFile
if ($LASTEXITCODE -ne 0) {
    throw "Environment check failed"
}

Write-Host "[2/5] Removing previous containers and volumes"
Invoke-Compose @("down", "-v", "--remove-orphans") | Out-Host

Write-Host "[3/5] Building images"
Invoke-Compose @("build", "--pull") | Out-Host

Write-Host "[4/5] Starting the stack"
Invoke-Compose @("up", "-d") | Out-Host

Write-Host "[5/5] Waiting for health and running smoke"
$ready = $false
for ($attempt = 1; $attempt -le 60; $attempt++) {
    try {
        $response = Invoke-RestMethod -Uri "$BaseUrl/health" -SkipCertificateCheck -TimeoutSec 5
        if ($response.status -eq "UP") {
            $ready = $true
            break
        }
    } catch {
        # Containers may still be starting.
    }
    Start-Sleep -Seconds 5
}
if (-not $ready) {
    Invoke-Compose @("ps") | Out-Host
    Invoke-Compose @("logs", "--tail", "200") | Out-Host
    throw "Stack did not become healthy within five minutes"
}

$python = Join-Path $PSScriptRoot "..\..\backend-python\.venv313\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python)) {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if (-not $pythonCommand) {
        throw "Python not found. Run uv sync in backend-python or install Python 3.12+."
    }
    $python = $pythonCommand.Source
}

& $python (Join-Path $PSScriptRoot "..\..\tests\smoke\e2e.py") --base-url $BaseUrl --insecure
if ($LASTEXITCODE -ne 0) {
    throw "Smoke test failed"
}

Write-Host "Clean-room acceptance passed: $BaseUrl"
