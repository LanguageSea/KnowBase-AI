[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackupDirectory,
    [string]$ComposeFile = (Join-Path $PSScriptRoot "..\compose.prod.yml"),
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\..\.env"),
    [string]$ProjectName = "knowbase-prod",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Compose {
    param([Parameter(Mandatory)][string[]]$Arguments)
    & docker compose --project-name $ProjectName --env-file $EnvFile -f $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($Arguments -join ' ')"
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Wait-HttpOk {
    param([string]$Url, [int]$Attempts = 40)
    for ($index = 1; $index -le $Attempts; $index++) {
        try {
            $response = Invoke-WebRequest -Uri $Url -SkipCertificateCheck -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            if ($index -eq $Attempts) {
                throw "Health check did not become ready: $Url"
            }
        }
        Start-Sleep -Seconds 3
    }
}

$backupDir = (Resolve-Path -LiteralPath $BackupDirectory).Path
$manifestPath = Join-Path $backupDir "manifest.json"
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.format_version -ne 1) {
    throw "Unsupported backup format: $($manifest.format_version)"
}

foreach ($file in $manifest.files) {
    $path = Join-Path $backupDir $file.path
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Backup file missing: $path"
    }
    $actual = Get-Sha256 $path
    if ($actual -ne $file.sha256) {
        throw "SHA-256 mismatch for $($file.path): expected=$($file.sha256) actual=$actual"
    }
    Write-Host "Verified $($file.path)"
}

if (-not $Force) {
    $answer = Read-Host "Restore will overwrite the database, uploads and Qdrant collection. Type RESTORE to continue"
    if ($answer -cne "RESTORE") {
        throw "Restore cancelled"
    }
}

Write-Host "[1/6] Starting infrastructure"
Invoke-Compose @("up", "-d", "mysql", "redis", "qdrant", "ai-service") | Out-Host

Write-Host "[2/6] Restoring MySQL"
$mysqlSource = Join-Path $backupDir "mysql.sql"
Invoke-Compose @("cp", $mysqlSource, "mysql:/tmp/knowbase-restore.sql") | Out-Host
$restoreSql = 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" < /tmp/knowbase-restore.sql'
Invoke-Compose @("exec", "-T", "mysql", "sh", "-c", $restoreSql) | Out-Host
Invoke-Compose @("exec", "-T", "mysql", "rm", "-f", "/tmp/knowbase-restore.sql") | Out-Host

Write-Host "[3/6] Restoring uploads"
$volumeName = "${ProjectName}_knowbase-uploads"
& docker run --rm `
    -v "${volumeName}:/target" `
    -v "${backupDir}:/backup:ro" `
    mysql:8.4 sh -c "find /target -mindepth 1 -maxdepth 1 -exec rm -rf {} + && tar -xzf /backup/uploads.tar.gz -C /target"
if ($LASTEXITCODE -ne 0) {
    throw "Uploads restore failed"
}

Write-Host "[4/6] Restoring Qdrant snapshot"
$collection = [string]$manifest.qdrant_collection
$snapshotName = [string]$manifest.qdrant_snapshot_name
$snapshotSource = Join-Path $backupDir "qdrant-$collection.snapshot"
Invoke-Compose @("exec", "-T", "qdrant", "mkdir", "-p", "/qdrant/snapshots/$collection") | Out-Host
Invoke-Compose @("cp", $snapshotSource, "qdrant:/qdrant/snapshots/$collection/$snapshotName") | Out-Host
$recoverSnapshot = @"
import json, os, urllib.request
url = "http://127.0.0.1:6333/collections/$collection/snapshots/recover"
headers = {"Content-Type": "application/json"}
api_key = os.getenv("QDRANT_API_KEY", "")
if api_key:
    headers["api-key"] = api_key
body = json.dumps({"location": "file:///qdrant/snapshots/$collection/$snapshotName", "priority": "snapshot"}).encode("utf-8")
request = urllib.request.Request(url, data=body, method="PUT", headers=headers)
with urllib.request.urlopen(request, timeout=120) as response:
    print(response.read().decode("utf-8"))
"@
Invoke-Compose @("exec", "-T", "ai-service", "python", "-c", $recoverSnapshot) | Out-Host

Write-Host "[5/6] Starting application"
Invoke-Compose @("up", "-d") | Out-Host

Write-Host "[6/6] Verifying health"
$httpPort = 443
if (Test-Path -LiteralPath $EnvFile) {
    $line = Get-Content $EnvFile |
        Where-Object { $_ -match "^\s*HTTPS_PORT\s*=" } |
        Select-Object -Last 1
    if ($line) {
        $httpPort = [int]$line.Substring($line.IndexOf("=") + 1).Trim()
    }
}
Wait-HttpOk "https://127.0.0.1:$httpPort/health"

$rowCount = (& docker compose --project-name $ProjectName --env-file $EnvFile -f $ComposeFile `
    exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM sys_user;"') -join ""
Write-Host "Restore complete. sys_user rows=$($rowCount.Trim())"
Write-Host "Restore APP_MASTER_KEY separately; it is intentionally not stored in backups."
