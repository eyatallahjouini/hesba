package com.example.hesba.ocr

import android.graphics.Bitmap
import com.example.hesba.domain.invoice.OcrToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit Text Recognition (écriture latine), modèle inclus dans l'APK : fonctionne hors ligne. */
class MlKitOcrEngine : OcrEngine {

    override val id = "mlkit-latin"
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val tokens = text.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        OcrToken(line.text, box.left, box.top, box.right, box.bottom, line.confidence)
                    }
                }
                cont.resume(OcrResult(id, tokens, rightToLeft = false))
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    override fun close() = recognizer.close()
}
