package com.encryptor255

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

object CryptoEngine {
    private const val MAGIC = "E255"
    private const val VERSION = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private const val BUFFER = 64 * 1024
    private const val HEADER_LEN = 4 + 1 + 1 + SALT_LEN + IV_LEN
    private val rng = SecureRandom()

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray, compress: Boolean = true) {
        val salt = ByteArray(SALT_LEN).also(rng::nextBytes)
        val iv = ByteArray(IV_LEN).also(rng::nextBytes)
        val key = deriveKey(password, salt)
        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(VERSION)
        output.write(if (compress) 1 else 0)
        output.write(salt)
        output.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val cipherOut = CipherOutputStream(output, cipher)
        val finalOut: OutputStream = if (compress) GZIPOutputStream(cipherOut, BUFFER) else cipherOut
        input.copyTo(finalOut, BUFFER)
        finalOut.close()
        key.fill(0)
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val header = ByteArray(HEADER_LEN)
        readFully(input, header)
        require(String(header, 0, 4, Charsets.US_ASCII) == MAGIC) { "No es .e255" }
        val compressed = (header[5].toInt() and 0x01) == 1
        val salt = header.copyOfRange(6, 6 + SALT_LEN)
        val iv = header.copyOfRange(6 + SALT_LEN, HEADER_LEN)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val cipherIn = CipherInputStream(input, cipher)
        val finalIn: InputStream = if (compressed) GZIPInputStream(cipherIn, BUFFER) else cipherIn
        finalIn.copyTo(output, BUFFER)
        finalIn.close()
        key.fill(0)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
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
