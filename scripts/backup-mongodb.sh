#!/usr/bin/env bash
# Automated MongoDB Backup Script for BINDASS.co (Linux/macOS/Docker/CI)
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$SCRIPT_DIR/../backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
MONGO_URI="${SPRING_DATA_MONGODB_URI:-${MONGODB_URI:-mongodb://localhost:27017/bindass}}"

mkdir -p "$BACKUP_DIR"
TIMESTAMP="$(date +'%Y%m%d_%H%M%S')"
ARCHIVE_FILE="$BACKUP_DIR/bindass_backup_${TIMESTAMP}.gz"
LOG_FILE="$BACKUP_DIR/backup.log"

log() {
  local level="${2:-INFO}"
  local entry="[$(date +'%Y-%m-%d %H:%M:%S')] [$level] $1"
  echo "$entry"
  echo "$entry" >> "$LOG_FILE"
}

log "Starting MongoDB backup for BINDASS.co..."

if ! command -v mongodump &> /dev/null; then
  log "mongodump command not found. Please install mongodb-database-tools." "ERROR"
  exit 1
fi

if mongodump --uri="$MONGO_URI" --archive="$ARCHIVE_FILE" --gzip; then
  SIZE=$(du -h "$ARCHIVE_FILE" | cut -f1)
  log "Backup created successfully: $ARCHIVE_FILE ($SIZE)" "SUCCESS"
else
  log "Backup execution failed." "ERROR"
  exit 1
fi

# Prune archives older than retention days
find "$BACKUP_DIR" -name "bindass_backup_*.gz" -type f -mtime +"$RETENTION_DAYS" -exec rm -f {} +
log "Cleaned backups older than $RETENTION_DAYS days." "INFO"
log "Backup process completed." "SUCCESS"
