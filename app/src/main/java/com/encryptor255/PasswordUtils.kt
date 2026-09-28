package com.encryptor255

import java.security.SecureRandom
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow

object PasswordUtils {

    private val rng = SecureRandom()

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{}<>?/|~"

    /** Genera una contraseña cripto-segura. */
    fun generate(length: Int = 24, useSymbols: Boolean = true): String {
        val alphabet = buildString {
            append(LOWER).append(UPPER).append(DIGITS)
            if (useSymbols) append(SYMBOLS)
        }
        val sb = StringBuilder(length)
        repeat(length) { sb.append(alphabet[rng.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    data class Strength(
        val score: Int,       // 0..4
        val entropyBits: Double,
        val label: String,    // "DÉBIL", "ACEPTABLE", ...
    )

    /**
     * Fuerza basada en entropía de Shannon aproximada.
     * bits = log2(alphabetSize) * length  → ajustado por variedad real.
     */
    fun strength(password: String): Strength {
        if (password.isEmpty()) return Strength(0, 0.0, "—")

        var hasLower = false
        var hasUpper = false
        var hasDigit = false
        var hasSymbol = false
        var hasOther = false

        for (c in password) when {
            c in 'a'..'z' -> hasLower = true
            c in 'A'..'Z' -> hasUpper = true
            c in '0'..'9' -> hasDigit = true
            c in "!@#\$%^&*()-_=+[]{}<>?/|~.,;:'\"\\` " -> hasSymbol = true
            else -> hasOther = true
        }

        var alphabet = 0
        if (hasLower) alphabet += 26
        if (hasUpper) alphabet += 26
        if (hasDigit) alphabet += 10
        if (hasSymbol) alphabet += 30
        if (hasOther) alphabet += 100
        if (alphabet == 0) alphabet = 1

        val rawBits = log2(alphabet.toDouble()) * password.length

        // Penalización por repeticiones
        val unique = password.toSet().size.toDouble()
        val diversity = (unique / password.length).coerceIn(0.3, 1.0)
        val bits = rawBits * (0.6 + 0.4 * diversity)

        val score = when {
            bits >= 128 -> 4
            bits >= 80 -> 3
            bits >= 50 -> 2
            bits >= 30 -> 1
            else -> 0
        }
        val label = when (score) {
            4 -> "BLINDADA"
            3 -> "FUERTE"
            2 -> "ACEPTABLE"
            1 -> "DÉBIL"
            else -> "MUY DÉBIL"
        }
        return Strength(score, bits, label)
    }

    /** Hash rápido (para mostrar "huella" en el log) */
    fun shortHash(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun Int.powSafe(exp: Int): Double = this.toDouble().pow(exp.toDouble())

    @Suppress("unused")
    private fun min(a: Int, b: Int) = min(a, b)
}
