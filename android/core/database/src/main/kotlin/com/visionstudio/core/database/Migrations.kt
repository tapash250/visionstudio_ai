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

/**
 * v2 → v3: the Vault now pages newest first with `id` as tie-break, so the two indexes behind
 * its queries gain `id` and cover that sort exactly. Indexes only; no row is touched.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_generation_history_created_at`")
        db.execSQL("DROP INDEX IF EXISTS `index_generation_history_media_type_created_at`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_generation_history_created_at_id` " +
                "ON `generation_history` (`created_at`, `id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_generation_history_media_type_created_at_id` " +
                "ON `generation_history` (`media_type`, `created_at`, `id`)",
        )
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
