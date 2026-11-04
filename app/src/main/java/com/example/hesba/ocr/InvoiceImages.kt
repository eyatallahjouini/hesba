package com.example.hesba.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Images de factures : tout reste dans le stockage privé (filesDir/factures), rien n'est envoyé.
 * Lecture d'une photo (orientation respectée) ou rendu des pages d'un PDF avec PdfRenderer.
 */
object InvoiceImages {

    const val MAX_SIDE = 2800
    const val MAX_PDF_PAGES = 4

    class PdfException(val reason: Reason) : Exception() {
        enum class Reason { CORRUPTED, PROTECTED, EMPTY }
    }

    fun dir(context: Context) = File(context.filesDir, "factures").apply { mkdirs() }

    fun newFile(context: Context, suffix: String = "") = File(dir(context), "facture_${System.currentTimeMillis()}$suffix.jpg")

    /** Copie une image choisie dans la galerie vers le stockage privé. Null si illisible. */
    fun importImage(context: Context, uri: Uri): File? {
        val bitmap = decodeUri(context, uri) ?: return null
        return save(context, bitmap)
    }

    fun save(context: Context, bitmap: Bitmap, suffix: String = ""): File? = runCatching {
        val f = newFile(context, suffix)
        FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        f
    }.getOrNull()

    /** Rend les premières pages d'un PDF en images (≈ 250 dpi), fond blanc. */
    fun importPdf(context: Context, uri: Uri): List<File> {
        val pfd: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r") ?: throw PdfException(PdfException.Reason.CORRUPTED)
        } catch (e: java.io.FileNotFoundException) { throw PdfException(PdfException.Reason.CORRUPTED) }
        pfd.use {
            val renderer = try { PdfRenderer(it) } catch (e: SecurityException) {
                throw PdfException(PdfException.Reason.PROTECTED)
            } catch (e: java.io.IOException) { throw PdfException(PdfException.Reason.CORRUPTED) }
            renderer.use { r ->
                if (r.pageCount == 0) throw PdfException(PdfException.Reason.EMPTY)
                val files = mutableListOf<File>()
                for (i in 0 until minOf(r.pageCount, MAX_PDF_PAGES)) {
                    r.openPage(i).use { page ->
                        val scale = minOf(250f / 72f, MAX_SIDE.toFloat() / maxOf(page.width, page.height))
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        save(context, bmp, "_p${i + 1}")?.let { f -> files += f }
                        bmp.recycle()
                    }
                }
                return files
            }
        }
    }

    /** Charge une image du stockage privé pour l'OCR (réduite si trop grande). */
    fun load(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return rotateByExif(file, bmp)
    }

    private fun decodeUri(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            val orientation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            bmp?.let { rotate(it, orientation ?: ExifInterface.ORIENTATION_NORMAL) }
        }
    }.getOrNull()

    private fun rotateByExif(file: File, bmp: Bitmap): Bitmap {
        val o = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return rotate(bmp, o)
    }

    private fun rotate(bmp: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }

    fun delete(files: List<File>) = files.forEach { it.delete() }
}
