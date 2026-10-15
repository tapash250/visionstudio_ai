package com.visionstudio.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: adds the durable `video_jobs` table (Ticket 3.3). Additive only, so existing
 * generation history is untouched. The SQL mirrors `schemas/.../2.json` exactly; Room
 * validates the migrated database against that schema when it opens and refuses to start
 * on any mismatch.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `video_jobs` (`id` TEXT NOT NULL, `task_id` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `parameters_json` TEXT NOT NULL, `source_path` TEXT, " +
                "`remote_job_json` TEXT, `engine_fingerprint` TEXT, `progress` REAL, `message` TEXT, " +
                "`error_message` TEXT, `result_item_id` TEXT, `attempts` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_jobs_status` ON `video_jobs` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_jobs_created_at` ON `video_jobs` (`created_at`)")
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
