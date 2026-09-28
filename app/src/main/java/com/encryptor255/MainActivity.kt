package com.encryptor255

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Mode { TEXT, FILE }
    private enum class Action { ENCRYPT, DECRYPT }

    private lateinit var tabText: TextView
    private lateinit var tabFile: TextView
    private lateinit var containerText: View
    private lateinit var containerFile: View
    private lateinit var passwordInput: EditText
    private lateinit var textInput: EditText
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var fileName: TextView
    private lateinit var fileMeta: TextView
    private lateinit var btnPickFile: Button

    private var mode: Mode = Mode.TEXT
    private var action: Action = Action.ENCRYPT
    private var pickedUri: Uri? = null

    private var pendingOpen: ((Uri) -> Unit)? = null
    private var pendingCreate: ((Uri) -> Unit)? = null

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingOpen?.invoke(it) }
    }
    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { pendingCreate?.invoke(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        tabText = findViewById(R.id.tabText)
        tabFile = findViewById(R.id.tabFile)
        containerText = findViewById(R.id.containerText)
        containerFile = findViewById(R.id.containerFile)
        passwordInput = findViewById(R.id.passwordInput)
        textInput = findViewById(R.id.textInput)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        fileName = findViewById(R.id.fileName)
        fileMeta = findViewById(R.id.fileMeta)
        btnPickFile = findViewById(R.id.btnPickFile)

        tabText.setOnClickListener { switchMode(Mode.TEXT) }
        tabFile.setOnClickListener { switchMode(Mode.FILE) }

        btnPickFile.setOnClickListener { pickFile() }
        findViewById<Button>(R.id.btnEncrypt).setOnClickListener {
            action = Action.ENCRYPT
            dispatch()
        }
        findViewById<Button>(R.id.btnDecrypt).setOnClickListener {
            action = Action.DECRYPT
            dispatch()
        }

        // Pulso sutil en el badge offline
        val badge = findViewById<View>(R.id.badgeOffline)
        val pulse = ObjectAnimator.ofFloat(badge, "alpha", 1f, 0.65f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulse.start()
    }

    private fun switchMode(m: Mode) {
        if (mode == m) return
        mode = m
        containerText.visibility = if (m == Mode.TEXT) View.VISIBLE else View.GONE
        containerFile.visibility = if (m == Mode.FILE) View.VISIBLE else View.GONE

        val active = ContextCompat.getDrawable(this, R.drawable.bg_segment_selected)
        tabText.background = if (m == Mode.TEXT) active else null
        tabFile.background = if (m == Mode.FILE) active else null
        tabText.setTextColor(ContextCompat.getColor(this,
            if (m == Mode.TEXT) R.color.text_primary else R.color.text_secondary))
        tabFile.setTextColor(ContextCompat.getColor(this,
            if (m == Mode.FILE) R.color.text_primary else R.color.text_secondary))
    }

    private fun dispatch() {
        when (mode) {
            Mode.TEXT -> if (action == Action.ENCRYPT) encryptText() else decryptText()
            Mode.FILE -> if (action == Action.ENCRYPT) pickAndEncrypt() else pickAndDecrypt()
        }
    }

    // ───────────── TEXTO ─────────────

    private fun encryptText() {
        val pwd = readPassword() ?: return
        val text = textInput.text.toString()
        if (text.isEmpty()) { toast("Escribe un texto"); return }
        runJob(success = { textInput.setText(it); setStatus("Texto cifrado ✓", true) }) {
            val out = ByteArrayOutputStream()
            CryptoEngine.encrypt(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
                out, pwd.copyOf(), true)
            "E255T:" + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun decryptText() {
        val pwd = readPassword() ?: return
        var text = textInput.text.toString().trim()
        if (text.startsWith("E255T:")) text = text.substring(6)
        if (text.isEmpty()) { toast("Pega el texto cifrado"); return }
        runJob(success = { textInput.setText(it); setStatus("Texto descifrado ✓", true) }) {
            val bytes = Base64.decode(text, Base64.DEFAULT)
            val out = ByteArrayOutputStream()
            CryptoEngine.decrypt(ByteArrayInputStream(bytes), out, pwd.copyOf())
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    // ───────────── ARCHIVO ─────────────

    private fun pickFile() {
        if (action == Action.ENCRYPT) {
            openDoc.launch(arrayOf("*/*"))
            pendingOpen = { uri -> showPicked(uri) }
        } else {
            openDoc.launch(arrayOf("*/*"))
            pendingOpen = { uri -> showPicked(uri) }
        }
    }

    private fun showPicked(uri: Uri) {
        pickedUri = uri
        val name = queryName(uri) ?: "archivo"
        val size = querySize(uri)
        fileName.text = name
        fileMeta.text = "%.2f KB".format(Locale.US, size / 1024.0)
        setStatus("Archivo listo: $name", true)
    }

    private fun pickAndEncrypt() {
        val pwd = readPassword() ?: return
        val uri = pickedUri
        if (uri == null) { toast("Selecciona un archivo primero"); return }
        val name = (queryName(uri) ?: "archivo") + ".e255"
        pendingCreate = { dst ->
            runJob(success = { setStatus("Archivo cifrado ✓", true) }) {
                encryptFile(uri, dst, pwd)
            }
        }
        createDoc.launch(name)
    }

    private fun pickAndDecrypt() {
        val pwd = readPassword() ?: return
        val uri = pickedUri
        if (uri == null) { toast("Selecciona un archivo .e255 primero"); return }
        val original = (queryName(uri) ?: "archivo.e255").removeSuffix(".e255")
        pendingCreate = { dst ->
            runJob(success = { setStatus("Archivo descifrado ✓", true) }) {
                decryptFile(uri, dst, pwd)
            }
        }
        createDoc.launch(original)
    }

    private suspend fun encryptFile(src: Uri, dst: Uri, pwd: CharArray): String =
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(src)!!.use { ins ->
                contentResolver.openOutputStream(dst, "wt")!!.use { outs ->
                    CryptoEngine.encrypt(ins, outs, pwd.copyOf(), true)
                }
            }
            "ok"
        }

    private suspend fun decryptFile(src: Uri, dst: Uri, pwd: CharArray): String =
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(src)!!.use { ins ->
                contentResolver.openOutputStream(dst, "wt")!!.use { outs ->
                    CryptoEngine.decrypt(ins, outs, pwd.copyOf())
                }
            }
            "ok"
        }

    // ───────────── HELPERS ─────────────

    private fun readPassword(): CharArray? {
        val p = passwordInput.text.toString()
        if (p.isEmpty()) { toast("Introduce una contraseña"); return null }
        return p.toCharArray()
    }

    private fun runJob(success: (String) -> Unit, block: suspend () -> String) {
        lifecycleScope.launch {
            setStatus("Procesando…", false)
            try {
                val msg = withContext(Dispatchers.IO) { block() }
                success(msg)
            } catch (t: Throwable) {
                val m = "Error: ${t.message ?: t.javaClass.simpleName}"
                setStatus(m, false, error = true)
                toast(m)
            }
        }
    }

    private fun setStatus(msg: String, ok: Boolean, error: Boolean = false) {
        statusText.text = msg
        val color = when {
            error -> R.color.error
            ok -> R.color.success
            else -> R.color.cyan_500
        }
        statusDot.background.setTint(ContextCompat.getColor(this, color))

        // Pequeña animación de entrada
        statusDot.alpha = 0f
        statusDot.animate().alpha(1f).setDuration(400).start()
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun querySize(uri: Uri): Long =
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
