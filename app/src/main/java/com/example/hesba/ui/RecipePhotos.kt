package com.example.hesba.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/** Photos des recettes : stockage privé de l'appli, photos/recette_<id>.jpg (format repris d'Eya). */
object RecipePhotos {

    fun file(context: Context, recipeId: Long) = File(File(context.filesDir, "photos"), "recette_$recipeId.jpg")

    fun exists(context: Context, recipeId: Long) = file(context, recipeId).exists()

    fun save(context: Context, bitmap: Bitmap, recipeId: Long): Boolean = runCatching {
        val f = file(context, recipeId)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }.getOrDefault(false)

    fun copy(context: Context, from: Long, to: Long) {
        val src = file(context, from)
        if (src.exists()) runCatching { src.copyTo(file(context, to), overwrite = true) }
    }

    fun delete(context: Context, recipeId: Long) {
        file(context, recipeId).delete()
    }

    /** Lit une version réduite (côté le plus court ≈ [minSide] px). */
    fun thumbnail(context: Context, recipeId: Long, minSide: Int = 160): Bitmap? {
        val f = file(context, recipeId)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= minSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Décode une image choisie dans la galerie, réduite à [maxSize] px, orientation respectée. */
    fun decode(context: Context, uri: Uri, maxSize: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = maxSize.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()
}
