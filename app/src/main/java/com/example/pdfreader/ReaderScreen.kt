package com.example.pdfreader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.Orientation
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
private val PAGE_SPACING = 8.dp

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
            prefs.addRecent(RecentFile(uri, opened.name, opened.pageCount))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            onError(context.getString(R.string.error_protected))
        } catch (e: Exception) {
            onError(context.getString(R.string.error_open))
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
    var crop by remember { mutableStateOf(prefs.crop(uri)) }
    val context = LocalContext.current
    LaunchedEffect(crop) { if (crop) doc.prepareCrop() }
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
            crop = crop,
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // file:// нельзя передавать другим приложениям (FileUriExposedException)
                    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                        IconButton(onClick = { sharePdf(context, uri) }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                        }
                    }
                    IconButton(onClick = {
                        crop = !crop
                        prefs.setCrop(uri, crop)
                    }) {
                        Icon(
                            if (crop) Icons.Filled.CropFree else Icons.Filled.Crop,
                            contentDescription = stringResource(if (crop) R.string.show_margins else R.string.crop_margins),
                        )
                    }
                    IconButton(onClick = {
                        nightMode = !nightMode
                        prefs.nightMode = nightMode
                    }) {
                        Icon(
                            if (nightMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = stringResource(R.string.night_mode),
                        )
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
 * перерисовывается в нужном разрешении. Во время щипка зум применяется к раскладке каждый кадр
 * (при отдалении сразу видны соседние страницы), а битмапы перерисовываются после отпускания.
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
    crop: Boolean,
    contentPadding: PaddingValues,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = constraints.maxWidth
        val density = LocalDensity.current
        val spacingPx = with(density) { PAGE_SPACING.toPx() }
        val padTopPx = with(density) { contentPadding.calculateTopPadding().toPx() }

        // Горизонтальная прокрутка своя (а не horizontalScroll): её позицию можно выставить
        // в том же кадре, что и новый зум, — до того как раскладка узнает новую ширину.
        var offsetX by remember { mutableFloatStateOf(0f) }
        val currentMaxOffsetX by rememberUpdatedState(viewportWidth * (zoom - 1f))
        val hScrollState = rememberScrollableState { delta ->
            val old = offsetX
            offsetX = (old - delta).coerceIn(0f, currentMaxOffsetX)
            old - offsetX
        }

        // Последний применённый зум: за один кадр может прийти несколько событий,
        // а параметр zoom обновится только после рекомпозиции.
        val liveZoom = remember { floatArrayOf(zoom) }
        SideEffect { liveZoom[0] = zoom }

        // Во время щипка страницы не перерисовываются, а растягиваются из готовых битмапов.
        var pinching by remember { mutableStateOf(false) }
        var frozenRenderWidth by remember { mutableIntStateOf(0) }

        /**
         * Применяет новый зум к раскладке так, чтобы точка документа, бывшая в [origin],
         * оказалась в origin + [pan]. Зум и обе позиции прокрутки меняются разом и попадают
         * в один кадр — поэтому картинка не дёргается.
         */
        val applyZoom: (Float, Offset, Offset) -> Unit = { requested, origin, pan ->
            val oldZoom = liveZoom[0]
            val newZoom = requested.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val ratio = newZoom / oldZoom
            val newWidth = viewportWidth * newZoom

            offsetX = ((offsetX + origin.x) * ratio - origin.x - pan.x)
                .coerceIn(0f, viewportWidth * (newZoom - 1f))

            // Опорная страница — та, что под пальцами; высоты страниц при новом зуме известны точно.
            val items = listState.layoutInfo.visibleItemsInfo
            val anchor = items.firstOrNull { origin.y < it.offset + padTopPx + it.size + spacingPx }
                ?: items.lastOrNull()
            if (anchor != null) {
                val withinItem = origin.y - (anchor.offset + padTopPx)
                var index = anchor.index
                var top = origin.y + pan.y - withinItem * ratio // где должен оказаться верх опорной страницы
                while (top > padTopPx && index > 0) {
                    index--
                    top -= newWidth * doc.displayAspect(index, crop) + spacingPx
                }
                listState.requestScrollToItem(index, (padTopPx - top).roundToInt().coerceAtLeast(0))
            }

            liveZoom[0] = newZoom
            onZoomChange(newZoom)
        }
        val currentApplyZoom by rememberUpdatedState(applyZoom)
        val currentOnTap by rememberUpdatedState(onTap)

        val pageWidthPx = (viewportWidth * zoom).roundToInt()
        val renderWidthPx = if (pinching) frozenRenderWidth else pageWidthPx

        Box(
            Modifier
                .fillMaxSize()
                // Щипок: перехватываем в Initial-проходе, чтобы список не прокручивался двумя пальцами.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var started = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                if (!started) {
                                    started = true
                                    frozenRenderWidth = (viewportWidth * liveZoom[0]).roundToInt()
                                    pinching = true
                                }
                                // Все три функции учитывают только пальцы, лежавшие и в прошлом кадре,
                                // поэтому появление/подъём пальца не даёт скачка.
                                val frameZoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (frameZoom != 1f || pan != Offset.Zero) {
                                    val origin = event.calculateCentroid(useCurrent = false)
                                    if (origin.isSpecified) currentApplyZoom(liveZoom[0] * frameZoom, origin, pan)
                                }
                                event.changes.forEach { it.consume() }
                            } else if (started) {
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        pinching = false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { currentOnTap() },
                        onDoubleTap = { pos ->
                            currentApplyZoom(if (liveZoom[0] > 1.05f) 1f else DOUBLE_TAP_ZOOM, pos, Offset.Zero)
                        },
                    )
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollable(hScrollState, Orientation.Horizontal, enabled = zoom > 1f)
                    .clipToBounds()
                    // Список шире экрана в zoom раз; сдвигаем его на offsetX (читается только при размещении).
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            Constraints.fixed(pageWidthPx, constraints.maxHeight)
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(-offsetX.roundToInt(), 0)
                        }
                    },
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(PAGE_SPACING),
            ) {
                items(count = doc.pageCount, key = { it }) { index ->
                    PdfPage(doc, index, renderWidthPx, nightMode, crop)
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int, nightMode: Boolean, crop: Boolean) {
    // Округляем ширину рендера, чтобы не перерисовывать на каждое мелкое изменение зума.
    val renderWidth = (ceil(widthPx / 256f).toInt() * 256).coerceIn(256, MAX_RENDER_WIDTH)
    // Меняется, когда уточняется область обрезки, — тогда страницу нужно перерисовать.
    val cropKey = doc.cropKey(index, crop)
    var bitmap by remember(index, cropKey) {
        mutableStateOf<ImageBitmap?>(doc.peek(index, renderWidth, crop)?.asImageBitmap())
    }

    LaunchedEffect(index, renderWidth, cropKey) {
        doc.render(index, renderWidth, crop)?.let { bitmap = it.asImageBitmap() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / doc.displayAspect(index, crop))
            .background(if (nightMode) Color.Black else Color.White)
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.page_description, index + 1),
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
        title = { Text(stringResource(R.string.go_to_page_title)) },
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
        confirmButton = { TextButton(onClick = go, enabled = valid) { Text(stringResource(R.string.go_to_page_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Отправляет исходный content:// URI с временным правом чтения для получателя. */
private fun sharePdf(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
}
