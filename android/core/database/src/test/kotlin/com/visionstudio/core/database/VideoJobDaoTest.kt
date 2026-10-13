package com.visionstudio.core.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoJobDaoTest {

    @get:Rule val temp = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: VisionStudioDatabase
    private lateinit var dao: VideoJobDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, VisionStudioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoJobDao()
    }

    @After fun tearDown() = db.close()

    private fun job(id: String, status: String = "QUEUED", createdAt: Long = 1_000L) = VideoJobEntity(
        id = id, taskId = "t-$id", status = status, parametersJson = "{}", sourcePath = null,
        remoteJobJson = null, engineFingerprint = null, progress = null, message = null,
        errorMessage = null, resultItemId = null, attempts = 0, createdAt = createdAt, updatedAt = createdAt,
    )

    @Test
    fun `round trips and lists newest first`() = runBlocking {
        dao.upsert(job("old", createdAt = 1))
        dao.upsert(job("new", createdAt = 2))

        assertEquals(listOf("new", "old"), dao.observeRecent(10).first().map { it.id })
        assertEquals("t-old", dao.get("old")!!.taskId)
        assertNull(dao.get("missing"))
    }

    @Test
    fun `progress updates apply to live jobs`() = runBlocking {
        dao.upsert(job("a"))
        assertEquals(1, dao.updateProgress("a", "POLLING", 0.4f, "Rendering", now = 5))

        val row = dao.get("a")!!
        assertEquals("POLLING", row.status)
        assertEquals(0.4f, row.progress!!, 0f)
        assertEquals("Rendering", row.message)
        assertEquals(5L, row.updatedAt)
    }

    @Test
    fun `terminal jobs can never be revived by late updates`() = runBlocking {
        for (terminal in listOf("COMPLETED", "FAILED", "CANCELLED")) {
            dao.upsert(job("t-$terminal", status = terminal))
            assertEquals(0, dao.updateProgress("t-$terminal", "POLLING", 0.9f, "late", 9))
            assertEquals(0, dao.setRemoteJob("t-$terminal", "{}", "fp", 9))
            assertEquals(0, dao.markCompleted("t-$terminal", "item", 9))
            assertEquals(0, dao.markFailed("t-$terminal", "boom", 9))
            assertEquals(0, dao.markCancelled("t-$terminal", 9))
            assertEquals(terminal, dao.statusOf("t-$terminal"))
        }
    }

    @Test
    fun `cancel does not overwrite a finished job but stops a live one`() = runBlocking {
        dao.upsert(job("live", status = "POLLING"))
        dao.upsert(job("done", status = "COMPLETED"))

        assertEquals(1, dao.markCancelled("live", 7))
        assertEquals(0, dao.markCancelled("done", 7))
        assertEquals("CANCELLED", dao.statusOf("live"))
        assertEquals("COMPLETED", dao.statusOf("done"))
    }

    @Test
    fun `remote handle is stored and completion records the result`() = runBlocking {
        dao.upsert(job("a", status = "SUBMITTING"))

        dao.setRemoteJob("a", """{"id":"r-1"}""", "REST|fal|x", now = 3)
        assertEquals("POLLING", dao.get("a")!!.status)
        assertEquals("""{"id":"r-1"}""", dao.get("a")!!.remoteJobJson)
        assertEquals("REST|fal|x", dao.get("a")!!.engineFingerprint)

        dao.markCompleted("a", "item-9", now = 4)
        val done = dao.get("a")!!
        assertEquals("COMPLETED", done.status)
        assertEquals("item-9", done.resultItemId)
        assertEquals(1f, done.progress!!, 0f)
    }

    @Test
    fun `retry resets failed and cancelled jobs but never running ones`() = runBlocking {
        dao.upsert(job("f", status = "FAILED").copy(remoteJobJson = "{}", errorMessage = "x", attempts = 4))
        dao.upsert(job("c", status = "CANCELLED"))
        dao.upsert(job("p", status = "POLLING"))

        assertEquals(1, dao.resetForRetry("f", 8))
        assertEquals(1, dao.resetForRetry("c", 8))
        assertEquals(0, dao.resetForRetry("p", 8))

        val reset = dao.get("f")!!
        assertEquals("QUEUED", reset.status)
        assertNull(reset.remoteJobJson)
        assertNull(reset.errorMessage)
        assertEquals(0, reset.attempts)
        assertEquals("POLLING", dao.statusOf("p"))
    }

    @Test
    fun `attempts increment and delete removes`() = runBlocking {
        dao.upsert(job("a"))
        dao.incrementAttempts("a", 2)
        dao.incrementAttempts("a", 3)
        assertEquals(2, dao.get("a")!!.attempts)

        assertEquals(1, dao.delete("a"))
        assertNull(dao.get("a"))
    }

    /**
     * Builds a genuine version-1 database file from the exported v1 schema, with a history row
     * in it, then opens it through Room at version 2. Room runs [MIGRATION_1_2] and validates
     * the result against the v2 schema, throwing on any mismatch, so a clean open proves the
     * migration produces exactly the tables and indices the entities declare.
     */
    @Test
    fun `migration from v1 keeps history and creates a usable video_jobs table`() = runBlocking {
        val schemaV1 = JSONObject(File(schemaDir(), "1.json").readText()).getJSONObject("database")
        val dbFile = temp.newFile("migrating.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            val entities = schemaV1.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            raw.execSQL(
                "INSERT INTO generation_history (id, task_id, slot, media_type, local_uri, mime_type, width, height, " +
                    "duration_millis, size_bytes, blur_hash, prompt, model_id, seed, parameters_json, created_at, favorite) " +
                    "VALUES ('keep-me','t','VIDEO_STUDIO','VIDEO','file:///a.mp4','video/mp4',16,9,5000,10,NULL,'p','m',1,'{}',1,0)",
            )
            raw.version = 1
        }

        val migrated = Room.databaseBuilder(context, VisionStudioDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertNotNull("history survived the migration", migrated.generationHistoryDao().get("keep-me"))
            migrated.videoJobDao().upsert(job("after-migration"))
            assertEquals("QUEUED", migrated.videoJobDao().statusOf("after-migration"))
        } finally {
            migrated.close()
        }
        assertTrue(dbFile.exists())
    }

    private fun schemaDir(): File {
        // Gradle runs unit tests with the module directory as the working directory.
        val dir = File("schemas/com.visionstudio.core.database.VisionStudioDatabase")
        check(dir.isDirectory) { "schema directory not found at ${dir.absolutePath}" }
        return dir
    }
}
