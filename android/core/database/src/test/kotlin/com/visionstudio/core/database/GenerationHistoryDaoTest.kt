package com.visionstudio.core.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class GenerationHistoryDaoTest {

    @get:Rule val temp = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: VisionStudioDatabase
    private lateinit var dao: GenerationHistoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, VisionStudioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.generationHistoryDao()
    }

    @After fun tearDown() = db.close()

    private fun row(id: String, createdAt: Long, type: String = "IMAGE") = GenerationHistoryEntity(
        id = id, taskId = "t-$id", slot = "TEXT_TO_IMAGE", mediaType = type, localUri = "file:///$id",
        mimeType = if (type == "VIDEO") "video/mp4" else "image/png", width = 1, height = 1,
        durationMillis = if (type == "VIDEO") 1_000 else null, sizeBytes = 1, blurHash = null,
        prompt = id, modelId = "m", seed = null, parametersJson = "{}", createdAtEpochMillis = createdAt,
    )

    @Test
    fun `newest first with id breaking ties, and a larger limit only appends`() = runBlocking {
        // Three rows share one millisecond: only the id can order them.
        dao.upsertAll(listOf(row("b", 10), row("a", 10), row("c", 10), row("old", 5), row("new", 20)))

        val firstPage = dao.observeNewest(limit = 3).first().map { it.id }
        val bothPages = dao.observeNewest(limit = 5).first().map { it.id }

        assertEquals(listOf("new", "c", "b"), firstPage)
        assertEquals(listOf("new", "c", "b", "a", "old"), bothPages)
        assertEquals("growing the window never reorders what was already shown", firstPage, bothPages.take(3))
    }

    @Test
    fun `a type filter is applied before the limit`() = runBlocking {
        dao.upsertAll(
            listOf(row("v1", 1, "VIDEO"), row("i1", 2), row("i2", 3), row("v2", 4, "VIDEO"), row("i3", 5)),
        )

        assertEquals(listOf("v2", "v1"), dao.observeNewestOfType("VIDEO", limit = 2).first().map { it.id })
        assertEquals(listOf("i3", "i2"), dao.observeNewestOfType("IMAGE", limit = 2).first().map { it.id })
    }

    @Test
    fun `counts are grouped by type and cover every row, not just a page`() = runBlocking {
        dao.upsertAll((1..7).map { row("i$it", it.toLong()) } + (1..3).map { row("v$it", 100L + it, "VIDEO") })

        val counts = dao.observeCountsByType().first().associate { it.mediaType to it.count }

        assertEquals(mapOf("IMAGE" to 7, "VIDEO" to 3), counts)
    }

    @Test
    fun `paging queries are answered from the index without a sort step`() {
        val readable = db.openHelper.readableDatabase
        fun plan(sql: String, vararg args: Any): String =
            readable.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }.joinToString(" | ")
            }

        val all = plan("SELECT * FROM generation_history ORDER BY created_at DESC, id DESC LIMIT ?", 40)
        val ofType = plan(
            "SELECT * FROM generation_history WHERE media_type = ? ORDER BY created_at DESC, id DESC LIMIT ?",
            "VIDEO",
            40,
        )

        assertTrue(all, "index_generation_history_created_at_id" in all && "TEMP B-TREE" !in all)
        assertTrue(ofType, "index_generation_history_media_type_created_at_id" in ofType && "TEMP B-TREE" !in ofType)
    }

    @Test
    fun `migration from v2 keeps history and swaps in the paging indexes`() = runBlocking {
        val dbFile = temp.newFile("v2.db")
        createFromSchema(dbFile, version = 2)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "INSERT INTO generation_history (id, task_id, slot, media_type, local_uri, mime_type, width, height, " +
                    "duration_millis, size_bytes, blur_hash, prompt, model_id, seed, parameters_json, created_at, favorite) " +
                    "VALUES ('keep-me','t','TEXT_TO_IMAGE','IMAGE','file:///a.png','image/png',1,1,NULL,1,NULL,'p','m',NULL,'{}',1,0)",
            )
        }

        // Room validates the migrated schema against v3 on open and throws on any mismatch.
        val migrated = Room.databaseBuilder(context, VisionStudioDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertNotNull(migrated.generationHistoryDao().get("keep-me"))
            val indexes = migrated.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'generation_history'")
                .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            assertTrue(indexes.toString(), "index_generation_history_created_at_id" in indexes)
            assertTrue(indexes.toString(), "index_generation_history_media_type_created_at_id" in indexes)
            assertTrue("the old single-column index is gone: $indexes", "index_generation_history_created_at" !in indexes)
        } finally {
            migrated.close()
        }
    }

    /** Builds a database exactly as the exported schema for [version] describes it. */
    private fun createFromSchema(file: File, version: Int) {
        val schema = JSONObject(File(schemaDir(), "$version.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            raw.version = version
        }
    }

    private fun schemaDir(): File {
        val dir = File("schemas/com.visionstudio.core.database.VisionStudioDatabase")
        check(dir.isDirectory) { "schema directory not found at ${dir.absolutePath}" }
        return dir
    }
}
