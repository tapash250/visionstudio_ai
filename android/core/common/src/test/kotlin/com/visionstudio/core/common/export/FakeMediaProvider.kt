package com.visionstudio.core.common.export

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Stand-in for the system MediaProvider, registered under the `media` authority. It models the
 * parts of MediaStore the exporter relies on: inserted rows get a content URI, a write goes to a
 * real file, and every insert/update/delete is recorded so tests can assert the exact sequence
 * (for example IS_PENDING=1 on insert, then 0 on publish).
 */
class FakeMediaProvider : ContentProvider() {

    class Row(val id: Long, val collection: Uri, val onInsert: ContentValues, val file: File) {
        val updates = mutableListOf<ContentValues>()
        var deleted = false
        val current: ContentValues get() = ContentValues(onInsert).also { merged -> updates.forEach(merged::putAll) }
    }

    val rows = mutableMapOf<Long, Row>()
    var insertReturnsNull = false
    var failOpen = false
    private var nextId = 1L
    private lateinit var storage: File

    override fun onCreate(): Boolean {
        storage = File(requireNotNull(context).cacheDir, "fake-media-${System.nanoTime()}").apply { mkdirs() }
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (insertReturnsNull) return null
        val id = nextId++
        val name = values?.getAsString("_display_name") ?: "file-$id"
        rows[id] = Row(id, uri, ContentValues(values), File(storage, "$id-$name"))
        return ContentUris.withAppendedId(uri, id)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failOpen) throw FileNotFoundException("simulated: storage unavailable")
        val row = rows[ContentUris.parseId(uri)] ?: throw FileNotFoundException("no row for $uri")
        val flags = if ('w' in mode) {
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(row.file, flags)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        row.updates += ContentValues(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        row.deleted = true
        row.file.delete()
        return 1
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
}
