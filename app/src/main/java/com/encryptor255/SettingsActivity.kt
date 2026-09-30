package com.encryptor255

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "encryptor255_prefs"
        private const val KEY_BIO = "biometric_enabled"
        private const val KEY_LANG = "app_language"
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var switchBio: Switch
    private lateinit var langValue: TextView
    private lateinit var cardLanguage: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecurityManager.applySecureFlag(this, true)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // ─── Biometría ───
        switchBio = findViewById(R.id.switchBiometric)
        switchBio.isChecked = prefs.getBoolean(KEY_BIO, false)

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
            toast(
                if (checked) getString(R.string.settings_biometric_on)
                else getString(R.string.settings_biometric_off)
            )
        }

        // ─── Idioma ───
        langValue = findViewById(R.id.txtLanguageValue)
        cardLanguage = findViewById(R.id.cardLanguage)

        val currentLang = prefs.getString(KEY_LANG, "system") ?: "system"
        langValue.text = languageLabel(currentLang)

        cardLanguage.setOnClickListener {
            showLanguageDialog(currentLang)
        }

        // ─── Back ───
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    // ═══════════════════════════════════════════
    // F5e · Selector de idioma
    // ═══════════════════════════════════════════
    private fun languageLabel(code: String): String = when (code) {
        "system" -> getString(R.string.settings_language_system)
        "en" -> "English"
        "es" -> "Español"
        "pt" -> "Português"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "it" -> "Italiano"
        "ru" -> "Русский"
        "ar" -> "العربية"
        "hi" -> "हिन्दी"
        "zh" -> "中文"
        "ja" -> "日本語"
        else -> getString(R.string.settings_language_system)
    }

    private fun showLanguageDialog(current: String) {
        val codes = arrayOf(
            "system", "es", "en", "pt", "fr", "de",
            "it", "ru", "ar", "hi", "zh", "ja"
        )
        val labels = codes.map { languageLabel(it) }.toTypedArray()
        val checked = codes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_dialog_language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val code = codes[which]
                prefs.edit().putString(KEY_LANG, code).apply()
                applyLanguage(code)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyLanguage(code: String) {
        val locale: Locale = if (code == "system") {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.content.res.Resources.getSystem().configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                android.content.res.Resources.getSystem().configuration.locale
            }
        } else {
            Locale(code)
        }
        Locale.setDefault(locale)

        val config = Configuration(resources.configuration)
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        applicationContext.resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun toast(m: String) =
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
