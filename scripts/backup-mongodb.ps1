<#
.SYNOPSIS
    Automated MongoDB Backup Script for BINDASS.co (PowerShell)
.DESCRIPTION
    Creates a compressed timestamped gzip archive of the MongoDB database using mongodump.
    Enforces a retention policy by removing backups older than specified days.
.PARAMETER MongoUri
    MongoDB connection string. Defaults to environment variable MONGODB_URI or localhost.
.PARAMETER BackupDir
    Target directory to store backup archives. Defaults to .\backups.
.PARAMETER RetentionDays
    Number of days to keep backup archives. Defaults to 7.
#>
param(
    [string]$MongoUri = $(if ($env:SPRING_DATA_MONGODB_URI) { $env:SPRING_DATA_MONGODB_URI } elseif ($env:MONGODB_URI) { $env:MONGODB_URI } else { "mongodb://localhost:27017/bindass" }),
    [string]$BackupDir = (Join-Path $PSScriptRoot "..\backups"),
    [int]$RetentionDays = 7
)

$ErrorActionPreference = "Stop"

# Ensure backup directory exists
if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$archiveFile = Join-Path $BackupDir "bindass_backup_$timestamp.gz"
$logFile = Join-Path $BackupDir "backup.log"

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $logEntry = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] [$Level] $Message"
    Write-Host $logEntry
    Add-Content -Path $logFile -Value $logEntry
}

Write-Log "Starting MongoDB backup for BINDASS.co..."

# Check if mongodump is available
$dumpCmd = Get-Command "mongodump" -ErrorAction SilentlyContinue
if (-not $dumpCmd) {
    Write-Log "mongodump CLI tool not found in PATH. Please install MongoDB Database Tools (https://www.mongodb.com/try/download/database-tools)" "ERROR"
    exit 1
}

try {
    # Execute mongodump with gzip archive
    & mongodump --uri="$MongoUri" --archive="$archiveFile" --gzip
    
    if (Test-Path $archiveFile) {
        $sizeBytes = (Get-Item $archiveFile).Length
        $sizeMB = [math]::Round($sizeBytes / 1MB, 2)
        Write-Log "Backup successfully created: $archiveFile ($sizeMB MB)" "SUCCESS"
    } else {
        throw "Archive file was not created."
    }

    # Apply Retention Policy: delete archives older than RetentionDays
    $cutoff = (Get-Date).AddDays(-$RetentionDays)
    $oldFiles = Get-ChildItem -Path $BackupDir -Filter "bindass_backup_*.gz" | Where-Object { $_.LastWriteTime -lt $cutoff }
    foreach ($file in $oldFiles) {
        Remove-Item -Path $file.FullName -Force
        Write-Log "Removed old backup: $($file.Name) (Retention: $RetentionDays days)" "INFO"
    }

    Write-Log "Backup process completed successfully." "SUCCESS"
} catch {
    Write-Log "Backup failed: $_" "ERROR"
    exit 1
}
