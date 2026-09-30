package com.encryptor255

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotesActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var prefs: android.content.SharedPreferences
    private var currentFile: java.io.File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecurityManager.applySecureFlag(this, true)
        setContentView(R.layout.activity_notes)

        prefs = getSharedPreferences("encryptor255_prefs", Context.MODE_PRIVATE)
        listContainer = findViewById(R.id.notesList)
        emptyView = findViewById(R.id.notesEmpty)

        findViewById<ImageButton>(R.id.btnNotesBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnNewNote).setOnClickListener { openEditor(null) }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val notes = NotesStore.list(this)
        emptyView.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE

        notes.forEach { note ->
            val row = layoutInflater.inflate(R.layout.item_note, listContainer, false)
            row.findViewById<TextView>(R.id.noteTitle).text = note.title.ifEmpty {
                getString(R.string.notes_untitled)
            }
            row.findViewById<TextView>(R.id.noteDate).text =
                NotesStore.formatDate(note.timestamp)
            row.setOnClickListener { openEditor(note.file) }
            row.setOnLongClickListener {
                confirmDelete(note.file)
                true
            }
            listContainer.addView(row)
        }
    }

    private fun openEditor(file: java.io.File?) {
        val pwd = promptPassword() ?: return
        if (file == null) {
            showEditor(null, "", pwd)
            return
        }
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    NotesStore.load(this@NotesActivity, file, pwd.copyOf())
                }
                showEditor(file, content, pwd)
            } catch (t: Throwable) {
                toast("Contraseña incorrecta o nota corrupta")
            }
        }
    }

    private fun showEditor(file: java.io.File?, initialContent: String, pwd: CharArray) {
        setContentView(R.layout.activity_note_editor)
        currentFile = file

        val titleInput = findViewById<EditText>(R.id.noteTitleInput)
        val contentInput = findViewById<EditText>(R.id.noteContentInput)
        val btnSave = findViewById<Button>(R.id.btnNoteSave)
        val btnDelete = findViewById<Button>(R.id.btnNoteDelete)
        val btnBack = findViewById<ImageButton>(R.id.btnEditorBack)

        if (file != null) {
            val parts = file.nameWithoutExtension.split("__", limit = 2)
            titleInput.setText(parts.getOrNull(1)?.replace("_", " ") ?: "")
            contentInput.setText(initialContent)
            btnDelete.visibility = View.VISIBLE
        }

        btnBack.setOnClickListener { recreate() }
        btnSave.setOnClickListener {
            val title = titleInput.text.toString().ifBlank { getString(R.string.notes_untitled) }
            val content = contentInput.text.toString()
            if (content.isEmpty()) { toast(getString(R.string.notes_error)); return@setOnClickListener }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (file != null) file.delete()
                        NotesStore.save(this@NotesActivity, title, content, pwd.copyOf())
                    }
                    toast(getString(R.string.notes_saved))
                    recreate()
                } catch (t: Throwable) {
                    toast(getString(R.string.notes_error) + ": ${t.message}")
                }
            }
        }
        btnDelete.setOnClickListener {
            if (file != null) {
                NotesStore.delete(file)
                toast(getString(R.string.notes_deleted))
                recreate()
            }
        }
    }

    private fun confirmDelete(file: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.notes_delete)
            .setMessage(R.string.notes_delete_confirm)
            .setPositiveButton(R.string.notes_delete) { _, _ ->
                NotesStore.delete(file)
                toast(getString(R.string.notes_deleted))
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptPassword(): CharArray? {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                          android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val pad = (20 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)

        var result: CharArray? = null
        AlertDialog.Builder(this)
            .setTitle(R.string.label_password)
            .setView(input)
            .setPositiveButton(R.string.notes_save) { _, _ ->
                result = input.text.toString().toCharArray()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()

        val pwd = input.text?.toString()?.takeIf { it.isNotEmpty() }?.toCharArray()
        return if (pwd != null) pwd else {
            // Fallback: si el usuario presionó OK rápido, pedimos igual
            toast(getString(R.string.notes_need_password))
            null
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
