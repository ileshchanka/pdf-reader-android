package com.example.pdfreader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {

    /** PDF, пришедший извне через «Открыть с помощью». */
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // В полноэкранном режиме рисуем и в области выреза камеры.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (savedInstanceState == null) incomingUri = intent.pdfUri()

        setContent {
            AppTheme {
                App(incomingUri = incomingUri, onIncomingConsumed = { incomingUri = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.pdfUri()?.let { incomingUri = it }
    }

    private fun Intent?.pdfUri(): Uri? = if (this?.action == Intent.ACTION_VIEW) data else null
}

@Composable
private fun App(incomingUri: Uri?, onIncomingConsumed: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var openUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            openUri = incomingUri
            onIncomingConsumed()
        }
    }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            error = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Сохраняем доступ, чтобы «Продолжить чтение» работало после перезапуска.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            openUri = uri
        }
    }

    val uri = openUri
    if (uri == null) {
        val last = remember(openUri) { prefs.lastFile() }
        HomeScreen(
            lastFile = last,
            snackbar = snackbar,
            onOpen = { picker.launch(arrayOf("application/pdf")) },
            onContinue = { openUri = it.uri },
        )
    } else {
        ReaderScreen(
            uri = uri,
            prefs = prefs,
            onClose = { openUri = null },
            onError = { message ->
                if (prefs.lastFile()?.uri == uri) prefs.clearLastFile()
                error = message
                openUri = null
            },
        )
    }
}

fun Context.findActivity(): Activity {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    error("Activity not found")
}
