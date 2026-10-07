package dev.drosh.ime.keyboard.snippets

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Reads and writes Drosh terminal snippets through its command provider.
 *
 * Drosh serves `content://dev.drosh.state/snippets`, one row per snippet
 * (`alias`, `command`), guarded by a signature-level permission — which is
 * why both apps share one signing key.
 *
 * Everything here is best-effort. The keyboard is a system-wide IME that
 * runs inside apps knowing nothing about Drosh: unreachable provider,
 * missing columns, and blank rows all read as empty, and every write
 * reports success or failure instead of throwing. The UI shows an empty
 * list rather than an error.
 */
class DroshSnippetsClient(context: Context) {

    data class Snippet(val alias: String, val command: String)

    companion object {
        private const val TAG = "DroshSnippets"
        private const val AUTHORITY = "dev.drosh.state"
        private const val PATH = "snippets"
        private const val URI_STRING = "content://$AUTHORITY/$PATH"
        private const val COLUMN_ALIAS = "alias"
        private const val COLUMN_COMMAND = "command"
        private val URI: Uri = Uri.parse(URI_STRING)
        private val BASE_URI: Uri = Uri.parse("content://$AUTHORITY")
    }

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    private var observer: ContentObserver? = null

    fun query(): List<Snippet> {
        return runCatching {
            resolver.query(URI, null, null, null, null)?.use { cursor ->
                val aliasIndex = cursor.getColumnIndex(COLUMN_ALIAS)
                val commandIndex = cursor.getColumnIndex(COLUMN_COMMAND)
                if (aliasIndex < 0 || commandIndex < 0) return emptyList()
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
        }.getOrElse {
            Log.w(TAG, "snippet query failed: ${it.message}")
            emptyList()
        }
    }

    /** False when the alias exists (update instead), is blank, or unreachable. */
    fun add(alias: String, command: String): Boolean {
        if (alias.isBlank() || command.isBlank()) return false
        return runCatching {
            val values = ContentValues(2).apply {
                put(COLUMN_ALIAS, alias)
                put(COLUMN_COMMAND, command)
            }
            resolver.insert(URI, values) != null
        }.getOrElse {
            Log.w(TAG, "snippet insert failed: ${it.message}")
            false
        }
    }

    fun update(alias: String, command: String): Boolean {
        if (alias.isBlank() || command.isBlank()) return false
        return runCatching {
            val values = ContentValues(1).apply {
                put(COLUMN_COMMAND, command)
            }
            resolver.update(URI, values, "$COLUMN_ALIAS = ?", arrayOf(alias)) > 0
        }.getOrElse {
            Log.w(TAG, "snippet update failed: ${it.message}")
            false
        }
    }

    fun delete(alias: String): Boolean {
        if (alias.isBlank()) return false
        return runCatching {
            resolver.delete(URI, "$COLUMN_ALIAS = ?", arrayOf(alias)) > 0
        }.getOrElse {
            Log.w(TAG, "snippet delete failed: ${it.message}")
            false
        }
    }

    /** [onChange] runs on the main thread. Returns false when not observable. */
    fun observe(onChange: () -> Unit): Boolean {
        if (observer != null) return true
        return runCatching {
            val callback = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChange()
            }
            resolver.registerContentObserver(BASE_URI, true, callback)
            observer = callback
            true
        }.getOrElse {
            Log.w(TAG, "cannot observe snippets: ${it.message}")
            false
        }
    }

    fun stopObserving() {
        observer?.let { runCatching { resolver.unregisterContentObserver(it) } }
        observer = null
    }
}
