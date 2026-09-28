package com.encryptor255

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.log2

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
        val score: Int,
        val entropyBits: Double,
        val label: String
    )

    /**
     * Fuerza basada en entropía aproximada (log2 del alfabeto * longitud),
     * penalizada por baja diversidad de caracteres.
     */
    fun strength(password: String): Strength {
        if (password.isEmpty()) return Strength(0, 0.0, "—")

        var hasLower = false
        var hasUpper = false
        var hasDigit = false
        var hasSymbol = false
        var hasOther = false

        for (c in password) {
            when {
                c in 'a'..'z' -> hasLower = true
                c in 'A'..'Z' -> hasUpper = true
                c in '0'..'9' -> hasDigit = true
                c in "!@#\$%^&*()-_=+[]{}<>?/|~.,;:'\"\\` " -> hasSymbol = true
                else -> hasOther = true
            }
        }

        var alphabet = 0
        if (hasLower) alphabet += 26
        if (hasUpper) alphabet += 26
        if (hasDigit) alphabet += 10
        if (hasSymbol) alphabet += 30
        if (hasOther) alphabet += 100
        if (alphabet == 0) alphabet = 1

        val rawBits: Double = log2(alphabet.toDouble()) * password.length.toDouble()

        val uniqueCount: Int = password.toSet().size
        val diversity: Double = (uniqueCount.toDouble() / password.length.toDouble()).coerceIn(0.3, 1.0)
        val bits: Double = rawBits * (0.6 + 0.4 * diversity)

        val score: Int = when {
            bits >= 128 -> 4
            bits >= 80 -> 3
            bits >= 50 -> 2
            bits >= 30 -> 1
            else -> 0
        }
        val label: String = when (score) {
            4 -> "BLINDADA"
            3 -> "FUERTE"
            2 -> "ACEPTABLE"
            1 -> "DÉBIL"
            else -> "MUY DÉBIL"
        }
        return Strength(score, bits, label)
    }

    /** Huella corta (SHA-256 primeros 4 bytes) para mostrar en el log */
    fun shortHash(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 4) {
            val b = bytes[i].toInt() and 0xFF
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
