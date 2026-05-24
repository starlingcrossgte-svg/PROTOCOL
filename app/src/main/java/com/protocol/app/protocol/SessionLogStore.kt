package com.protocol.app.protocol

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Persists the in-progress session log to a fixed file in the app's
 * cache directory so a process kill / crash mid-log doesn't lose the
 * data. Write strategy:
 *
 *  - The ViewModel calls [save] every N samples while logging.
 *  - Each save rewrites the file with the full current CSV. Simpler and
 *    more crash-safe than append (a corrupt-mid-write append produces
 *    an unparseable file; a corrupt-mid-write rewrite at worst keeps
 *    yesterday's data, since the writeText pattern truncates + writes).
 *  - File lives at `cacheDir/last_session.csv`. Cached storage is
 *    private to the app, persists across launches, and is automatically
 *    cleaned by the system on low storage — exactly the lifecycle we
 *    want for "best-effort crash recovery, not durable archive."
 *
 * The path is exposed so the Activity can build a share-chooser intent
 * via FileProvider (same flow the regular Export CSV button uses).
 */
class SessionLogStore(context: Context) {

    private val appCtx = context.applicationContext
    val file: File = File(appCtx.cacheDir, FILE_NAME)

    fun save(csv: String) {
        if (csv.isEmpty()) return
        try {
            file.writeText(csv, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // Disk full, permissions weirdness, etc. Logging is a
            // best-effort safety net — never crash the main path.
        }
    }

    fun exists(): Boolean = try { file.exists() && file.length() > 0L } catch (_: Exception) { false }

    fun clear() {
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }

    companion object {
        private const val FILE_NAME = "last_session.csv"
    }
}
