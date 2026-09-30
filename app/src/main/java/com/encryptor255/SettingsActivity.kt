package com.encryptor255

import android.content.Context
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.app.AlertDialog
import android.content.res.Configuration
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "encryptor255_prefs"
        private const val KEY_BIO = "biometric_enabled"
        private const val KEY_LANG = "app_language"
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var switchBio: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecurityManager.applySecureFlag(this, true)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        switchBio = findViewById(R.id.switchBiometric)
        switchBio.isChecked = prefs.getBoolean(KEY_BIO, false)

        // Si el dispositivo no soporta biometría, bloqueamos el switch
        if (!SecurityManager.canUseBiometric(this)) {
            switchBio.isEnabled = false
            switchBio.alpha = 0.5f
        }

        switchBio.setOnCheckedChangeListener { _, checked ->
            if (checked && !SecurityManager.canUseBiometric(this)) {
                switchBio.isChecked = false
                toast(getString(R.string.settings_biometric_unavailable))
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(KEY_BIO, checked).apply()
            toast(if (checked) getString(R.string.settings_biometric_on)
              else getString(R.string.settings_biometric_off))
        }

        
        // ═══════════ F5e · Selector de idioma ═══════════
        val langValue = findViewById<android.widget.TextView>(R.id.txtLanguageValue)
        val currentLang = prefs.getString(KEY_LANG, "system") ?: "system"
        langValue.text = languageLabel(currentLang)

        langValue.parent.parent.setOnClickListener {
            showLanguageDialog(currentLang)
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    private fun toast(m: String) =
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
