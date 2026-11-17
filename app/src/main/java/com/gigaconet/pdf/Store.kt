package com.gigaconet.pdf

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recent(val uri: String, val name: String, val time: Long)

/** Guardar en Descargas, lista de recientes y utilidades de archivos. */
object Store {

    private const val PREFS = "gigapdf"

    fun newName(prefix: String = "GigaPDF"): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_$stamp.pdf"
    }

    fun cleanName(name: String): String {
        var n = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (n.isEmpty()) n = newName()
        if (!n.lowercase(Locale.ROOT).endsWith(".pdf")) n += ".pdf"
        return n
    }

    /** Copia el archivo a Descargas/GigaPDF y devuelve su Uri. */
    fun saveToDownloads(ctx: Context, src: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName(name))
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GigaPDF")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear el archivo")
        ctx.contentResolver.openOutputStream(uri, "w").use { out ->
            requireNotNull(out)
            src.inputStream().use { it.copyTo(out) }
        }
        return uri
    }

    /** Guarda una imagen en Imágenes/GigaPDF (se ve en la galería). */
    fun saveImage(ctx: Context, bmp: Bitmap, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GigaPDF")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear la imagen")
        ctx.contentResolver.openOutputStream(uri, "w").use { out ->
            requireNotNull(out)
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        return uri
    }

    /** Reemplaza el contenido de un documento existente (si hay permiso). */
    fun overwrite(ctx: Context, src: File, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "wt").use { out ->
            requireNotNull(out)
            src.inputStream().use { it.copyTo(out) }
        }
    }

    fun copyToFile(ctx: Context, uri: Uri, dst: File) {
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "No se pudo leer el archivo" }
            dst.outputStream().use { input.copyTo(it) }
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "documento.pdf"
    }

    /** Lee una imagen ya girada correctamente y reducida para no gastar memoria. */
    fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int = 2200): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val big = maxOf(w, h)
            if (big > maxSide) {
                val s = maxSide.toFloat() / big
                decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
        }
    }

    fun shareFile(ctx: Context, src: File, name: String) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, cleanName(name))
        src.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Compartir PDF"))
    }

    // ---------- Recientes ----------

    fun recents(ctx: Context): List<Recent> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recents", "[]")
        val list = ArrayList<Recent>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Recent(o.getString("u"), o.getString("n"), o.getLong("t")))
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun saveRecents(ctx: Context, list: List<Recent>) {
        val arr = JSONArray()
        for (r in list.take(30)) {
            arr.put(JSONObject().put("u", r.uri).put("n", r.name).put("t", r.time))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recents", arr.toString()).apply()
    }

    fun addRecent(ctx: Context, uri: Uri, name: String) {
        val list = recents(ctx).filter { it.uri != uri.toString() }.toMutableList()
        list.add(0, Recent(uri.toString(), name, System.currentTimeMillis()))
        saveRecents(ctx, list)
    }

    fun removeRecent(ctx: Context, uri: String) {
        saveRecents(ctx, recents(ctx).filter { it.uri != uri })
    }

    // ---------- Firma guardada ----------

    fun signature(ctx: Context): Signature? =
        Signature.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("signature", null))

    fun saveSignature(ctx: Context, sig: Signature) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("signature", sig.toJson()).apply()
    }
}
