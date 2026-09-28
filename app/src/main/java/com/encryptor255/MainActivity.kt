package com.encryptor255

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var passwordInput: EditText
    private lateinit var textInput: EditText
    private lateinit var statusText: TextView
    private var pendingOpen: ((Uri) -> Unit)? = null
    private var pendingCreate: ((Uri) -> Unit)? = null

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { pendingOpen?.invoke(it) } }
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { pendingCreate?.invoke(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        passwordInput = findViewById(R.id.passwordInput)
        textInput = findViewById(R.id.textInput)
        statusText = findViewById(R.id.statusText)
        findViewById<Button>(R.id.btnEncryptText).setOnClickListener { encryptText() }
        findViewById<Button>(R.id.btnDecryptText).setOnClickListener { decryptText() }
        findViewById<Button>(R.id.btnEncryptFile).setOnClickListener { pickAndEncrypt() }
        findViewById<Button>(R.id.btnDecryptFile).setOnClickListener { pickAndDecrypt() }
    }

    private fun encryptText() {
        val pwd = readPassword() ?: return
        val text = textInput.text.toString()
        if (text.isEmpty()) { toast("Escribe un texto"); return }
        runJob({ textInput.setText(it); statusText.text = "Texto cifrado ✓" }) {
            val out = ByteArrayOutputStream()
            CryptoEngine.encrypt(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), out, pwd.copyOf(), true)
            "E255T:" + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun decryptText() {
        val pwd = readPassword() ?: return
        var text = textInput.text.toString().trim()
        if (text.startsWith("E255T:")) text = text.substring(6)
        if (text.isEmpty()) { toast("Pega texto cifrado"); return }
        runJob({ textInput.setText(it); statusText.text = "Texto descifrado ✓" }) {
            val bytes = Base64.decode(text, Base64.DEFAULT)
            val out = ByteArrayOutputStream()
            CryptoEngine.decrypt(ByteArrayInputStream(bytes), out, pwd.copyOf())
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun pickAndEncrypt() {
        val pwd = readPassword() ?: return
        pendingOpen = { src ->
            val name = (queryName(src) ?: "archivo") + ".e255"
            pendingCreate = { dst -> runJob { encryptFile(src, dst, pwd) } }
            createDoc.launch(name)
        }
        openDoc.launch(arrayOf("*/*"))
    }

    private fun pickAndDecrypt() {
        val pwd = readPassword() ?: return
        pendingOpen = { src ->
            val original = (queryName(src) ?: "archivo.e255").removeSuffix(".e255")
            pendingCreate = { dst -> runJob { decryptFile(src, dst, pwd) } }
            createDoc.launch(original)
        }
        openDoc.launch(arrayOf("*/*"))
    }

    private suspend fun encryptFile(src: Uri, dst: Uri, pwd: CharArray): String = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(src)!!.use { ins ->
            contentResolver.openOutputStream(dst, "wt")!!.use { outs ->
                CryptoEngine.encrypt(ins, outs, pwd.copyOf(), true)
            }
        }
        "Archivo cifrado ✓"
    }

    private suspend fun decryptFile(src: Uri, dst: Uri, pwd: CharArray): String = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(src)!!.use { ins ->
            contentResolver.openOutputStream(dst, "wt")!!.use { outs ->
                CryptoEngine.decrypt(ins, outs, pwd.copyOf())
            }
        }
        "Archivo descifrado ✓"
    }

    private fun readPassword(): CharArray? {
        val p = passwordInput.text.toString()
        if (p.isEmpty()) { toast("Introduce contraseña"); return null }
        return p.toCharArray()
    }

    private fun runJob(onSuccess: (String) -> Unit = { statusText.text = it }, block: suspend () -> String) {
        lifecycleScope.launch {
            statusText.text = "Procesando…"
            try {
                val msg = withContext(Dispatchers.IO) { block() }
                onSuccess(msg)
            } catch (t: Throwable) {
                val m = "Error: ${t.message ?: t.javaClass.simpleName}"
                statusText.text = m
                toast(m)
            }
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
