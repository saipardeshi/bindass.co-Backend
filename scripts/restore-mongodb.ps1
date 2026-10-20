<#
.SYNOPSIS
    Automated MongoDB Restore Script for BINDASS.co (PowerShell)
.DESCRIPTION
    Restores a database from a compressed gzip archive using mongorestore.
.PARAMETER ArchivePath
    Path to the .gz archive file. If not provided, the newest backup in .\backups is used.
.PARAMETER MongoUri
    MongoDB connection string to restore into.
.PARAMETER Drop
    If switch is present, drops existing collections before restoring.
#>
param(
    [string]$ArchivePath,
    [string]$MongoUri = $(if ($env:SPRING_DATA_MONGODB_URI) { $env:SPRING_DATA_MONGODB_URI } elseif ($env:MONGODB_URI) { $env:MONGODB_URI } else { "mongodb://localhost:27017/bindass" }),
    [switch]$Drop = $true
)

$ErrorActionPreference = "Stop"

if (-not $ArchivePath) {
    $backupDir = Join-Path $PSScriptRoot "..\backups"
    $newest = Get-ChildItem -Path $backupDir -Filter "bindass_backup_*.gz" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $newest) {
        Write-Error "No backup archives found in $backupDir."
        exit 1
    }
    $ArchivePath = $newest.FullName
}

if (-not (Test-Path $ArchivePath)) {
    Write-Error "Specified archive path does not exist: $ArchivePath"
    exit 1
}

Write-Host "Restoring MongoDB database from archive: $ArchivePath" -ForegroundColor Cyan
Write-Host "Target URI: $MongoUri" -ForegroundColor Cyan

$dropFlag = if ($Drop) { "--drop" } else { "" }

try {
    if ($dropFlag) {
        & mongorestore --uri="$MongoUri" --archive="$ArchivePath" --gzip --drop
    } else {
        & mongorestore --uri="$MongoUri" --archive="$ArchivePath" --gzip
    }
    Write-Host "Database restoration completed successfully!" -ForegroundColor Green
} catch {
    Write-Error "Failed to restore database: $_"
    exit 1
}
