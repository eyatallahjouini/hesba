package com.example.hesba

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.hesba.databinding.ActivityScanBinding
import com.example.hesba.ocr.InvoiceImages
import com.example.hesba.ocr.OcrLanguage
import com.example.hesba.ui.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Scanner une facture : photo (CameraX), image de la galerie ou PDF. Tout reste sur le téléphone. */
class ScanActivity : BaseActivity() {

    private lateinit var b: ActivityScanBinding
    private var capture: ImageCapture? = null
    private var askedOnce = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedOnce = true
        if (granted) startCamera() else showPermissionState()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { importImage(it) } }
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importPdf(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityScanBinding.inflate(layoutInflater)
        setScreen(b.root)
        setTitleText(getString(R.string.scan_title))
        // langue proposée selon la langue de l'appli
        if (savedInstanceState == null && resources.configuration.locales[0].language == "ar") b.groupLanguage.check(R.id.langAr)

        b.btnCapture.setOnClickListener { takePhoto() }
        b.btnGallery.setOnClickListener { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        b.btnPdf.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        b.btnPermission.setOnClickListener {
            if (!askedOnce || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) permission.launch(Manifest.permission.CAMERA)
            else startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onStart() {
        super.onStart()
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            b.txtPermission.setText(R.string.scan_no_camera); b.btnPermission.visibility = View.GONE
            b.blockPermission.visibility = View.VISIBLE; b.btnCapture.isEnabled = false
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (!askedOnce) permission.launch(Manifest.permission.CAMERA) else showPermissionState()
    }

    private fun language(): OcrLanguage = when (b.groupLanguage.checkedChipId) {
        R.id.langFr -> OcrLanguage.FRENCH
        R.id.langAr -> OcrLanguage.ARABIC
        else -> OcrLanguage.AUTO
    }

    private fun showPermissionState() {
        b.blockPermission.visibility = View.VISIBLE
        b.frame.visibility = View.GONE
        b.txtTip.visibility = View.GONE
        b.btnCapture.isEnabled = false
        val permanentlyDenied = askedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        b.txtPermission.setText(if (permanentlyDenied) R.string.scan_permission_settings_body else R.string.scan_permission_body)
        b.btnPermission.setText(if (permanentlyDenied) R.string.scan_permission_settings else R.string.scan_permission_allow)
    }

    private fun startCamera() {
        b.blockPermission.visibility = View.GONE
        b.frame.visibility = View.VISIBLE
        b.txtTip.visibility = View.VISIBLE
        b.btnCapture.isEnabled = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener cameraError()
            val preview = Preview.Builder().build().also { it.surfaceProvider = b.preview.surfaceProvider }
            val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
                capture = cap
            }.onFailure { cameraError() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cameraError() {
        b.blockPermission.visibility = View.VISIBLE
        b.txtPermission.setText(R.string.scan_camera_error)
        b.btnPermission.visibility = View.GONE
        b.btnCapture.isEnabled = false
    }

    private fun takePhoto() {
        val cap = capture ?: return
        val file = InvoiceImages.newFile(this)
        busy(true, R.string.scan_busy_photo)
        cap.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = review(listOf(file))
                override fun onError(e: ImageCaptureException) { busy(false); file.delete(); snack(R.string.scan_capture_failed) }
            })
    }

    private fun importImage(uri: Uri) {
        busy(true, R.string.scan_busy_import)
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { InvoiceImages.importImage(this@ScanActivity, uri) }
            if (f == null) { busy(false); snack(R.string.scan_image_unreadable, long = true) } else review(listOf(f))
        }
    }

    private fun importPdf(uri: Uri) {
        busy(true, R.string.scan_busy_pdf)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { InvoiceImages.importPdf(this@ScanActivity, uri) } }
            result.onSuccess { pages -> if (pages.isEmpty()) { busy(false); snack(R.string.scan_pdf_corrupted, long = true) } else review(pages) }
            result.onFailure { e ->
                busy(false)
                val msg = when ((e as? InvoiceImages.PdfException)?.reason) {
                    InvoiceImages.PdfException.Reason.PROTECTED -> R.string.scan_pdf_protected
                    InvoiceImages.PdfException.Reason.EMPTY -> R.string.scan_pdf_empty
                    else -> R.string.scan_pdf_corrupted
                }
                snack(msg, long = true)
            }
        }
    }

    private fun review(files: List<File>) {
        busy(false)
        startActivity(
            Intent(this, ScanReviewActivity::class.java)
                .putExtra(ScanReviewActivity.EXTRA_FILES, files.map { it.absolutePath }.toTypedArray())
                .putExtra(ScanReviewActivity.EXTRA_LANGUAGE, language().name)
        )
        finish()
    }

    private fun busy(on: Boolean, message: Int = 0) {
        b.blockBusy.visibility = if (on) View.VISIBLE else View.GONE
        if (on) b.txtBusy.setText(message)
        b.btnCapture.isEnabled = !on && capture != null
        b.btnGallery.isEnabled = !on
        b.btnPdf.isEnabled = !on
    }
}
