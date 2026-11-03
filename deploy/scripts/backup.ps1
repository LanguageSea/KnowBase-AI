[CmdletBinding()]
param(
    [string]$ComposeFile = (Join-Path $PSScriptRoot "..\compose.prod.yml"),
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\..\.env"),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\..\backups"),
    [string]$ProjectName = "knowbase-prod",
    [string]$Collection = "knowbase_chunks"
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

function Get-EnvValue {
    param([Parameter(Mandatory)][string]$Name)
    $line = Get-Content $EnvFile |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -Last 1
    if (-not $line) {
        throw "Missing $Name in $EnvFile"
    }
    return $line.Substring($line.IndexOf("=") + 1).Trim()
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

if (-not (Test-Path -LiteralPath $ComposeFile)) {
    throw "Compose file not found: $ComposeFile"
}
if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "Env file not found: $EnvFile"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupDir = Join-Path $OutputDirectory "backup-$timestamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
$database = Get-EnvValue "MYSQL_DATABASE"

Write-Host "[1/5] Checking service state"
Invoke-Compose @("up", "-d", "mysql", "redis", "qdrant", "ai-service") | Out-Host

Write-Host "[2/5] Dumping MySQL"
$dumpCommand = 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --quick --routines --triggers --events --set-gtid-purged=OFF "$MYSQL_DATABASE" > /tmp/knowbase-backup.sql'
Invoke-Compose @("exec", "-T", "mysql", "sh", "-c", $dumpCommand) | Out-Host
$mysqlFile = Join-Path $backupDir "mysql.sql"
Invoke-Compose @("cp", "mysql:/tmp/knowbase-backup.sql", $mysqlFile) | Out-Host
Invoke-Compose @("exec", "-T", "mysql", "rm", "-f", "/tmp/knowbase-backup.sql") | Out-Host

Write-Host "[3/5] Archiving uploads volume"
$uploadsFile = Join-Path $backupDir "uploads.tar.gz"
$volumeName = "${ProjectName}_knowbase-uploads"
& docker run --rm `
    -v "${volumeName}:/source:ro" `
    -v "${backupDir}:/backup" `
    mysql:8.4 sh -c "tar -czf /backup/uploads.tar.gz -C /source ."
if ($LASTEXITCODE -ne 0) {
    throw "Failed to archive uploads volume $volumeName"
}

Write-Host "[4/5] Creating Qdrant snapshot"
$createSnapshot = @"
import json, os, urllib.request
url = "http://127.0.0.1:6333/collections/$Collection/snapshots"
headers = {"Content-Type": "application/json"}
api_key = os.getenv("QDRANT_API_KEY", "")
if api_key:
    headers["api-key"] = api_key
request = urllib.request.Request(url, data=b"{}", method="POST", headers=headers)
with urllib.request.urlopen(request, timeout=30) as response:
    print(response.read().decode("utf-8"))
"@
$snapshotJson = (& docker compose --project-name $ProjectName --env-file $EnvFile -f $ComposeFile `
    exec -T ai-service python -c $createSnapshot) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw "Qdrant snapshot request failed"
}
$snapshotName = ($snapshotJson | ConvertFrom-Json).result.name
if (-not $snapshotName) {
    throw "Qdrant did not return a snapshot name: $snapshotJson"
}
Invoke-Compose @("exec", "-T", "qdrant", "mkdir", "-p", "/qdrant/snapshots/$Collection") | Out-Host
$qdrantFile = Join-Path $backupDir "qdrant-$Collection.snapshot"
Invoke-Compose @("cp", "qdrant:/qdrant/snapshots/$Collection/$snapshotName", $qdrantFile) | Out-Host

Write-Host "[5/5] Writing integrity manifest"
$files = @(
    [ordered]@{
        path = "mysql.sql"
        bytes = (Get-Item -LiteralPath $mysqlFile).Length
        sha256 = Get-Sha256 $mysqlFile
    },
    [ordered]@{
        path = "uploads.tar.gz"
        bytes = (Get-Item -LiteralPath $uploadsFile).Length
        sha256 = Get-Sha256 $uploadsFile
    },
    [ordered]@{
        path = "qdrant-$Collection.snapshot"
        bytes = (Get-Item -LiteralPath $qdrantFile).Length
        sha256 = Get-Sha256 $qdrantFile
    }
)
$manifest = [ordered]@{
    format_version = 1
    created_at = (Get-Date).ToUniversalTime().ToString("o")
    project_name = $ProjectName
    database = $database
    qdrant_collection = $Collection
    qdrant_snapshot_name = $snapshotName
    files = $files
    restore_command = ".\deploy\scripts\restore.ps1 -BackupDirectory `"$backupDir`" -Force"
}
$manifestPath = Join-Path $backupDir "manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8

Write-Host ""
Write-Host "Backup complete: $backupDir"
Write-Host "Manifest SHA-256: $(Get-Sha256 $manifestPath)"
Write-Host "The application master key is NOT stored in this backup. Keep APP_MASTER_KEY separately."
