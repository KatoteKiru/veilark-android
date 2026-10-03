package com.example.veilark

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.theme.VeilarkTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Self-contained QR scanner backed by CameraX and the pure-Java ZXing core. The explicit
 * Preview/ImageAnalysis pipeline avoids device-specific controller/use-case negotiation.
 */
class QrScannerActivity : ComponentActivity() {
  private lateinit var previewView: PreviewView
  private var cameraProvider: ProcessCameraProvider? = null
  private var analysisExecutor: ExecutorService? = null
  private var delivered = false
  private var frameFailureLogged = false
  private var cameraStartRequested = false
  private var permissionDialog: AlertDialog? = null

  private val cameraPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      startCamera()
    } else {
      showCameraPermissionGuidance()
    }
  }

  private val cameraSettings = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED
    ) {
      startCamera()
    } else {
      showCameraPermissionGuidance()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    TechnicalLogStore.info("QR", "QR scanner opened")
    runCatching {
      setContentView(createScannerView())
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
      ) {
        startCamera()
      } else {
        requestOrExplainCameraPermission()
      }
    }.onFailure { failure ->
      finishWithError(
        getString(R.string.qr_scanner_failed, failure.javaClass.simpleName),
      )
    }
  }

  private fun requestOrExplainCameraPermission() {
    if (cameraPermissionRequestCount() == 0) {
      requestCameraPermission()
    } else {
      showCameraPermissionGuidance()
    }
  }

  private fun requestCameraPermission() {
    val preferences = getPreferences(MODE_PRIVATE)
    val count = preferences.getInt(KEY_CAMERA_PERMISSION_REQUESTS, 0) + 1
    preferences.edit { putInt(KEY_CAMERA_PERMISSION_REQUESTS, count) }
    runCatching { cameraPermission.launch(Manifest.permission.CAMERA) }
      .onFailure { failure ->
        finishWithCameraFailure("QR-PERMISSION-REQUEST", failure)
      }
  }

  private fun cameraPermissionRequestCount(): Int =
    getPreferences(MODE_PRIVATE).getInt(KEY_CAMERA_PERMISSION_REQUESTS, 0)

  private fun showCameraPermissionGuidance() {
    if (isFinishing || isDestroyed || permissionDialog?.isShowing == true) return
    val guidance = cameraPermissionGuidance(
      deniedRequestCount = cameraPermissionRequestCount(),
      shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
        this,
        Manifest.permission.CAMERA,
      ),
    )
    val permanent = guidance == CameraPermissionGuidance.APP_SETTINGS
    permissionDialog = AlertDialog.Builder(this)
      .setTitle(R.string.qr_permission_required)
      .setMessage(
        if (permanent) R.string.qr_permission_settings_message
        else R.string.qr_permission_rationale_message,
      )
      .setNegativeButton(R.string.cancel) { _, _ ->
        finishWithError(getString(R.string.qr_permission_cancelled))
      }
      .setPositiveButton(
        if (permanent) R.string.qr_permission_open_settings
        else R.string.qr_permission_retry,
      ) { _, _ ->
        if (permanent) openCameraSettings() else requestCameraPermission()
      }
      .create()
      .also { dialog ->
        dialog.setOnCancelListener { finishWithError(getString(R.string.qr_permission_cancelled)) }
        dialog.setOnDismissListener { permissionDialog = null }
        dialog.show()
      }
  }

  private fun openCameraSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = "package:$packageName".toUri()
    }
    runCatching { cameraSettings.launch(intent) }
      .onFailure { failure ->
        finishWithCameraFailure("QR-PERMISSION-SETTINGS", failure)
      }
  }

  private fun createScannerView(): FrameLayout {
    previewView = PreviewView(this).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    val chrome = ComposeView(this).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        VeilarkTheme {
          QrScannerChrome(onClose = ::finish)
        }
      }
    }
    return FrameLayout(this).apply {
      setBackgroundColor(android.graphics.Color.BLACK)
      addView(
        previewView,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
      addView(
        chrome,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
    }
  }

  @OptIn(ExperimentalMaterial3ExpressiveApi::class)
  @Composable
  private fun QrScannerChrome(onClose: () -> Unit) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      // Expressive filled tonal icon button (round at rest, squircle on press).
      FilledTonalIconButton(
        onClick = onClose,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier
          .align(Alignment.TopEnd)
          .size(56.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
          contentColor = MaterialTheme.colorScheme.onSurface,
        ),
      ) {
        Icon(
          imageVector = Icons.Rounded.Close,
          contentDescription = stringResource(R.string.qr_scanner_close),
        )
      }
      Surface(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .widthIn(max = 560.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          // Scanning is ongoing work: the expressive loading indicator replaces
          // a static hint and signals that the camera is analysing frames.
          LoadingIndicator(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = stringResource(R.string.qr_scanner_hint),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
          )
        }
      }
    }
  }

  private fun startCamera() {
    if (cameraStartRequested || delivered || isFinishing || isDestroyed) return
    cameraStartRequested = true
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
      finishWithCameraFailure("QR-CAMERA-NOT-FOUND", IllegalStateException("camera feature absent"))
      return
    }
    val frameDecoder = QrFrameDecoder()
    val worker = Executors.newSingleThreadExecutor { task ->
      Thread(task, "veilark-qr-analysis").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    analysisExecutor = worker
    val providerFuture = runCatching {
      ProcessCameraProvider.getInstance(this)
    }.getOrElse { failure ->
      finishWithCameraFailure("QR-CAMERA-PROVIDER", failure)
      return
    }
    runCatching { providerFuture.addListener(
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
              useCase.setAnalyzer(worker) { image -> analyzeFrame(frameDecoder, image) }
            }
          provider.unbindAll()
          provider.bindToLifecycle(this@QrScannerActivity, selector, preview, analysis)
          TechnicalLogStore.info(
            "QR",
            "Camera started: ${if (selector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"}",
          )
        }.onFailure { failure ->
          finishWithCameraFailure("QR-CAMERA-BIND", failure)
        }
      },
      ContextCompat.getMainExecutor(this),
    ) }.onFailure { failure ->
      finishWithCameraFailure("QR-CAMERA-LISTENER", failure)
    }
  }

  @AndroidXOptIn(markerClass = [ExperimentalGetImage::class])
  private fun analyzeFrame(frameDecoder: QrFrameDecoder, imageProxy: ImageProxy) {
    if (delivered) {
      imageProxy.close()
      return
    }
    try {
      val mediaImage = imageProxy.image ?: return
      val yPlane = mediaImage.planes.firstOrNull() ?: return
      val crop = imageProxy.cropRect
      val frame = QrLuminance.fromYPlane(
        buffer = yPlane.buffer,
        imageWidth = mediaImage.width,
        imageHeight = mediaImage.height,
        rowStride = yPlane.rowStride,
        pixelStride = yPlane.pixelStride,
        cropLeft = crop.left,
        cropTop = crop.top,
        cropRight = crop.right,
        cropBottom = crop.bottom,
        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
      )
      frameDecoder.decode(frame)?.let { value ->
        if (!delivered && !isFinishing && !isDestroyed) {
          runOnUiThread { finishWithResult(value) }
        }
      }
    } catch (failure: Throwable) {
      // Camera reconfiguration and malformed plane metadata must never kill the analyzer thread.
      logFrameFailure(failure)
    } finally {
      imageProxy.close()
    }
  }

  @Synchronized
  private fun logFrameFailure(failure: Throwable) {
    if (frameFailureLogged) return
    frameFailureLogged = true
    TechnicalLogStore.warning("QR", "QR-FRAME: ${QrDiagnostics.safeFailureSummary(failure)}")
  }

  @Synchronized
  private fun finishWithResult(value: String) {
    if (delivered) return
    delivered = true
    TechnicalLogStore.info("QR", "QR code decoded")
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
    finishWithError(getString(R.string.qr_camera_start_failed, code))
  }

  override fun onDestroy() {
    permissionDialog?.dismiss()
    permissionDialog = null
    runCatching { cameraProvider?.unbindAll() }
    // CameraX may already have closed these resources after a failed
    // bind or an Activity recreation. Teardown must never turn that recoverable
    // scanner failure into a process crash.
    runCatching { analysisExecutor?.shutdownNow() }
    super.onDestroy()
  }
  companion object {
    private const val KEY_CAMERA_PERMISSION_REQUESTS = "camera_permission_requests"
    const val EXTRA_RESULT = "qr_result"
    const val EXTRA_ERROR = "qr_error"
  }
}

internal enum class CameraPermissionGuidance {
  RATIONALE,
  APP_SETTINGS,
}

/** Treat the first denial as recoverable even on Android versions that hide rationale initially. */
internal fun cameraPermissionGuidance(
  deniedRequestCount: Int,
  shouldShowRationale: Boolean,
): CameraPermissionGuidance =
  if (deniedRequestCount > 1 && !shouldShowRationale) {
    CameraPermissionGuidance.APP_SETTINGS
  } else {
    CameraPermissionGuidance.RATIONALE
  }
