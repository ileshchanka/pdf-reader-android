package com.example.pdfreader

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_RENDER_WIDTH = 2048

private val PanelColor = Color(0xE6202020)
private val ReaderBackground = Color(0xFF303030)

private val InvertFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

@Composable
fun ReaderScreen(uri: Uri, prefs: Prefs, onClose: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    var doc by remember(uri) { mutableStateOf<PdfDoc?>(null) }

    LaunchedEffect(uri) {
        try {
            val opened = PdfDoc.open(context, uri)
            doc = opened
            prefs.setLastFile(uri, opened.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            onError("Файл защищён паролем или нет доступа к нему")
        } catch (e: Exception) {
            onError("Не удалось открыть файл")
        }
    }
    DisposableEffect(doc) {
        val opened = doc
        onDispose { opened?.close() }
    }

    // Ридер всегда в тёмной гамме — меньше отвлекает от страницы.
    MaterialTheme(colorScheme = darkColorScheme()) {
        val opened = doc
        if (opened == null) {
            BackHandler(onBack = onClose)
            Box(Modifier.fillMaxSize().background(ReaderBackground), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            ReaderContent(opened, uri, prefs, onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderContent(doc: PdfDoc, uri: Uri, prefs: Prefs, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var nightMode by remember { mutableStateOf(prefs.nightMode) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var showGoTo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = prefs.page(uri).coerceIn(0, doc.pageCount - 1)
    )

    FullscreenEffect(fullscreen)

    // Текущая страница — та, что пересекает центр экрана.
    val currentPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.firstOrNull { it.offset <= center && it.offset + it.size > center }?.index
                ?: listState.firstVisibleItemIndex
        }
    }
    LaunchedEffect(currentPage) { prefs.savePage(uri, currentPage) }

    BackHandler {
        if (fullscreen) fullscreen = false else onClose()
    }

    // Бейдж с номером страницы в полноэкранном режиме — появляется при прокрутке и исчезает.
    var badgeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(currentPage, fullscreen) {
        if (fullscreen) {
            badgeVisible = true
            delay(1500)
        }
        badgeVisible = false
    }

    val bars = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
    val contentPadding = PaddingValues(
        top = bars.calculateTopPadding() + 64.dp,
        bottom = bars.calculateBottomPadding() + 72.dp,
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(if (fullscreen) Color.Black else ReaderBackground)
    ) {
        PdfPages(
            doc = doc,
            listState = listState,
            zoom = zoom,
            onZoomChange = { zoom = it },
            onTap = { fullscreen = !fullscreen },
            nightMode = nightMode,
            contentPadding = contentPadding,
        )

        AnimatedVisibility(
            visible = !fullscreen,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            TopAppBar(
                title = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        nightMode = !nightMode
                        prefs.nightMode = nightMode
                    }) {
                        Icon(
                            if (nightMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "Ночной режим",
                        )
                    }
                    IconButton(onClick = { fullscreen = true }) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = "Во весь экран")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PanelColor),
            )
        }

        AnimatedVisibility(
            visible = !fullscreen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BottomPanel(
                page = currentPage,
                pageCount = doc.pageCount,
                onPageClick = { showGoTo = true },
                onSeek = { page -> scope.launch { listState.scrollToItem(page) } },
            )
        }

        AnimatedVisibility(
            visible = fullscreen && badgeVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(color = Color(0x99000000), shape = RoundedCornerShape(50)) {
                Text(
                    "${currentPage + 1} / ${doc.pageCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = doc.pageCount,
            onDismiss = { showGoTo = false },
            onGo = { page ->
                showGoTo = false
                scope.launch { listState.scrollToItem(page) }
            },
        )
    }
}

/** Скрывает/показывает системные панели и не даёт экрану погаснуть, пока открыт ридер. */
@Composable
private fun FullscreenEffect(fullscreen: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity().window }
    val controller = remember(view) { WindowCompat.getInsetsController(window, view) }
    val systemDark = isSystemInDarkTheme()

    DisposableEffect(Unit) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !systemDark
            controller.isAppearanceLightNavigationBars = !systemDark
        }
    }
    LaunchedEffect(fullscreen) {
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun BottomPanel(page: Int, pageCount: Int, onPageClick: () -> Unit, onSeek: (Int) -> Unit) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    Surface(color = PanelColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = dragValue?.roundToInt() ?: page
            TextButton(onClick = onPageClick) {
                Text("${shown + 1} / $pageCount")
            }
            if (pageCount > 1) {
                Slider(
                    value = dragValue ?: page.toFloat(),
                    onValueChange = {
                        dragValue = it
                        onSeek(it.roundToInt())
                    },
                    onValueChangeFinished = { dragValue = null },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Список страниц с зумом.
 * Зум реально меняет ширину страниц (а не просто растягивает картинку), поэтому текст
 * перерисовывается в нужном разрешении, а прокрутка работает в обе стороны «родными» средствами.
 * Во время щипка показываем быстрый graphicsLayer-масштаб, а по отпусканию применяем зум к раскладке.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfPages(
    doc: PdfDoc,
    listState: LazyListState,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onTap: () -> Unit,
    nightMode: Boolean,
    contentPadding: PaddingValues,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = constraints.maxWidth
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val hScroll = rememberScrollState()
        var pinchScale by remember { mutableFloatStateOf(1f) }
        var pinchOrigin by remember { mutableStateOf(Offset.Zero) }

        val commitZoom: (Float, Offset) -> Unit = { requested, focus ->
            val newZoom = requested.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val ratio = newZoom / zoom
            if (ratio != 1f) {
                // Сохраняем под пальцем ту же точку документа.
                val newScrollX = ((hScroll.value + focus.x) * ratio - focus.x).roundToInt()
                val index = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                val newOffset = ((offset + focus.y) * ratio - focus.y).roundToInt().coerceAtLeast(0)
                onZoomChange(newZoom)
                scope.launch {
                    withFrameNanos { }
                    withFrameNanos { } // ждём перераскладку с новой шириной
                    hScroll.scrollTo(newScrollX)
                    listState.scrollToItem(index, newOffset)
                }
            }
        }
        val currentZoom by rememberUpdatedState(zoom)
        val currentCommit by rememberUpdatedState(commitZoom)
        val currentOnTap by rememberUpdatedState(onTap)

        Box(
            Modifier
                .fillMaxSize()
                // Щипок: перехватываем в Initial-проходе, чтобы список не прокручивался двумя пальцами.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pinching = false
                        var accumulated = 1f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                pinching = true
                                accumulated *= event.calculateZoom()
                                pinchScale = accumulated.coerceIn(MIN_ZOOM / currentZoom, MAX_ZOOM / currentZoom)
                                pinchOrigin = event.calculateCentroid(useCurrent = true)
                                event.changes.forEach { it.consume() }
                            } else if (pinching) {
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) {
                            currentCommit(currentZoom * pinchScale, pinchOrigin)
                            pinchScale = 1f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { currentOnTap() },
                        onDoubleTap = { pos ->
                            currentCommit(if (currentZoom > 1.05f) 1f else DOUBLE_TAP_ZOOM, pos)
                        },
                    )
                }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = pinchScale
                        scaleY = pinchScale
                        if (size.width > 0f && size.height > 0f) {
                            transformOrigin = TransformOrigin(
                                pinchOrigin.x / size.width,
                                pinchOrigin.y / size.height,
                            )
                        }
                    }
                    .horizontalScroll(hScroll, enabled = zoom > 1f)
            ) {
                val pageWidthPx = (viewportWidth * zoom).roundToInt()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(with(density) { pageWidthPx.toDp() })
                        .fillMaxHeight(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = doc.pageCount, key = { it }) { index ->
                        PdfPage(doc, index, pageWidthPx, nightMode)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int, nightMode: Boolean) {
    // Округляем ширину рендера, чтобы не перерисовывать на каждое мелкое изменение зума.
    val renderWidth = (ceil(widthPx / 256f).toInt() * 256).coerceIn(256, MAX_RENDER_WIDTH)
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(doc.peek(index, renderWidth)?.asImageBitmap()) }

    LaunchedEffect(index, renderWidth) {
        doc.render(index, renderWidth)?.let { bitmap = it.asImageBitmap() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / doc.aspect(index))
            .background(if (nightMode) Color.Black else Color.White)
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Страница ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = if (nightMode) InvertFilter else null,
            )
        }
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val number = text.toIntOrNull()
    val valid = number != null && number in 1..pageCount
    val go = { if (valid) onGo(number!! - 1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Перейти к странице") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(6) },
                label = { Text("1–$pageCount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
            )
        },
        confirmButton = { TextButton(onClick = go, enabled = valid) { Text("Перейти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
