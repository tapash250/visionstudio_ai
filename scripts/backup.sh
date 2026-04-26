#!/bin/bash
set -e

# VisionStudio AI Backup Script
# Run via cron: 0 2 * * * /path/to/backup.sh

BACKUP_DIR="/backups/visionstudio"
DATE=$(date +%Y%m%d_%H%M%S)
RETENTION_DAYS=30

# Database backup
echo "💾 Backing up database..."
pg_dump "$DATABASE_URL" | gzip > "$BACKUP_DIR/db_$DATE.sql.gz"

# Media backup (if using local storage)
# aws s3 sync s3://visionstudio-media "$BACKUP_DIR/media_$DATE" || true

# Cleanup old backups
echo "🧹 Cleaning up old backups..."
find "$BACKUP_DIR" -name "db_*.sql.gz" -mtime +$RETENTION_DAYS -delete
find "$BACKUP_DIR" -name "media_*" -mtime +$RETENTION_DAYS -exec rm -rf {} + 2>/dev/null || true

echo "✅ Backup complete: $BACKUP_DIR/db_$DATE.sql.gz"
