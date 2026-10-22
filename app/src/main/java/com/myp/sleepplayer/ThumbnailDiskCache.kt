package com.myp.sleepplayer

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min

/** Optional cache: all source/provider and cache IO belongs on a background worker. */
internal class ThumbnailDiskCache(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    // Context.cacheDir may create the directory. Resolve it only on first worker access.
    private val directory by lazy { File(appContext.cacheDir, "thumbnail-v5") }
    private var indexed = false
    private val entries = mutableMapOf<String, Entry>()
    private var totalBytes = 0L

    internal data class Key internal constructor(
        val id: String,
        val width: Int,
        val height: Int,
        internal val uri: Uri,
        internal val size: Long,
        internal val modified: Long
    )

    private data class Revision(val size: Long, val modified: Long)
    private data class Entry(val file: File, val bytes: Long, var lastAccess: Long)

    /** Obtain immediately before read; unknown revisions deliberately disable persistent reuse. */
    @WorkerThread
    fun prepare(uri: String, width: Int, height: Int): Key? {
        if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE) return null
        if (Thread.currentThread().isInterrupted) return null
        return try {
            val source = Uri.parse(uri).normalizeScheme()
            val revision = revision(source) ?: return null
            val identity = "$STRATEGY_VERSION\n${source}\n${revision.size}\n${revision.modified}\n${width}x$height"
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            val hex = CharArray(digest.size * 2)
            digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                hex[index * 2] = HEX[value ushr 4]
                hex[index * 2 + 1] = HEX[value and 0xf]
            }
            Key(String(hex), width, height, source, revision.size, revision.modified)
        } catch (_: Exception) {
            null
        }
    }

    @WorkerThread
    @Synchronized
    fun read(key: Key): Bitmap? {
        if (Thread.currentThread().isInterrupted) return null
        return try {
            ensureIndex()
            val entry = entries[key.id] ?: return null
            if (!entry.file.isFile || entry.file.length() !in 1..MAX_ENTRY_BYTES) {
                discard(key.id)
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(entry.file.absolutePath, bounds)
            // Our writer never stores a bitmap larger than its target. Check headers before
            // allocation, including files left corrupt by storage failure or external cleanup.
            if (bounds.outWidth !in 1..key.width || bounds.outHeight !in 1..key.height) {
                discard(key.id)
                return null
            }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(entry.file.absolutePath, options)
            if (bitmap == null) {
                discard(key.id)
                return null
            }
            entry.lastAccess = System.currentTimeMillis()
            entry.file.setLastModified(entry.lastAccess)
            bitmap
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Recheck after decoding so a file changed during extraction does not populate the old key. */
    @WorkerThread
    @Synchronized
    fun write(key: Key, bitmap: Bitmap) {
        if (Thread.currentThread().isInterrupted || bitmap.isRecycled) return
        var temporary: File? = null
        var scaled: Bitmap? = null
        try {
            if (revision(key.uri) != Revision(key.size, key.modified)) return
            ensureIndex()
            if (!directory.isDirectory && !directory.mkdirs()) return
            val scale = min(1.0, min(key.width.toDouble() / bitmap.width, key.height.toDouble() / bitmap.height))
            val image = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { scaled = it }
            } else {
                bitmap
            }
            temporary = File.createTempFile("thumb-", ".tmp", directory)
            FileOutputStream(temporary).use { output ->
                if (!image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return
            }
            val bytes = temporary.length()
            if (bytes !in 1..MAX_ENTRY_BYTES || Thread.currentThread().isInterrupted) return
            val destination = File(directory, "${key.id}.jpg")
            // Both paths are in the same private directory: Android rename replaces the
            // destination atomically, so readers cannot observe an incomplete JPEG.
            if (!temporary.renameTo(destination)) return
            val now = System.currentTimeMillis()
            destination.setLastModified(now)
            totalBytes -= entries.remove(key.id)?.bytes ?: 0L
            entries[key.id] = Entry(destination, bytes, now)
            totalBytes += bytes
            trim()
        } catch (_: Exception) {
            // Disk full, revoked access and provider failures remain normal cache misses.
        } catch (_: OutOfMemoryError) {
            // A failed optional cache write must not discard the caller's decoded bitmap.
        } finally {
            try {
                temporary?.delete()
            } catch (_: Exception) {
                // A cleanup failure must not turn a successful decode into a failed request.
            }
            scaled?.takeIf { it !== bitmap }?.recycle()
        }
    }

    private fun revision(uri: Uri): Revision? {
        if (Thread.currentThread().isInterrupted) return null
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> {
                val file = File(uri.path ?: return null)
                if (!file.isFile || !file.canRead()) return null
                val size = file.length()
                val modified = file.lastModified()
                if (size > 0L && modified > 0L) Revision(size, modified) else null
            }
            ContentResolver.SCHEME_CONTENT -> {
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )
                val sourceRevision = resolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val sizeIndex = cursor.getColumnIndex(projection[0])
                    val modifiedIndex = cursor.getColumnIndex(projection[1])
                    if (sizeIndex < 0 || modifiedIndex < 0 || cursor.isNull(sizeIndex) || cursor.isNull(modifiedIndex)) {
                        return@use null
                    }
                    val size = cursor.getLong(sizeIndex)
                    val modified = cursor.getLong(modifiedIndex)
                    if (size > 0L && modified > 0L) Revision(size, modified) else null
                } ?: return null
                // Some providers expose metadata without granting access to the actual file.
                // Opening a descriptor verifies read access without scanning video contents.
                resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    val actualSize = descriptor.statSize
                    if (actualSize >= 0L && actualSize != sourceRevision.size) null else sourceRevision
                }
            }
            else -> null
        }
    }

    /** Index once per cache instance; normal writes update it instead of rescanning each time. */
    private fun ensureIndex() {
        if (indexed) return
        // A provider/storage exception may have interrupted an earlier indexing attempt.
        entries.clear()
        totalBytes = 0L
        val files = directory.listFiles().orEmpty()
        val expiredBefore = System.currentTimeMillis() - STALE_TEMP_AGE_MS
        files.forEach { file ->
            val name = file.name
            if (name.startsWith("thumb-") && name.endsWith(".tmp")) {
                if (file.lastModified() < expiredBefore) file.delete()
            } else if (name.length == 68 && name.endsWith(".jpg") && name.take(64).all { it in HEX }) {
                val bytes = file.length()
                if (!file.isFile || bytes !in 1..MAX_ENTRY_BYTES) {
                    file.delete()
                } else {
                    entries[name.dropLast(4)] = Entry(file, bytes, file.lastModified())
                    totalBytes += bytes
                }
            }
        }
        indexed = true
        trim()
    }

    private fun trim() {
        if (totalBytes <= MAX_BYTES && entries.size <= MAX_ENTRIES) return
        for ((id, entry) in entries.entries.sortedBy { it.value.lastAccess }) {
            if (totalBytes <= MAX_BYTES && entries.size <= MAX_ENTRIES) break
            if (entry.file.delete() || !entry.file.exists()) {
                entries.remove(id)
                totalBytes -= entry.bytes
            }
        }
    }

    private fun discard(id: String) {
        val entry = entries[id] ?: return
        if (entry.file.delete() || !entry.file.exists()) {
            entries.remove(id)
            totalBytes -= entry.bytes
        }
    }

    private companion object {
        const val STRATEGY_VERSION = "bounded-frame-v7-midpoint"
        const val MAX_BYTES = 64L * 1024L * 1024L
        const val MAX_ENTRIES = 2048
        const val MAX_ENTRY_BYTES = 2L * 1024L * 1024L
        const val MAX_EDGE = 1024
        const val JPEG_QUALITY = 85
        const val STALE_TEMP_AGE_MS = 24L * 60L * 60L * 1000L
        const val HEX = "0123456789abcdef"
    }
}
