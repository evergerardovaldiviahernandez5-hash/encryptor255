package com.encryptor255

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.PasswordTransformationMethod
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
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
    private lateinit var hashLabel: TextView
    private lateinit var fileName: TextView
    private lateinit var fileMeta: TextView
    private lateinit var strengthBar: View
    private lateinit var strengthLabel: TextView
    private lateinit var btnTogglePwd: ImageButton
    private lateinit var badge: View

    private var pwdVisible = false
    private var mode: Mode = Mode.TEXT
    private var action: Action = Action.ENCRYPT
    private var pickedUri: Uri? = null
    private val pickedUris = mutableListOf<Uri>()
    private var batchMode = false

    private var pendingOpen: ((Uri) -> Unit)? = null
    private var pendingCreate: ((Uri) -> Unit)? = null

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingOpen?.invoke(it) }
    }
    private val openMulti = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        pickedUris.clear()
        pickedUris.addAll(uris)
        pickedUri = uris.first()
        batchMode = uris.size > 1

        val total = uris.size
        fileName.text = if (total == 1) {
            queryName(uris[0]) ?: "archivo"
        } else {
            getString(R.string.batch_count, total)
        }
        val totalSize = uris.sumOf { querySize(it) }
        fileMeta.text = String.format(java.util.Locale.US, "%d archivos · %.2f KB", total, totalSize / 1024.0)
        log("> $total archivos cargados (${totalSize / 1024} KB)")
    }

private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { pendingCreate?.invoke(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        SecurityManager.applySecureFlag(this, true)
        setContentView(R.layout.activity_main)

        tabText = findViewById(R.id.tabText)
        tabFile = findViewById(R.id.tabFile)
        containerText = findViewById(R.id.containerText)
        containerFile = findViewById(R.id.containerFile)
        passwordInput = findViewById(R.id.passwordInput)
        textInput = findViewById(R.id.textInput)
        statusText = findViewById(R.id.statusText)
        hashLabel = findViewById(R.id.hashLabel)
        fileName = findViewById(R.id.fileName)
        fileMeta = findViewById(R.id.fileMeta)
        strengthBar = findViewById(R.id.strengthBar)
        strengthLabel = findViewById(R.id.strengthLabel)
        btnTogglePwd = findViewById(R.id.btnTogglePwd)
        badge = findViewById(R.id.badgeOffline)

        tabText.setOnClickListener { withHaptic { switchMode(Mode.TEXT) } }
        tabFile.setOnClickListener { withHaptic { switchMode(Mode.FILE) } }
        findViewById<Button>(R.id.btnEncrypt).setOnClickListener {
            withHaptic { action = Action.ENCRYPT; dispatch() }
        }
        findViewById<Button>(R.id.btnDecrypt).setOnClickListener {
            withHaptic { action = Action.DECRYPT; dispatch() }
        }
        findViewById<Button>(R.id.btnPickFile).setOnClickListener { withHaptic { pickFile() } }
        findViewById<ImageButton>(R.id.btnCopy).setOnClickListener { withHaptic { copyToClipboard() } }
        findViewById<ImageButton>(R.id.btnShare).setOnClickListener { withHaptic { shareCurrent() } }
        findViewById<ImageButton>(R.id.btnClear).setOnClickListener { withHaptic { clearAll() } }
        findViewById<ImageButton>(R.id.btnGenerate).setOnClickListener { withHaptic { generatePassword() } }
        btnTogglePwd.setOnClickListener { withHaptic { togglePasswordVisibility() } }

        findViewById<android.widget.ImageButton>(R.id.btnSettings).setOnClickListener {
            withHaptic { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
        }

        passwordInput.doAfterTextChanged { editable ->
            refreshStrength(editable?.toString() ?: "")
        }

        ObjectAnimator.ofFloat(badge, "alpha", 1f, 0.55f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        containerText.alpha = 0f
        containerText.translationY = 24f
        containerText.animate().alpha(1f).translationY(0f).setDuration(420).start()

        log("> sistema iniciado")
        log("> aes-256-gcm · pbkdf2-sha512 310k · gzip")
        log("> esperando instrucción_")


        // ═══════════ F4c · Biometría al arrancar ═══════════
        val prefs = getSharedPreferences("encryptor255_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("biometric_enabled", false)) {
            SecurityManager.promptBiometric(
                this,
                onSuccess = { log("> identidad verificada") },
                onError = { msg ->
                    log("! biometría: $msg")
                    toast("Verificación cancelada")
                }
            )
        }    }

    // ───────────── UI HELPERS ─────────────

    private fun withHaptic(block: () -> Unit) {
        window.decorView.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        block()
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

    private fun togglePasswordVisibility() {
        pwdVisible = !pwdVisible
        val sel = passwordInput.selectionEnd
        if (pwdVisible) {
            passwordInput.transformationMethod = null
            btnTogglePwd.setImageResource(R.drawable.ic_eye_off)
            btnTogglePwd.contentDescription = getString(R.string.cd_hide_pwd)
        } else {
            passwordInput.transformationMethod = PasswordTransformationMethod.getInstance()
            btnTogglePwd.setImageResource(R.drawable.ic_eye_on)
            btnTogglePwd.contentDescription = getString(R.string.cd_show_pwd)
        }
        passwordInput.setSelection(sel.coerceAtLeast(0))
    }

    private fun refreshStrength(pwd: String) {
        val s = PasswordUtils.strength(pwd)
        val parentW = (strengthBar.parent as? View)?.width ?: 0
        val target = if (parentW > 0) parentW else resources.displayMetrics.widthPixels - 120

        val colorRes = when (s.score) {
            4 -> R.color.strength_strong
            3 -> R.color.strength_good
            2 -> R.color.strength_fair
            1 -> R.color.strength_weak
            else -> R.color.text_disabled
        }
        strengthBar.background.setTint(ContextCompat.getColor(this, colorRes))
        strengthLabel.text = if (pwd.isEmpty()) "—" else "${s.label} · ${s.entropyBits.toInt()} bits"
        strengthLabel.setTextColor(ContextCompat.getColor(this, colorRes))

        val ratio = when (s.score) {
            4 -> 1.0f
            3 -> 0.75f
            2 -> 0.5f
            1 -> 0.28f
            else -> 0.06f
        }
        val lp = strengthBar.layoutParams
        lp.width = if (pwd.isEmpty()) 0 else (target * ratio).toInt().coerceAtLeast(6)
        strengthBar.layoutParams = lp
    }

    private fun generatePassword() {
        val pwd = PasswordUtils.generate(24, true)
        passwordInput.setText(pwd)
        passwordInput.setSelection(pwd.length)
        if (!pwdVisible) togglePasswordVisibility()
        refreshStrength(pwd)
        log("> clave generada · 24 chars · ${PasswordUtils.strength(pwd).entropyBits.toInt()} bits")
        toast("Contraseña generada")
    }

    // ───────────── DISPATCH ─────────────

    private fun dispatch() {
        when (mode) {
            Mode.TEXT -> if (action == Action.ENCRYPT) encryptText() else decryptText()
            Mode.FILE -> if (action == Action.ENCRYPT) pickAndEncrypt() else pickAndDecrypt()
        }
    }

    private fun encryptText() {
        val pwd = readPassword() ?: return
        val text = textInput.text.toString()
        if (text.isEmpty()) { log("! sin contenido"); toast("Escribe un texto"); return }
        log("> cifrando texto (${text.length} chars)…")
        runJob(
            block = {
                val out = ByteArrayOutputStream()
                CryptoEngine.encrypt(
                    ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
                    out, pwd.copyOf(), true
                )
                "E255T:" + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            },
            onOk = { result ->
                textInput.setText(result)
                val hash = PasswordUtils.shortHash(result)
                hashLabel.text = "sha:$hash"
                log("> cifrado ok · ${result.length} chars · sha256:$hash")
            }
        )
    }

    private fun decryptText() {
        val pwd = readPassword() ?: return
        var text = textInput.text.toString().trim()
        if (text.startsWith("E255T:")) text = text.substring(6)
        if (text.isEmpty()) { log("! sin ciphertext"); toast("Pega el texto cifrado"); return }
        log("> descifrando texto…")
        runJob(
            block = {
                val bytes = Base64.decode(text, Base64.DEFAULT)
                val out = ByteArrayOutputStream()
                CryptoEngine.decrypt(ByteArrayInputStream(bytes), out, pwd.copyOf())
                String(out.toByteArray(), Charsets.UTF_8)
            },
            onOk = { result ->
                textInput.setText(result)
                hashLabel.text = ""
                log("> descifrado ok · ${result.length} chars")
            }
        )
    }

    private fun pickFile() {
        openMulti.launch(arrayOf("*/*"))
    }

    private fun showPicked(uri: Uri) {
        pickedUri = uri
        val name = queryName(uri) ?: "archivo"
        val size = querySize(uri)
        fileName.text = name
        fileMeta.text = String.format(Locale.US, "%d bytes · %.2f KB", size, size / 1024.0)
        log("> archivo cargado: $name ($size bytes)")
    }

    private fun pickAndEncrypt() {
        val pwd = readPassword() ?: return
        val uri = pickedUri
        if (uri == null) { toast("Selecciona un archivo"); return }
        val name = (queryName(uri) ?: "archivo") + ".e255"
        pendingCreate = { dst ->
            runJob(
                block = { encryptFile(uri, dst, pwd); "ok" },
                onOk = { log("> archivo cifrado → $name") }
            )
        }
        createDoc.launch(name)
    }

    private fun pickAndDecrypt() {
        val pwd = readPassword() ?: return
        val uri = pickedUri
        if (uri == null) { toast("Selecciona un .e255"); return }
        val original = (queryName(uri) ?: "archivo.e255").removeSuffix(".e255")
        pendingCreate = { dst ->
            runJob(
                block = { decryptFile(uri, dst, pwd); "ok" },
                onOk = { log("> archivo descifrado → $original") }
            )
        }
        createDoc.launch(original)
    }

    private suspend fun encryptFile(src: Uri, dst: Uri, pwd: CharArray) = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(src)!!.use { ins ->
            contentResolver.openOutputStream(dst, "wt")!!.use { outs ->
                CryptoEngine.encrypt(ins, outs, pwd.copyOf(), true)
            }
        }
    }

    private suspend fun decryptFile(src: Uri, dst: Uri, pwd: CharArray) = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(src)!!.use { ins ->
            contentResolver.openOutputStream(dst, "wt")!!.use { outs ->
                CryptoEngine.decrypt(ins, outs, pwd.copyOf())
            }
        }
    }

    // ───────────── UTILIDADES ─────────────

    private fun copyToClipboard() {
        val text = textInput.text.toString()
        if (text.isEmpty()) { toast("Nada que copiar"); return }
        SecurityManager.copyAndSelfDestruct(this, text)
        log("> copiado · autolimpieza en 30s")
        toast("Copiado · se borrará en 30s")
    }

    private fun shareCurrent() {
        // Si hay archivo(s) seleccionado(s) → compartir archivo(s)
        if (mode == Mode.FILE && pickedUris.isNotEmpty()) {
            shareFiles()
            return
        }
        // Si hay texto → compartir texto
        val text = textInput.text.toString()
        if (text.isEmpty()) { toast("Nada que compartir"); return }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Encryptor 255")
        }
        startActivity(Intent.createChooser(i, "Compartir texto"))
        log("> compartiendo texto (${text.length} chars)")
    }

    private fun shareFiles() {
        lifecycleScope.launch {
            try {
                val cacheDir = java.io.File(cacheDir, "shared").apply { mkdirs() }
                // Limpiar previos
                cacheDir.listFiles()?.forEach { it.delete() }

                val uris = mutableListOf<Uri>()
                for ((idx, src) in pickedUris.withIndex()) {
                    val name = queryName(src) ?: "archivo_${idx + 1}"
                    val outFile = java.io.File(cacheDir, name)
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(src)!!.use { ins ->
                            outFile.outputStream().use { outs -> ins.copyTo(outs) }
                        }
                    }
                    val contentUri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        outFile
                    )
                    uris.add(contentUri)
                }

                val intent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_STREAM, uris[0])
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                startActivity(Intent.createChooser(intent, "Compartir archivo(s)"))
                log("> compartiendo ${uris.size} archivo(s)")
            } catch (t: Throwable) {
                log("! error al compartir: ${t.message}")
                toast("No se pudo compartir")
            }
        }
    }

    private fun clearAll() {
        textInput.setText("")
        passwordInput.setText("")
        pickedUri = null
        fileName.text = getString(R.string.hint_no_file)
        fileMeta.text = "—"
        hashLabel.text = ""
        refreshStrength("")
        log("> estado limpiado")
    }

    private fun readPassword(): CharArray? {
        val p = passwordInput.text.toString()
        if (p.isEmpty()) { toast("Introduce una contraseña"); log("! contraseña vacía"); return null }
        return p.toCharArray()
    }

    private fun runJob(block: suspend () -> String, onOk: (String) -> Unit) {
        lifecycleScope.launch {
            log("> trabajando…")
            try {
                val msg = withContext(Dispatchers.IO) { block() }
                onOk(msg)
            } catch (t: Throwable) {
                val m = "! error: ${t.message ?: t.javaClass.simpleName}"
                log(m)
                toast(m)
            }
        }
    }

    private fun log(line: String) {
        val current = statusText.text.toString()
        val lines = current.lines().takeLast(6)
        val newText = (lines + line).joinToString("\n")
        statusText.text = newText
        statusText.alpha = 0.4f
        statusText.animate().alpha(1f).setDuration(280).start()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun querySize(uri: Uri): Long =
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L

    override fun onStop() {
        super.onStop()
        if (::passwordInput.isInitialized) {
            val pwd = passwordInput.text?.toString()
            if (!pwd.isNullOrEmpty()) {
                val arr = pwd.toCharArray()
                SecurityManager.wipe(arr)
            }
        }
    }
}

