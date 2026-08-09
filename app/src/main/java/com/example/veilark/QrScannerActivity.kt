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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.veilark.diagnostics.TechnicalLogStore
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Self-contained QR scanner. It uses the bundled ML Kit model and CameraX's lifecycle
 * controller, so no Play Services module download or manually managed ImageProxy exists.
 */
class QrScannerActivity : ComponentActivity() {
  private lateinit var previewView: PreviewView
  private var cameraController: LifecycleCameraController? = null
  private var scanner: BarcodeScanner? = null
  private var delivered = false

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
    runCatching {
      val detector = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
          .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
          .build(),
      )
      scanner = detector
      val executor = ContextCompat.getMainExecutor(this)
      val controller = LifecycleCameraController(this)
      cameraController = controller
      previewView.controller = controller
      controller.apply {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        setImageAnalysisAnalyzer(
          executor,
          MlKitAnalyzer(
            listOf(detector),
            ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL,
            executor,
          ) { result ->
            result.getValue(detector)
              ?.firstNotNullOfOrNull { barcode ->
                barcode.rawValue?.trim()?.takeIf(String::isNotEmpty)
              }
              ?.let(::finishWithResult)
            result.getThrowable(detector)?.let {
              TechnicalLogStore.warning(
                "QR",
                "Кадр не обработан: ${it.javaClass.simpleName}",
              )
            }
          },
        )
        bindToLifecycle(this@QrScannerActivity)
      }
    }.onFailure { failure ->
      TechnicalLogStore.error(
        "QR",
        "Камера не запущена: ${failure.javaClass.simpleName}",
      )
      finishWithError("Не удалось запустить камеру (${failure.javaClass.simpleName})")
    }
  }

  @Synchronized
  private fun finishWithResult(value: String) {
    if (delivered) return
    delivered = true
    TechnicalLogStore.info("QR", "QR-код распознан")
    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, value))
    finish()
  }

  private fun finishWithError(message: String) {
    TechnicalLogStore.warning("QR", message)
    setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
    finish()
  }

  override fun onDestroy() {
    cameraController?.clearImageAnalysisAnalyzer()
    cameraController?.unbind()
    scanner?.close()
    super.onDestroy()
  }

  private val Int.dp: Int
    get() = (this * resources.displayMetrics.density).toInt()

  companion object {
    const val EXTRA_RESULT = "qr_result"
    const val EXTRA_ERROR = "qr_error"
  }
}
