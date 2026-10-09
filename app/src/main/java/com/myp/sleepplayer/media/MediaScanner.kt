package com.myp.sleepplayer.media

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Reads either a normal filesystem tree or a SAF tree into the same media model. */
internal class MediaScanner(
    private val contentResolver: ContentResolver,
    private val cacheDirectory: File
) {
    fun scanFileDirectory(directory: File): List<MediaEntry> {
        if (Thread.currentThread().isInterrupted) return emptyList()
        val children = try {
            directory.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val subtitles = children
            .filter { it.isFile && isSubtitleFile(it.name) }
            .mapNotNull { subtitle -> buildSubtitleFile(subtitle.name, Uri.fromFile(subtitle)) }
        val entries = children
            .filter { it.isFile && isSupportedMedia(it.name) }
            .map { media ->
                MediaEntry(
                    media.name,
                    Uri.fromFile(media),
                    mediaType(media.name),
                    matchSubtitles(media.name, subtitles)
                )
            }
        return entries + children
            .filter { it.isDirectory && it.canRead() }
            .flatMap { child ->
                if (Thread.currentThread().isInterrupted) emptyList()
                else scanFileDirectory(child)
            }
    }

    fun scanDocumentDirectory(directory: DocumentFile): List<MediaEntry> {
        if (Thread.currentThread().isInterrupted) return emptyList()
        val children = directory.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
        val subtitles = children
            .filter { it.isFile && isSubtitleFile(it.name) }
            .mapNotNull { file ->
                file.name?.let { name -> buildSubtitleFile(name, file.uri) }
            }
        val entries = children
            .filter { it.isFile && isSupportedMedia(it.name) }
            .mapNotNull { media ->
                val name = media.name ?: return@mapNotNull null
                MediaEntry(name, media.uri, mediaType(name), matchSubtitles(name, subtitles))
            }
        return entries + children
            .filter { it.isDirectory }
            .flatMap { child ->
                if (Thread.currentThread().isInterrupted) emptyList()
                else scanDocumentDirectory(child)
            }
    }

    private fun buildSubtitleFile(name: String, uri: Uri): SubtitleFile? {
        if (isExtension(name, "vtt")) return SubtitleFile(name, uri)
        if (!isExtension(name, "lrc")) return null
        return convertLrcSubtitle(name, uri)
    }

    private fun convertLrcSubtitle(name: String, sourceUri: Uri): SubtitleFile? {
        val lines = try {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readLines()
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val cues = SubtitleParser.parseLrc(lines)
        if (cues.isEmpty()) return null
        val vtt = SubtitleParser.toWebVtt(cues)
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) return null
        val cacheName = "lrc-${Integer.toUnsignedString(sourceUri.toString().hashCode())}.vtt"
        val cacheFile = File(cacheDirectory, cacheName)
        return try {
            cacheFile.writeText(vtt, StandardCharsets.UTF_8)
            SubtitleFile(name, Uri.fromFile(cacheFile))
        } catch (_: Exception) {
            null
        }
    }

    private fun matchSubtitles(videoName: String, subtitles: List<SubtitleFile>): List<SubtitleFile> {
        val stem = mediaStem(videoName)
        return subtitles.filter { subtitle ->
            val subtitleStem = mediaStem(subtitle.name)
            subtitleStem == stem || subtitleStem.startsWith("$stem.")
        }.sortedWith(compareBy<SubtitleFile> { !it.isDefaultFor(videoName) }.thenBy { it.name })
    }
}
