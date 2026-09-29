package com.encryptor255

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

/**
 * Capa de endurecimiento — sin estado, todo estático.
 *
 * Responsabilidades:
 *  - FLAG_SECURE (bloquea screenshots + preview en app switcher)
 *  - Portapapeles autolimpiable (30s, solo si el clip es nuestro)
 *  - BiometricPrompt con fallback silencioso
 *  - Utilidades para zero-RAM de CharArray
 */
object SecurityManager {

    private const val CLIP_LABEL = "encryptor255"
    private const val CLIP_CLEAR_MS = 30_000L

    // ─────────────────────────────────────────
    // FLAG_SECURE
    // ─────────────────────────────────────────

    fun applySecureFlag(activity: Activity, enabled: Boolean = true) {
        if (enabled) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // ─────────────────────────────────────────
    // CLIPBOARD AUTODESTRUCTIVO
    // ─────────────────────────────────────────

    fun copyAndSelfDestruct(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(CLIP_LABEL, text)

        // Marcar como sensible (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)

        Handler(Looper.getMainLooper()).postDelayed({
            val desc = cm.primaryClipDescription
            if (desc?.label?.toString() == CLIP_LABEL) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cm.clearPrimaryClip()
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }, CLIP_CLEAR_MS)
    }

    // ─────────────────────────────────────────
    // ZERO-RAM
    // ─────────────────────────────────────────

    fun wipe(chars: CharArray?) {
        chars?.fill('\u0000')
    }

    // ─────────────────────────────────────────
    // BIOMETRÍA
    // ─────────────────────────────────────────

    private fun authenticators(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

    fun canUseBiometric(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun promptBiometric(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!canUseBiometric(activity)) {
            onError("Biometría no configurada en este dispositivo")
            return
        }

        val executor: Executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    onSuccess()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    onError(msg.toString())
                }
            })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Encryptor 255")
            .setSubtitle("Verifica tu identidad")
            .setAllowedAuthenticators(authenticators())
            .build()

        prompt.authenticate(info)
    }
}
