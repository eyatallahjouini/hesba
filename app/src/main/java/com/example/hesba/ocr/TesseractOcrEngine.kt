package com.example.hesba.ocr

import android.content.Context
import android.graphics.Bitmap
import com.example.hesba.domain.invoice.OcrToken
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tesseract OCR (bibliothèque Tesseract4Android, Apache 2.0) avec le modèle arabe standard
 * « ara » de tessdata_fast (Apache 2.0, ≈1,4 Mo), copié des assets vers le stockage privé au premier usage.
 * Arabe moderne standard imprimé uniquement : pas de dialecte, pas d'écriture manuscrite fiable.
 */
class TesseractOcrEngine(private val context: Context) : OcrEngine {

    override val id = "tesseract-ara"
    private var api: TessBaseAPI? = null

    private fun dataDir(): File {
        val root = File(context.filesDir, "ocr")
        val tessdata = File(root, "tessdata").apply { mkdirs() }
        val model = File(tessdata, "ara.traineddata")
        if (!model.exists() || model.length() == 0L) {
            context.assets.open("tessdata/ara.traineddata").use { input -> model.outputStream().use { input.copyTo(it) } }
        }
        return root
    }

    private fun engine(): TessBaseAPI = api ?: TessBaseAPI().also {
        if (!it.init(dataDir().absolutePath, "ara")) {
            it.recycle()
            throw IllegalStateException("Tesseract init failed")
        }
        it.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        it.setVariable("preserve_interword_spaces", "1")
        api = it
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val t = engine()
        t.setImage(bitmap)
        t.getUTF8Text()   // lance la reconnaissance
        val tokens = mutableListOf<OcrToken>()
        val it = t.resultIterator
        if (it != null) {
            it.begin()
            do {
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) ?: continue
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) / 100f
                if (text.isNotBlank()) tokens += OcrToken(text.trim(), r.left, r.top, r.right, r.bottom, conf)
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            it.delete()
        }
        t.clear()
        OcrResult(id, tokens, rightToLeft = true)
    }

    override fun close() {
        api?.recycle()
        api = null
    }
}
