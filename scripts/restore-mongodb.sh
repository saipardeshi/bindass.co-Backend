#!/usr/bin/env bash
# Automated MongoDB Restore Script for BINDASS.co (Linux/macOS/Docker/CI)
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$SCRIPT_DIR/../backups}"
MONGO_URI="${SPRING_DATA_MONGODB_URI:-${MONGODB_URI:-mongodb://localhost:27017/bindass}}"
ARCHIVE_PATH="${1:-}"

if [ -z "$ARCHIVE_PATH" ]; then
  ARCHIVE_PATH="$(ls -t "$BACKUP_DIR"/bindass_backup_*.gz 2>/dev/null | head -n 1 || true)"
fi

if [ -z "$ARCHIVE_PATH" ] || [ ! -f "$ARCHIVE_PATH" ]; then
  echo "Error: No valid backup archive found at '$ARCHIVE_PATH'." >&2
  exit 1
fi

echo "Restoring MongoDB database from archive: $ARCHIVE_PATH"
echo "Target URI: $MONGO_URI"

mongorestore --uri="$MONGO_URI" --archive="$ARCHIVE_PATH" --gzip --drop

echo "Database restoration completed successfully!"
