package dev.drosh.ime.state

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Reads and writes Drosh terminal state through its command provider.
 *
 * Drosh serves `content://dev.drosh.state/command` (one row: status,
 * activity, tint, …) and `content://dev.drosh.state/snippets` (one row per
 * snippet: alias, command), guarded by a signature-level permission — which
 * is why both apps share one signing key.
 *
 * Everything here is best-effort and synchronous; callers must stay off the
 * main thread. Unreachable provider, missing columns, and blank rows all
 * read as empty, and every write reports success or failure instead of
 * throwing. The UI shows an empty list rather than an error, since an IME
 * must never depend on another app being alive.
 *
 * Failures are not silent though: the last one is kept in [lastError] so the
 * snippet panel can explain *why* it is empty, and so the About screen's
 * connection test has something to show.
 */
class DroshStateClient(context: Context) {

    data class Snippet(val alias: String, val command: String)

    companion object {
        private const val TAG = "DroshState"
        private const val AUTHORITY = "dev.drosh.state"
        private const val BASE_URI_STRING = "content://$AUTHORITY"
        const val SNIPPETS_URI_STRING = "$BASE_URI_STRING/snippets"
        private const val COMMAND_URI_STRING = "$BASE_URI_STRING/command"
        private const val COLUMN_ALIAS = "alias"
        private const val COLUMN_COMMAND = "command"

        private val SNIPPETS_URI: Uri = Uri.parse(SNIPPETS_URI_STRING)
        private val COMMAND_URI: Uri = Uri.parse(COMMAND_URI_STRING)
        private val BASE_URI: Uri = Uri.parse(BASE_URI_STRING)
    }

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * Last failure reason, or null when the last call succeeded. Set to a
     * human-readable string (exception class + message) so an empty snippet
     * list can tell "Drosh has none yet" from "Drosh is unreachable".
     */
    @Volatile
    var lastError: String? = null
        private set

    fun querySnippets(): List<Snippet> {
        return runCatching {
            resolver.query(SNIPPETS_URI, null, null, null, null)?.use { cursor ->
                val aliasIndex = cursor.getColumnIndex(COLUMN_ALIAS)
                val commandIndex = cursor.getColumnIndex(COLUMN_COMMAND)
                if (aliasIndex < 0 || commandIndex < 0) {
                    lastError = "snippet columns missing"
                    return emptyList()
                }
                buildList {
                    while (cursor.moveToNext()) {
                        val alias = cursor.getString(aliasIndex).orEmpty()
                        val command = cursor.getString(commandIndex).orEmpty()
                        if (alias.isNotBlank() && command.isNotBlank()) {
                            add(Snippet(alias, command))
                        }
                    }
                }
            } ?: emptyList()
        }.onSuccess { lastError = null }.getOrElse {
            lastError = describe(it, "snippet query")
            Log.w(TAG, "snippet query failed: ${it.message}")
            emptyList()
        }
    }

    /**
     * The provider's single command-state row as key/value pairs, or null
     * when the provider cannot be reached at all. An empty map means the
     * provider answered but has nothing published (Drosh started the
     * process, but its terminal service never published state).
     */
    fun queryCommand(): Map<String, String>? {
        return runCatching {
            resolver.query(COMMAND_URI, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return emptyMap()
                val columns = cursor.columnNames
                buildMap {
                    for (column in columns) {
                        put(column, cursor.getString(cursor.getColumnIndex(column)).orEmpty())
                    }
                }
            } ?: emptyMap()
        }.onSuccess { lastError = null }.getOrElse {
            lastError = describe(it, "command query")
            Log.w(TAG, "command query failed: ${it.message}")
            null
        }
    }

    /** False when the alias exists (update instead), is blank, or unreachable. */
    fun addSnippet(alias: String, command: String): Boolean {
        if (alias.isBlank() || command.isBlank()) return false
        return runCatching {
            val values = ContentValues(2).apply {
                put(COLUMN_ALIAS, alias)
                put(COLUMN_COMMAND, command)
            }
            resolver.insert(SNIPPETS_URI, values) != null
        }.onSuccess { ok -> if (ok) lastError = null }.getOrElse {
            lastError = describe(it, "snippet insert")
            Log.w(TAG, "snippet insert failed: ${it.message}")
            false
        }
    }

    fun updateSnippet(alias: String, command: String): Boolean {
        if (alias.isBlank() || command.isBlank()) return false
        return runCatching {
            val values = ContentValues(1).apply {
                put(COLUMN_COMMAND, command)
            }
            resolver.update(SNIPPETS_URI, values, "$COLUMN_ALIAS = ?", arrayOf(alias)) > 0
        }.onSuccess { ok -> if (ok) lastError = null }.getOrElse {
            lastError = describe(it, "snippet update")
            Log.w(TAG, "snippet update failed: ${it.message}")
            false
        }
    }

    fun deleteSnippet(alias: String): Boolean {
        if (alias.isBlank()) return false
        return runCatching {
            resolver.delete(SNIPPETS_URI, "$COLUMN_ALIAS = ?", arrayOf(alias)) > 0
        }.onSuccess { ok -> if (ok) lastError = null }.getOrElse {
            lastError = describe(it, "snippet delete")
            Log.w(TAG, "snippet delete failed: ${it.message}")
            false
        }
    }

    /**
     * Observes both provider paths through one registration. [onChange] runs
     * on the main thread. Returns false when not observable.
     */
    fun observe(onChange: () -> Unit): ContentObserver? {
        return runCatching {
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChange()
            }.also { resolver.registerContentObserver(BASE_URI, true, it) }
        }.onSuccess { lastError = null }.getOrElse {
            lastError = describe(it, "observe")
            Log.w(TAG, "cannot observe Drosh state: ${it.message}")
            null
        }
    }

    fun stopObserving(observer: ContentObserver?) {
        observer?.let { runCatching { resolver.unregisterContentObserver(it) } }
    }

    /** Short, human-readable failure text for [lastError] and the test screen. */
    private fun describe(error: Throwable, what: String): String =
        "$what failed: ${error.javaClass.simpleName}: ${error.message ?: "no message"}"
}
