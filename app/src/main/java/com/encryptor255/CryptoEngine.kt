package com.encryptor255

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.io.*
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Formato .e255:
 *
 * v1 (legacy, solo lectura):
 *   [0..3]   "E255"
 *   [4]      version = 1
 *   [5]      flags   bit0 = GZIP
 *   [6..21]  salt    16 bytes
 *   [22..33] IV      12 bytes
 *   [34..]   AES-256-GCM (payload + tag 16B)
 *   Key = PBKDF2-HMAC-SHA512(310000 iter, salt, 256 bit)
 *
 * v2 (actual, escritura):
 *   mismo header, version = 2
 *   Key = Argon2id(m=64 MiB, t=3, p=2, out=32 bytes)
 */
object CryptoEngine {
    private const val MAGIC = "E255"
    private const val VERSION_1 = 1
    private const val VERSION_2 = 2
    private const val VERSION_WRITE = VERSION_2

    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val BUFFER = 256 * 1024
    private const val HEADER_LEN = 4 + 1 + 1 + SALT_LEN + IV_LEN

    // PBKDF2 (v1, legacy)
    private const val PBKDF2_ITERATIONS = 310_000

    // Argon2id (v2) — OWASP 2024 recomendación
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_MEMORY_KIB = 32768    // 32 MiB (más rápido en móvil)
    private const val ARGON2_PARALLELISM = 1

    private val rng = SecureRandom()
    private val argon2 = Argon2Kt()

    // ─────────── ENCRYPT (siempre v2) ───────────

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        compress: Boolean = true,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        val salt = ByteArray(SALT_LEN).also(rng::nextBytes)
        val iv = ByteArray(IV_LEN).also(rng::nextBytes)
        val key = deriveKeyArgon2(password, salt)

        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(VERSION_WRITE)
        output.write(if (compress) 1 else 0)
        output.write(salt)
        output.write(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )

        val cipherOut = CipherOutputStream(output, cipher)
        val finalOut: OutputStream =
            if (compress) GZIPOutputStream(cipherOut, BUFFER) else cipherOut

        var total: Long = 0
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            finalOut.write(buf, 0, n)
            total += n
            onProgress?.invoke(total, -1L)
        }
        finalOut.close()
        key.fill(0)
    }

    // ─────────── DECRYPT (v1 o v2) ───────────

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray, onProgress: ((Long, Long) -> Unit)? = null) {
        val header = ByteArray(HEADER_LEN)
        readFully(input, header)

        val magic = String(header, 0, 4, Charsets.US_ASCII)
        require(magic == MAGIC) { "Formato no reconocido (no es .e255)" }

        val version = header[4].toInt() and 0xFF
        val compressed = (header[5].toInt() and 0x01) == 1
        val salt = header.copyOfRange(6, 6 + SALT_LEN)
        val iv = header.copyOfRange(6 + SALT_LEN, HEADER_LEN)

        val key: ByteArray = when (version) {
            VERSION_1 -> deriveKeyPBKDF2(password, salt)
            VERSION_2 -> deriveKeyArgon2(password, salt)
            else -> throw IllegalArgumentException("Versión $version no soportada")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )

        val cipherIn = CipherInputStream(input, cipher)
        val finalIn: InputStream =
            if (compressed) GZIPInputStream(cipherIn, BUFFER) else cipherIn

        var total: Long = 0
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = finalIn.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            onProgress?.invoke(total, -1L)
        }
        finalIn.close()
        key.fill(0)
    }

    // ─────────── DERIVACIÓN ───────────

    private fun deriveKeyArgon2(password: CharArray, salt: ByteArray): ByteArray {
        // Convertir CharArray → UTF-8 bytes sin dejar rastro
        val pwBytes = CharArrayToUtf8(password)
        return try {
            val result = argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = pwBytes,
                salt = salt,
                tCostInIterations = ARGON2_ITERATIONS,
                mCostInKibibyte = ARGON2_MEMORY_KIB,
                parallelism = ARGON2_PARALLELISM,
                hashLengthInBytes = KEY_BITS / 8
            )
            result.rawHashAsByteArray()
        } finally {
            pwBytes.fill(0)
        }
    }

    private fun deriveKeyPBKDF2(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun CharArrayToUtf8(chars: CharArray): ByteArray {
        val sb = StringBuilder(chars.size)
        chars.forEach { sb.append(it) }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("Archivo truncado o corrupto")
            off += n
        }
    }
}
