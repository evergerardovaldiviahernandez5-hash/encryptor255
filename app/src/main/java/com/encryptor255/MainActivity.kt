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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.security.MessageDigest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import android.app.AlertDialog
import android.graphics.Bitmap
import android.provider.MediaStore
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Switch
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
    private lateinit var progressContainer: View
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var progressLabel: TextView
    private var currentJob: Job? = null
    private val cryptoDispatcher = Dispatchers.IO.limitedParallelism(2)

    private var pwdVisible = false
    private var mode: Mode = Mode.TEXT
    private var action: Action = Action.ENCRYPT
    private var pickedUri: Uri? = null
    private val pickedUris = mutableListOf<Uri>()
    private var pendingSingleFile: java.io.File? = null
    private var pendingBundleFile: java.io.File? = null
    private var pendingBundleNames: List<String> = emptyList()

    private val pickDirForExtract = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { dirUri -> dirUri?.let { extractBundleTo(it) } }
    private var batchMode = false

    private var pendingOpen: ((Uri) -> Unit)? = null
    private var pendingCreate: ((Uri) -> Unit)? = null

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingOpen?.invoke(it) }
    }
    private val singleDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedUris.clear()
        pickedUris.add(uri)
        pickedUri = uri
        val name = queryName(uri) ?: "archivo"
        val size = querySize(uri)
        fileName.text = name
        fileMeta.text = String.format(java.util.Locale.US, "%d bytes · %.2f KB", size, size / 1024.0)
        log("> archivo cargado: " + name)
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
        progressContainer = findViewById(R.id.progressContainer)
        progressBar = findViewById(R.id.progressBar)
        progressLabel = findViewById(R.id.progressLabel)

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
        findViewById<Button>(R.id.btnQrGenerate).setOnClickListener {
            withHaptic { generateQrFromText() }
        }
        findViewById<Button>(R.id.btnQrScan).setOnClickListener {
            withHaptic { scanQrFromImage() }
        }
        findViewById<Switch>(R.id.switchBatch).setOnCheckedChangeListener { _, checked ->
            batchMode = checked
            log(if (checked) "> modo lote ON" else "> modo lote OFF")
            if (!checked) pickedUris.clear()
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            withHaptic {
                currentJob?.cancel()
                log("> operación cancelada")
                hideProgress()
            }
        }
        findViewById<Button>(R.id.btnHash).setOnClickListener {
            withHaptic { computeFileHash() }
        }
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
        if (batchMode) {
            log("> selección múltiple activa")
            openMulti.launch(arrayOf("*/*"))
        } else {
            log("> selección simple")
            singleDoc.launch(arrayOf("*/*"))
        }
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
        if (pickedUris.isEmpty()) { toast("Selecciona archivo(s)"); return }

        val uris = pickedUris.toList()
        val name = if (uris.size == 1) {
            (queryName(uris[0]) ?: "archivo") + ".e255"
        } else {
            "vault_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + ".e255"
        }

        pendingCreate = { dst ->
            runJob(
                block = { encryptBundle(uris, dst, pwd) },
                onOk = { log("> " + uris.size + " archivo(s) cifrado(s) → " + name) }
            )
        }
        createDoc.launch(name)
    }

    private fun pickAndDecrypt() {
        val pwd = readPassword() ?: return
        val uri = pickedUris.firstOrNull() ?: pickedUri
        if (uri == null) { toast("Selecciona un .e255"); return }
        val original = (queryName(uri) ?: "archivo.e255").removeSuffix(".e255")

        runJob(
            block = { decryptOrExtract(uri, pwd, original) },
            onOk = { }
        )
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
                val cacheDir = java.io.File(cacheDir, "shared")
                val uris = mutableListOf<Uri>()
                withContext(Dispatchers.IO) {
                    cacheDir.mkdirs()
                    cacheDir.listFiles()?.forEach { it.delete() }
                    for ((idx, src) in pickedUris.withIndex()) {
                        val name = queryName(src) ?: "archivo_${idx + 1}"
                        val outFile = java.io.File(cacheDir, name)
                        contentResolver.openInputStream(src)!!.use { ins ->
                            outFile.outputStream().use { outs -> ins.copyTo(outs, 64 * 1024) }
                        }
                        val contentUri = androidx.core.content.FileProvider.getUriForFile(
                            this@MainActivity, "${packageName}.fileprovider", outFile
                        )
                        uris.add(contentUri)
                    }
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
                startActivity(Intent.createChooser(intent, "Compartir"))
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
        currentJob?.cancel()
        currentJob = lifecycleScope.launch {
            showProgress("Procesando…")
            try {
                val msg = withContext(Dispatchers.IO) { block() }
                onOk(msg)
                hideProgress()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                log("> cancelado")
                hideProgress()
                throw ce
            } catch (t: Throwable) {
                val m = "! error: ${t.message ?: t.javaClass.simpleName}"
                log(m)
                toast(m)
                hideProgress()
            }
        }
    }

    private fun showProgress(label: String) {
        progressContainer.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressLabel.text = label
    }

    private fun hideProgress() {
        progressContainer.visibility = View.GONE
        progressBar.progress = 0
    }

    private fun updateProgress(bytes: Long) {
        progressLabel.text = "Procesando… ${bytes / 1024} KB"
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


    // ═══════════════════════════════════════════
    // F5a · Cifrado en lote (ZIP bundle)
    // ═══════════════════════════════════════════

    private suspend fun encryptBundle(uris: List<Uri>, dst: Uri, pwd: CharArray): String =
        withContext(cryptoDispatcher) {
            if (uris.size == 1) {
                contentResolver.openInputStream(uris[0])!!.use { ins ->
                    contentResolver.openOutputStream(dst, "wt")!!.use { outs ->
                        CryptoEngine.encrypt(ins, outs, pwd.copyOf(), compress = true) { b, _ ->
                            lifecycleScope.launch(Dispatchers.Main) { updateProgress(b) }
                        }
                    }
                }
                return@withContext "ok"
            }
            val tmp = java.io.File(cacheDir, "enc_${System.currentTimeMillis()}.zip")
            try {
                java.io.FileOutputStream(tmp).use { fos ->
                    java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(fos)).use { zos ->
                        uris.forEachIndexed { idx, src ->
                            val name = queryName(src) ?: "archivo_$idx"
                            zos.putNextEntry(java.util.zip.ZipEntry(name))
                            contentResolver.openInputStream(src)!!.use { ins ->
                                ins.copyTo(zos, 64 * 1024)
                            }
                            zos.closeEntry()
                            withContext(Dispatchers.Main) {
                                log("> empaquetando ${idx + 1}/${uris.size}: $name")
                            }
                        }
                    }
                }
                java.io.FileInputStream(tmp).use { ins ->
                    contentResolver.openOutputStream(dst, "wt")!!.use { outs ->
                        CryptoEngine.encrypt(ins, outs, pwd.copyOf(), compress = false) { b, _ ->
                            lifecycleScope.launch(Dispatchers.Main) { updateProgress(b) }
                        }
                    }
                }
                "ok"
            } finally { tmp.delete() }
        }

    private fun showQrDialog(bmp: Bitmap) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val size = (260 * resources.displayMetrics.density).toInt()

        val container = android.widget.FrameLayout(this)
        container.setPadding(pad, pad, pad, pad)

        val iv = ImageView(this)
        iv.layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setImageBitmap(bmp)
        container.addView(iv)

        AlertDialog.Builder(this)
            .setTitle(R.string.qr_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.qr_save) { _, _ -> saveQrToFile(bmp) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun saveQrToFile(bmp: Bitmap) {
        pendingCreate = { uri ->
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    log("> QR guardado")
                    toast("QR guardado")
                } catch (t: Throwable) {
                    log("! error: " + t.message)
                }
            }
        }
        createDoc.launch("qr_" + System.currentTimeMillis() + ".png")
    }

    private fun scanQrFromImage() {
        pendingOpen = { uri ->
            lifecycleScope.launch {
                try {
                    log("> leyendo QR…")
                    val text = withContext(Dispatchers.IO) {
                        val bmp = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                        QrUtils.decode(bmp)
                    }
                    if (text.isNullOrEmpty()) {
                        log("! QR no detectado")
                        toast(getString(R.string.qr_not_found))
                    } else {
                        textInput.setText(text)
                        log("> QR decodificado (" + text.length + " chars)")
                        toast("QR leído")
                    }
                } catch (t: Throwable) {
                    log("! error: " + t.message)
                }
            }
        }
        openDoc.launch(arrayOf("image/*"))
    }


    // ═══════════════════════════════════════════
    // F5b · Hash SHA-256 de archivo
    // ═══════════════════════════════════════════
    private fun computeFileHash() {
        val uri = pickedUris.firstOrNull() ?: pickedUri
        if (uri == null) { toast("Selecciona un archivo primero"); return }
        val name = queryName(uri) ?: "archivo"

        runJob(
            block = {
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(64 * 1024)
                var total = 0L
                contentResolver.openInputStream(uri)!!.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        total += n
                        withContext(Dispatchers.Main) { updateProgress(total) }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                "ok:$hex"
            },
            onOk = { result ->
                val hex = result.removePrefix("ok:")
                log("> sha256: ${hex.take(16)}…")
                showHashDialog(name, hex)
            }
        )
    }

    private fun showHashDialog(name: String, hex: String) {
        val formatted = hex.chunked(4).joinToString(" ")
        val msg = "Archivo: $name\n\n$formatted"
        AlertDialog.Builder(this)
            .setTitle(R.string.hash_dialog_title)
            .setMessage(msg)
            .setPositiveButton("Copiar") { _, _ ->
                SecurityManager.copyAndSelfDestruct(this, hex)
                toast("Hash copiado")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }


    // ═══════════════════════════════════════════
    // decryptOrExtract — descifra a temp file, detecta ZIP
    // ═══════════════════════════════════════════
    private suspend fun decryptOrExtract(uri: Uri, pwd: CharArray, baseName: String): String =
        withContext(cryptoDispatcher) {
            val tmp = java.io.File(cacheDir, "dec_${System.currentTimeMillis()}.tmp")
            try {
                java.io.FileOutputStream(tmp).use { fos ->
                    contentResolver.openInputStream(uri)!!.use { ins ->
                        CryptoEngine.decrypt(ins, fos, pwd.copyOf()) { bytes, _ ->
                            lifecycleScope.launch(Dispatchers.Main) { updateProgress(bytes) }
                        }
                    }
                }

                val header = ByteArray(4)
                java.io.FileInputStream(tmp).use { fis -> fis.read(header) }
                val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

                if (!isZip) {
                    withContext(Dispatchers.Main) {
                        pendingSingleFile = tmp
                        pendingCreate = { out ->
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        contentResolver.openOutputStream(out, "wt")!!.use { os ->
                                            java.io.FileInputStream(tmp).use { fis ->
                                                fis.copyTo(os, 64 * 1024)
                                            }
                                        }
                                    }
                                    log("> archivo descifrado")
                                } catch (t: Throwable) {
                                    log("! error: ${t.message}")
                                } finally {
                                    tmp.delete()
                                    pendingSingleFile = null
                                }
                            }
                        }
                        createDoc.launch(baseName)
                    }
                    return@withContext "file"
                }

                val names = mutableListOf<String>()
                java.util.zip.ZipInputStream(java.io.FileInputStream(tmp)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) { names.add(e.name); e = zis.nextEntry }
                }
                withContext(Dispatchers.Main) {
                    pendingBundleFile = tmp
                    pendingBundleNames = names
                    log("> bundle: ${names.size} archivos")
                    pickDirForExtract.launch(null)
                }
                "bundle"
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }

    // ═══════════════════════════════════════════
    // extractBundleTo — extrae el ZIP descifrado
    // ═══════════════════════════════════════════
    private fun extractBundleTo(dirUri: Uri) {
        val tmp = pendingBundleFile ?: return
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val root = androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(this@MainActivity, dirUri)
                        ?: throw IllegalStateException("Carpeta inaccesible")
                    var n = 0
                    java.util.zip.ZipInputStream(java.io.FileInputStream(tmp)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val safe = entry.name.replace("..", "_").substringAfterLast('/')
                            val out = root.createFile("application/octet-stream", safe)
                            if (out != null) {
                                contentResolver.openOutputStream(out.uri, "wt")!!.use { os ->
                                    zis.copyTo(os, 64 * 1024)
                                }
                                n++
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    n
                }
                log("> $count archivos extraídos")
                toast(getString(R.string.bundle_extracted, count))
            } catch (t: Throwable) {
                log("! error al extraer: ${t.message}")
                toast("No se pudo extraer")
            } finally {
                tmp.delete()
                pendingBundleFile = null
                pendingBundleNames = emptyList()
            }
        }
    }

    // ═══════════════════════════════════════════
    // generateQrFromText — genera QR del input
    // ═══════════════════════════════════════════
    private fun generateQrFromText() {
        val text = textInput.text.toString()
        if (text.isEmpty()) { toast(getString(R.string.qr_empty)); return }
        if (text.length > 2000) { toast(getString(R.string.qr_too_long)); return }

        log("> generando QR (" + text.length + " chars)…")
        try {
            val bmp = QrUtils.generate(text, 800)
            showQrDialog(bmp)
            log("> QR mostrado")
        } catch (t: Throwable) {
            log("! error generando QR: " + t.message)
            toast("Error al generar QR")
        }
    }

}
