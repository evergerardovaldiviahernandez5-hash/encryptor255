package com.encryptor255

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Almacén local de notas cifradas.
 * Cada nota es un archivo .e255 en filesDir/notes/.
 * Nombre del archivo = timestamp + slug del título.
 */
object NotesStore {

    data class Note(
        val file: File,
        val title: String,
        val timestamp: Long
    )

    private fun notesDir(ctx: Context): File {
        val d = File(ctx.filesDir, "notes")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(ctx: Context): List<Note> {
        val dir = notesDir(ctx)
        return dir.listFiles { f -> f.extension == "e255" }
            ?.map { f ->
                val parts = f.nameWithoutExtension.split("__", limit = 2)
                val ts = parts.getOrNull(0)?.toLongOrNull() ?: f.lastModified()
                val title = parts.getOrNull(1)?.replace("_", " ") ?: "Sin título"
                Note(f, title, ts)
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun save(ctx: Context, title: String, content: String, password: CharArray): File {
        val dir = notesDir(ctx)
        val ts = System.currentTimeMillis()
        val slug = title
            .replace(Regex("[^a-zA-Z0-9áéíóúñÁÉÍÓÚÑ ]"), "")
            .trim()
            .replace(" ", "_")
            .take(30)
            .ifEmpty { "nota" }
        val file = File(dir, "${ts}__${slug}.e255")
        file.outputStream().use { outs ->
            CryptoEngine.encrypt(
                content.byteInputStream(Charsets.UTF_8),
                outs,
                password,
                dataIsCompressed = true
            )
        }
        return file
    }

    fun load(ctx: Context, file: File, password: CharArray): String {
        val out = java.io.ByteArrayOutputStream()
        file.inputStream().use { ins ->
            CryptoEngine.decrypt(ins, out, password)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun delete(file: File): Boolean = file.delete()

    fun formatDate(ts: Long): String {
        val fmt = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())
        return fmt.format(Date(ts))
    }
}
