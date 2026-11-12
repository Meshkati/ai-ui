package dev.aiui.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.Executors

/** Full-screen camera that closes with the text of the first QR code it sees. */
@Composable
fun QrScanDialog(hint: String, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                when (rememberCameraAccess()) {
                    CameraAccess.Granted -> {
                        QrViewfinder(
                            onDecoded = { text ->
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                onResult(text)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(248.dp)
                                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(28.dp)),
                        )
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(horizontal = 32.dp, vertical = 48.dp),
                        )
                    }
                    CameraAccess.Denied -> CameraDenied(Modifier.align(Alignment.Center))
                    CameraAccess.Asking -> Unit
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(4.dp),
                ) { Icon(Icons.Filled.Close, contentDescription = "Close scanner") }
            }
        }
    }
}

private enum class CameraAccess { Asking, Granted, Denied }

/** Asks for the camera once, and notices when it is allowed later from Settings. */
@Composable
private fun rememberCameraAccess(): CameraAccess {
    val context = LocalContext.current
    var access by remember { mutableStateOf(if (context.hasCamera()) CameraAccess.Granted else CameraAccess.Asking) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = if (granted) CameraAccess.Granted else CameraAccess.Denied
    }
    LaunchedEffect(Unit) {
        if (access == CameraAccess.Asking) request.launch(Manifest.permission.CAMERA)
    }
    LifecycleResumeEffect(Unit) {
        if (context.hasCamera()) access = CameraAccess.Granted
        onPauseOrDispose { }
    }
    return access
}

private fun Context.hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun CameraDenied(modifier: Modifier) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.padding(32.dp),
    ) {
        Text("Allow camera access to scan QR codes.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        FilledTonalButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text("Open settings") }
    }
}

@Composable
private fun QrViewfinder(onDecoded: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDecoded by rememberUpdatedState(onDecoded)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    LaunchedEffect(lifecycleOwner) {
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val decoded = Channel<String>(Channel.CONFLATED)
        val analyzer = Executors.newSingleThreadExecutor()
        analysis.setAnalyzer(analyzer) { frame -> frame.use { decodeQr(it)?.let(decoded::trySend) } }
        val cameras = ProcessCameraProvider.awaitInstance(context)
        try {
            cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            currentOnDecoded(decoded.receive())
        } finally {
            cameras.unbind(preview, analysis)
            analyzer.shutdown()
        }
    }

    surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = modifier) }
}

private fun decodeQr(frame: ImageProxy): String? {
    val brightness = frame.planes[0]
    val luminance = ByteArray(brightness.buffer.remaining()).also { brightness.buffer.get(it) }
    return decodeQr(luminance, brightness.rowStride, frame.width, frame.height)
}
