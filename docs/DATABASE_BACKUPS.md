# MongoDB Backup & Disaster Recovery Guide — BINDASS.co

## Overview
This directory contains production-ready backup and restore scripts for BINDASS.co MongoDB database:
- `scripts/backup-mongodb.ps1` (Windows PowerShell)
- `scripts/backup-mongodb.sh` (Linux / macOS / Docker / CI)
- `scripts/restore-mongodb.ps1` (Windows PowerShell)
- `scripts/restore-mongodb.sh` (Linux / macOS / Docker / CI)

Each backup creates a compressed timestamped archive (`bindass_backup_YYYYMMDD_HHMMSS.gz`) and automatically purges archives older than 7 days (configurable retention policy).

---

## Prerequisites
Ensure MongoDB Database Tools (`mongodump` and `mongorestore`) are installed and accessible in `PATH`.
- **Download:** [MongoDB Database Tools](https://www.mongodb.com/try/download/database-tools)

---

## 1. Manual Backup

### PowerShell (Windows)
```powershell
# Uses default URI from SPRING_DATA_MONGODB_URI or localhost
.\scripts\backup-mongodb.ps1

# Or specify custom Atlas / remote URI
.\scripts\backup-mongodb.ps1 -MongoUri "mongodb+srv://user:pass@cluster.mongodb.net/bindass" -RetentionDays 14
```

### Bash (Linux / macOS / Cloud VMs)
```bash
chmod +x ./scripts/*.sh
./scripts/backup-mongodb.sh
```

---

## 2. Automated Scheduled Backups

### Linux Cron (Daily at 2:00 AM)
Run `crontab -e` and add:
```cron
0 2 * * * /path/to/bindass.co-Backend/scripts/backup-mongodb.sh >> /path/to/bindass.co-Backend/backups/cron.log 2>&1
```

### Windows Task Scheduler
Create a daily task running `powershell.exe`:
```
Program/script: powershell.exe
Add arguments:  -ExecutionPolicy Bypass -File "C:\path\to\bindass.co-Backend\scripts\backup-mongodb.ps1"
```

---

## 3. Disaster Recovery / Restore

### PowerShell
```powershell
# Restores the newest backup automatically
.\scripts\restore-mongodb.ps1

# Or specify a particular archive
.\scripts\restore-mongodb.ps1 -ArchivePath ".\backups\bindass_backup_20261020_230000.gz"
```

### Bash
```bash
./scripts/restore-mongodb.sh ./backups/bindass_backup_20261020_230000.gz
```
