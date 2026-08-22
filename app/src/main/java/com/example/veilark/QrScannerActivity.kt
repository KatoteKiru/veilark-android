package com.example.veilark

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.veilark.diagnostics.TechnicalLogStore
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Self-contained QR scanner backed by CameraX and the bundled ML Kit model. The explicit
 * Preview/ImageAnalysis pipeline avoids device-specific controller/use-case negotiation.
 */
class QrScannerActivity : ComponentActivity() {
  private lateinit var previewView: PreviewView
  private var cameraProvider: ProcessCameraProvider? = null
  private var scanner: BarcodeScanner? = null
  private var analysisExecutor: ExecutorService? = null
  private var delivered = false
  private var frameFailureLogged = false

  private val cameraPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      startCamera()
    } else {
      finishWithError("Для сканирования QR разрешите доступ к камере")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    TechnicalLogStore.info("QR", "Открыт сканер QR-кода")
    runCatching {
      setContentView(createScannerView())
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
      ) {
        startCamera()
      } else {
        cameraPermission.launch(Manifest.permission.CAMERA)
      }
    }.onFailure { failure ->
      finishWithError(
        "Не удалось открыть сканер камеры (${failure.javaClass.simpleName})",
      )
    }
  }

  private fun createScannerView(): FrameLayout {
    previewView = PreviewView(this).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    val hint = TextView(this).apply {
      text = "Наведите камеру на QR-код"
      setTextColor(Color.WHITE)
      setBackgroundColor(0xB3000000.toInt())
      textSize = 16f
      gravity = Gravity.CENTER
      setPadding(24.dp, 14.dp, 24.dp, 14.dp)
    }
    val close = TextView(this).apply {
      text = "×"
      contentDescription = "Закрыть сканер"
      setTextColor(Color.WHITE)
      textSize = 34f
      gravity = Gravity.CENTER
      setBackgroundColor(0x66000000)
      setOnClickListener { finish() }
    }
    return FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      addView(
        previewView,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
      addView(
        hint,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
          Gravity.BOTTOM,
        ).apply {
          bottomMargin = 48.dp
          marginStart = 24.dp
          marginEnd = 24.dp
        },
      )
      addView(
        close,
        FrameLayout.LayoutParams(52.dp, 52.dp, Gravity.TOP or Gravity.END).apply {
          topMargin = 40.dp
          marginEnd = 16.dp
        },
      )
    }
  }

  private fun startCamera() {
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
      finishWithCameraFailure("QR-CAMERA-NOT-FOUND", IllegalStateException("camera feature absent"))
      return
    }
    val detector = runCatching {
      BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
          .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
          .build(),
      )
    }.getOrElse { failure ->
      finishWithCameraFailure("QR-SCANNER-INIT", failure)
      return
    }
    scanner = detector
    val worker = Executors.newSingleThreadExecutor { task ->
      Thread(task, "veilark-qr-analysis").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    analysisExecutor = worker
    val providerFuture = ProcessCameraProvider.getInstance(this)
    providerFuture.addListener(
      {
        if (isFinishing || isDestroyed || delivered) return@addListener
        runCatching {
          val provider = providerFuture.get()
          cameraProvider = provider
          val selector = when {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
              CameraSelector.DEFAULT_BACK_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
              CameraSelector.DEFAULT_FRONT_CAMERA
            else -> error("no compatible camera")
          }
          val preview = Preview.Builder().build().also { useCase ->
            useCase.surfaceProvider = previewView.surfaceProvider
          }
          val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { useCase ->
              useCase.setAnalyzer(worker) { image -> analyzeFrame(detector, image) }
            }
          provider.unbindAll()
          provider.bindToLifecycle(this@QrScannerActivity, selector, preview, analysis)
          TechnicalLogStore.info(
            "QR",
            "Камера запущена: ${if (selector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"}",
          )
        }.onFailure { failure ->
          finishWithCameraFailure("QR-CAMERA-BIND", failure)
        }
      },
      ContextCompat.getMainExecutor(this),
    )
  }

  @AndroidXOptIn(markerClass = [ExperimentalGetImage::class])
  private fun analyzeFrame(detector: BarcodeScanner, imageProxy: ImageProxy) {
    if (delivered) {
      imageProxy.close()
      return
    }
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
      imageProxy.close()
      return
    }
    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    detector.process(input)
      .addOnSuccessListener { barcodes ->
        barcodes.firstNotNullOfOrNull { barcode ->
          barcode.rawValue?.trim()?.takeIf(String::isNotEmpty)
        }?.let { value -> runOnUiThread { finishWithResult(value) } }
      }
      .addOnFailureListener { failure ->
        if (!frameFailureLogged) {
          frameFailureLogged = true
          TechnicalLogStore.warning("QR", "QR-FRAME: ${QrDiagnostics.safeFailureSummary(failure)}")
        }
      }
      .addOnCompleteListener { imageProxy.close() }
  }

  @Synchronized
  private fun finishWithResult(value: String) {
    if (delivered) return
    delivered = true
    TechnicalLogStore.info("QR", "QR-код распознан")
    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, value))
    finish()
  }

  @Synchronized
  private fun finishWithError(message: String) {
    if (delivered) return
    delivered = true
    TechnicalLogStore.warning("QR", message)
    setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
    finish()
  }

  private fun finishWithCameraFailure(code: String, failure: Throwable) {
    val summary = QrDiagnostics.safeFailureSummary(failure)
    TechnicalLogStore.error("QR", "$code: $summary")
    finishWithError("Не удалось запустить камеру · $code")
  }

  override fun onDestroy() {
    cameraProvider?.unbindAll()
    scanner?.close()
    analysisExecutor?.shutdownNow()
    super.onDestroy()
  }

  private val Int.dp: Int
    get() = (this * resources.displayMetrics.density).toInt()

  companion object {
    const val EXTRA_RESULT = "qr_result"
    const val EXTRA_ERROR = "qr_error"
  }
}
