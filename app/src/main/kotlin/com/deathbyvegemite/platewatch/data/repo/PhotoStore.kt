package com.deathbyvegemite.platewatch.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Crops live in app-private storage, so nothing turns up in the phone's gallery and
 * nothing survives uninstalling the app.
 */
class PhotoStore(context: Context) {

    private val root: File = File(context.filesDir, "sightings").apply { mkdirs() }

    fun save(bitmap: Bitmap, name: String, quality: Int = 85): String? = try {
        val file = File(root, "$name.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        file.absolutePath
    } catch (e: Exception) {
        Log.w(TAG, "Could not write crop $name", e)
        null
    }

    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching { File(path).delete() }
    }

    /** Files left behind by rows that were removed without going through [delete]. */
    fun deleteOrphans(keepPaths: Set<String>): Int {
        val files = root.listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (f.absolutePath !in keepPaths && f.delete()) removed++
        }
        return removed
    }

    fun totalBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    private companion object { const val TAG = "PhotoStore" }
}
