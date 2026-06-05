package com.protocol.app.protocol

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Writes CSV logs into a user-chosen SAF tree (folder) with an
 * auto-incrementing numeric suffix: "<base>1.csv", "<base>2.csv", …
 *
 * Pure framework ([DocumentsContract]) — no extra gradle dependency. The next
 * free number is found by scanning the folder's existing children, so the
 * sequence never collides with files already there (or with a provider that
 * would otherwise append its own " (1)" suffix on a name clash).
 *
 * The chosen folder URI + the two base names are persisted in [AppSettings];
 * the Activity owns the folder-picker and calls in here on auto-save.
 */
object CsvDestination {

    /**
     * Create "<baseName><N>.csv" in [treeUri] and write [content] to it.
     * Returns the created file's display name, or null on any failure.
     */
    fun writeEnumerated(context: Context, treeUri: Uri, baseName: String, content: String): String? {
        if (content.isEmpty()) return null
        val resolver = context.contentResolver
        val safeBase = sanitize(baseName).ifBlank { "log" }
        return try {
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
            val next = nextIndex(context, treeUri, treeId, safeBase)
            val fileName = "$safeBase$next.csv"
            val target = DocumentsContract.createDocument(resolver, parentDoc, "text/csv", fileName)
                ?: return null
            resolver.openOutputStream(target)?.use { out: OutputStream ->
                out.write(content.toByteArray(StandardCharsets.UTF_8))
            } ?: return null
            fileName
        } catch (_: Exception) {
            null
        }
    }

    /** Human-readable label for the chosen folder (the document id tail, e.g.
     *  "Download/Protocol"). Falls back to the raw URI on any oddity. */
    fun prettyFolderLabel(treeUri: Uri): String = try {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        id.substringAfter(':', id)
    } catch (_: Exception) {
        treeUri.lastPathSegment ?: treeUri.toString()
    }

    private fun nextIndex(context: Context, treeUri: Uri, treeId: String, base: String): Int {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val pattern = Regex("^" + Regex.escape(base) + "(\\d+)\\.csv$", RegexOption.IGNORE_CASE)
        var max = 0
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val n = pattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (n > max) max = n
                }
            }
        } catch (_: Exception) {
            // Query failed (provider quirk) — fall back to starting at 1.
        }
        return max + 1
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
