package dev.drosh.ime.keyboard.snippets

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import dev.drosh.ime.latin.R
import dev.drosh.ime.state.DroshStateClient
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.drosh.ime.keyboard.KeyboardActionListener
import dev.drosh.ime.keyboard.internal.keyboard_parser.floris.KeyCode
import dev.drosh.ime.latin.common.Constants
import dev.drosh.ime.latin.settings.Settings
import dev.drosh.ime.latin.utils.ResourceUtils

/**
 * Snippet list replacing the key grid, mirroring ClipboardHistoryView.
 *
 * Rows come from Drosh's `~/.drosh/snippets.json` through the command
 * provider. Tap inserts the command at the cursor; the edit and delete
 * buttons modify the file through the same provider, and the add button
 * creates a new entry.
 *
 * Shown and hidden by KeyboardSwitcher like the clipboard view. Closing
 * (header X or the toolbar key again) toggles the layout back via the
 * state machine, so this view never manages keyboard modes itself.
 */
class SnippetsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    private lateinit var keyboardActionListener: KeyboardActionListener
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = SnippetAdapter()
    private val client = DroshStateClient(context)
    private var observer: android.database.ContentObserver? = null
    private var observing = false

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }

    fun startSnippets(actionListener: KeyboardActionListener) {
        keyboardActionListener = actionListener
        ensureInflated()
        if (!observing) {
            observer = client.observe { refresh() }
            observing = observer != null
        }
        refresh()
    }

    fun stopSnippets() {
        if (observing) {
            client.stopObserving(observer)
            observer = null
            observing = false
        }
    }

    private var inflated = false

    private fun ensureInflated() {
        if (inflated) return
        inflated = true
        findViewById<ImageButton>(R.id.snippets_add).setOnClickListener { showEditDialog(null) }
        findViewById<ImageButton>(R.id.snippets_close).setOnClickListener { close() }
        emptyView = findViewById(R.id.snippets_empty_view)
        list = findViewById<RecyclerView>(R.id.snippets_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SnippetsView.adapter
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
    }

    private fun refresh() {
        Thread {
            val items = client.querySnippets()
            post {
                adapter.submit(items)
                val empty = items.isEmpty()
                emptyView.visibility = if (empty) View.VISIBLE else View.GONE
                list.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }.start()
    }

    private fun close() {
        keyboardActionListener.onCodeInput(
            KeyCode.SNIPPETS,
            Constants.NOT_A_COORDINATE,
            Constants.NOT_A_COORDINATE,
            false,
        )
    }

    private fun showEditDialog(existing: DroshStateClient.Snippet?) {
        val view = LayoutInflater.from(context).inflate(R.layout.snippet_edit_dialog, null)
        val aliasField = view.findViewById<EditText>(R.id.snippet_edit_alias)
        val commandField = view.findViewById<EditText>(R.id.snippet_edit_command)
        if (existing != null) {
            aliasField.setText(existing.alias)
            aliasField.isEnabled = false
            commandField.setText(existing.command)
        }
        showImeDialog(
            AlertDialog.Builder(context)
                .setTitle(if (existing == null) R.string.snippet_add_title else R.string.snippet_edit_title)
                .setView(view)
                .setPositiveButton(R.string.snippet_save) { _, _ ->
                    val alias = aliasField.text.toString().trim()
                    val command = commandField.text.toString().trim()
                    if (alias.isEmpty() || command.isEmpty()) return@setPositiveButton
                    Thread {
                        val ok = if (existing == null) {
                            client.addSnippet(alias, command)
                        } else {
                            client.updateSnippet(alias, command)
                        }
                        if (ok) post { refresh() }
                    }.start()
                }
                .setNegativeButton(R.string.snippet_cancel, null),
        )
    }

    private fun showDeleteDialog(snippet: DroshStateClient.Snippet) {
        showImeDialog(
            AlertDialog.Builder(context)
                .setTitle(R.string.snippet_delete_title)
                .setMessage(snippet.alias)
                .setPositiveButton(R.string.snippet_delete) { _, _ ->
                    Thread {
                        if (client.deleteSnippet(snippet.alias)) post { refresh() }
                    }.start()
                }
                .setNegativeButton(R.string.snippet_cancel, null),
        )
    }

    /**
     * Shows a dialog built from the view context.
     *
     * An InputMethodService is not an Activity, so its views have no window
     * token. A plain `AlertDialog.show()` therefore fails with
     * BadTokenException "token null is not valid". The token of the attached
     * IME window has to be handed over explicitly and the window typed as an
     * attached dialog. This mirrors LatinIME.showInputPickerDialog().
     */
    private fun showImeDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        val window = dialog.window ?: return
        val params = window.attributes
        params.token = rootView?.windowToken
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        window.attributes = params
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.show()
    }

    private inner class SnippetAdapter : RecyclerView.Adapter<SnippetHolder>() {
        private var items: List<DroshStateClient.Snippet> = emptyList()

        fun submit(next: List<DroshStateClient.Snippet>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnippetHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.snippet_row, parent, false)
            return SnippetHolder(view)
        }

        override fun onBindViewHolder(holder: SnippetHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class SnippetHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val aliasView: TextView = view.findViewById(R.id.snippet_alias)
        private val commandView: TextView = view.findViewById(R.id.snippet_command)

        fun bind(snippet: DroshStateClient.Snippet) {
            aliasView.text = snippet.alias
            commandView.text = snippet.command
            itemView.setOnClickListener {
                keyboardActionListener.onTextInput(snippet.command)
            }
            itemView.findViewById<View>(R.id.snippet_edit).setOnClickListener {
                showEditDialog(snippet)
            }
            itemView.findViewById<View>(R.id.snippet_delete).setOnClickListener {
                showDeleteDialog(snippet)
            }
        }
    }
}
