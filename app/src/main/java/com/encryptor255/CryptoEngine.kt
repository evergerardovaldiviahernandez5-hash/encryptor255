package com.encryptor255

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.io.*
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming AES-256-GCM puro. NO comprime internamente.
 * El flag `dataIsCompressed` solo indica qué escribir en el header.
 * La compresión la maneja el caller (MainActivity) con temp files si es necesario.
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

    private const val PBKDF2_ITERATIONS = 310_000
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_MEMORY_KIB = 32768
    private const val ARGON2_PARALLELISM = 1

    private val rng = SecureRandom()
    private val argon2 = Argon2Kt()

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        dataIsCompressed: Boolean,
        totalSize: Long = -1L,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        val salt = ByteArray(SALT_LEN).also(rng::nextBytes)
        val iv = ByteArray(IV_LEN).also(rng::nextBytes)
        val key = deriveKeyArgon2(password, salt)

        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(VERSION_WRITE)
        output.write(if (dataIsCompressed) 1 else 0)
        output.write(salt)
        output.write(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )

        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val chunk = cipher.update(buf, 0, n)
            if (chunk != null && chunk.isNotEmpty()) output.write(chunk)
            total += n
            onProgress?.invoke(total, totalSize)
        }
        val tag = cipher.doFinal()
        if (tag != null && tag.isNotEmpty()) output.write(tag)
        output.flush()
        key.fill(0)
    }

    fun decrypt(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        totalSize: Long = -1L,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        val header = ByteArray(HEADER_LEN)
        readFully(input, header)
        require(String(header, 0, 4, Charsets.US_ASCII) == MAGIC) { "Formato no reconocido" }

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

        val sink: OutputStream = if (compressed) GZIPOutputStream(output, BUFFER) else output

        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val chunk = cipher.update(buf, 0, n)
            if (chunk != null && chunk.isNotEmpty()) sink.write(chunk)
            total += n
            onProgress?.invoke(total, totalSize)
        }
        val tail = try {
            cipher.doFinal()
        } catch (t: Throwable) {
            throw SecurityException("Contraseña incorrecta o archivo corrupto", t)
        }
        if (tail != null && tail.isNotEmpty()) sink.write(tail)
        sink.flush()
        if (compressed) (sink as GZIPOutputStream).finish()
        output.flush()
        key.fill(0)
    }

    private fun deriveKeyArgon2(password: CharArray, salt: ByteArray): ByteArray {
        val pwBytes = password.concatToString().toByteArray(Charsets.UTF_8)
        return try {
            argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = pwBytes,
                salt = salt,
                tCostInIterations = ARGON2_ITERATIONS,
                mCostInKibibyte = ARGON2_MEMORY_KIB,
                parallelism = ARGON2_PARALLELISM,
                hashLengthInBytes = KEY_BITS / 8
            ).rawHashAsByteArray()
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

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("Archivo truncado")
            off += n
        }
    }
}
