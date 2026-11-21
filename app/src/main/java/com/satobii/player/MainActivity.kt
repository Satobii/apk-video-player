package com.satobii.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Bg = Color(0xFF101010)
private val SurfaceDark = Color(0xFF1D1D1D)
private val ThumbPlaceholder = Color(0xFF3A3A3A)
private val TextMain = Color(0xFFF4F4F4)
private val TextMuted = Color(0xFF9B9B9B)

private const val PREFS_NAME = "astra_player_prefs"
private const val KEY_PERF_MODE = "perf_mode"
private const val KEY_SORT_MODE = "sort_mode"
private const val KEY_SORT_ASC = "sort_asc"
private const val KEY_GRID_MODE = "grid_mode"

private object ThumbnailCache {
    private val maxCacheKb = ((Runtime.getRuntime().maxMemory() / 1024L) / 5L).toInt().coerceAtLeast(8192)
    val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
}

data class VideoEntry(
    val uri: Uri,
    val name: String,
    val album: String,
    val albumId: Long,
    val dateAdded: Long,
    val size: Long,
    val duration: Long,
    val isVideo: Boolean = true
)

data class AlbumEntry(
    val id: Long,
    val name: String,
    val count: Int,
    val cover: Uri?,
    val coverIsVideo: Boolean,
    val videoCount: Int,
    val photoCount: Int
)

enum class MediaFilter { ALL, VIDEOS, PHOTOS }

enum class SortMode { NAME, DATE, SIZE }
enum class PlaybackMode { REPEAT, AUTO_NEXT }
enum class PerformanceMode(val label: String, val thumbWidth: Int, val thumbHeight: Int, val animationsEnabled: Boolean) {
    LOW("Low (Fast, No Animation)", 128, 72, false),
    MEDIUM("Medium (Balanced)", 160, 96, true),
    HIGH("High (Sharp + Animation)", 320, 192, true)
}

private fun loadSavedPerfMode(context: Context): PerformanceMode {
    val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_PERF_MODE, PerformanceMode.MEDIUM.name)
    return PerformanceMode.entries.firstOrNull { it.name == saved } ?: PerformanceMode.MEDIUM
}

private fun savePerfMode(context: Context, mode: PerformanceMode) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_PERF_MODE, mode.name)
        .apply()
}

private fun loadSavedSortMode(context: Context): SortMode {
    val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SORT_MODE, SortMode.DATE.name)
    return SortMode.entries.firstOrNull { it.name == saved } ?: SortMode.DATE
}

private fun loadSavedSortAsc(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_SORT_ASC, false)
}

private fun loadSavedGridMode(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_GRID_MODE, true)
}

private fun saveGridMode(context: Context, grid: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_GRID_MODE, grid)
        .apply()
}

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasAnyMediaPermission(context: Context): Boolean =
    mediaPermissions().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun saveSortSettings(context: Context, mode: SortMode, ascending: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SORT_MODE, mode.name)
        .putBoolean(KEY_SORT_ASC, ascending)
        .apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 16, 16)
        window.navigationBarColor = android.graphics.Color.rgb(16, 16, 16)
        AppHolder.resolver = contentResolver
        setContent { AstraApp() }
    }
}

@Composable
private fun AstraApp() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasAnyMediaPermission(context)) }
    var albums by remember { mutableStateOf<List<AlbumEntry>>(emptyList()) }
    var allVideos by remember { mutableStateOf<List<VideoEntry>>(emptyList()) }
    var selectedAlbum by remember { mutableStateOf<Long?>(null) }
    var screen by remember { mutableStateOf("albums") }
    var sortMode by remember { mutableStateOf(loadSavedSortMode(context)) }
    var ascending by remember { mutableStateOf(loadSavedSortAsc(context)) }
    var playbackMode by remember { mutableStateOf(PlaybackMode.REPEAT) }
    var perfMode by remember { mutableStateOf(loadSavedPerfMode(context)) }
    var activeVideos by remember { mutableStateOf<List<VideoEntry>>(emptyList()) }
    var startAt by remember { mutableIntStateOf(0) }
    var activePhotos by remember { mutableStateOf<List<VideoEntry>>(emptyList()) }
    var photoStart by remember { mutableIntStateOf(0) }
    var mediaFilter by remember { mutableStateOf(MediaFilter.ALL) }
    var gridMode by remember { mutableStateOf(loadSavedGridMode(context)) }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var miniPlayerActive by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableIntStateOf(0) }

    val updatePerfMode: (PerformanceMode) -> Unit = { mode ->
        perfMode = mode
        savePerfMode(context, mode)
    }

    val updateSort: (SortMode) -> Unit = { mode ->
        if (sortMode == mode) {
            ascending = !ascending
        } else {
            sortMode = mode
            ascending = mode == SortMode.NAME
        }
        saveSortSettings(context, sortMode, ascending)
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasPermission = hasAnyMediaPermission(context)
        if (hasPermission) refreshToken++
    }

    // Kalau app lama dah ada permission video je, minta permission gambar sekali.
    LaunchedEffect(Unit) {
        val missing = mediaPermissions().filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (hasPermission && missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(hasPermission, refreshToken) {
        if (hasPermission) {
            val result = withContext(Dispatchers.IO) { queryMedia() }
            allVideos = result
            albums = withContext(Dispatchers.Default) {
                result.groupBy { it.albumId }
                    .map { (id, items) ->
                        val cover = items.maxByOrNull { it.dateAdded }
                        AlbumEntry(
                            id = id,
                            name = items.first().album,
                            count = items.size,
                            cover = cover?.uri,
                            coverIsVideo = cover?.isVideo ?: true,
                            videoCount = items.count { it.isVideo },
                            photoCount = items.count { !it.isVideo }
                        )
                    }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
            }
        }
    }

    val leavePlayerToPlaylist = {
        miniPlayerActive = activeVideos.isNotEmpty()
        screen = "videos"
    }

    val closeMiniPlayer = {
        miniPlayerActive = false
        player.pause()
    }

    BackHandler(enabled = hasPermission && screen == "player") {
        leavePlayerToPlaylist()
    }

    BackHandler(enabled = hasPermission && screen == "photo") {
        screen = "videos"
    }

    BackHandler(enabled = hasPermission && screen == "videos") {
        screen = "albums"
    }

    val sortedAlbumMedia by remember(allVideos, selectedAlbum, sortMode, ascending, mediaFilter) {
        derivedStateOf {
            val base = allVideos.filter {
                it.albumId == selectedAlbum && when (mediaFilter) {
                    MediaFilter.ALL -> true
                    MediaFilter.VIDEOS -> it.isVideo
                    MediaFilter.PHOTOS -> !it.isVideo
                }
            }
            sortVideos(base, sortMode, ascending)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = SurfaceDark,
            primary = Color.White,
            onBackground = TextMain,
            onSurface = TextMain
        )
    ) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            if (!hasPermission) {
                PermissionScreen {
                    permissionLauncher.launch(mediaPermissions())
                }
            } else {
                val renderScreenContent: @Composable (String) -> Unit = { currentScreen ->
                    when (currentScreen) {
                        "albums" -> AlbumScreen(
                            albums = albums,
                            total = allVideos.size,
                            perfMode = perfMode,
                            onSelectPerfMode = updatePerfMode,
                            onRefresh = { refreshToken++ }
                        ) { album ->
                            selectedAlbum = album.id
                            mediaFilter = MediaFilter.ALL
                            gridState.requestScrollToItem(0)
                            listState.requestScrollToItem(0)
                            screen = "videos"
                        }

                        "videos" -> AlbumContentScreen(
                            album = albums.firstOrNull { it.id == selectedAlbum }?.name ?: "Gallery",
                            items = sortedAlbumMedia,
                            filter = mediaFilter,
                            onFilter = { mediaFilter = it },
                            sortMode = sortMode,
                            ascending = ascending,
                            gridMode = gridMode,
                            onToggleGrid = {
                                gridMode = !gridMode
                                saveGridMode(context, gridMode)
                            },
                            perfMode = perfMode,
                            gridState = gridState,
                            listState = listState,
                            onBack = { screen = "albums" },
                            onSort = updateSort,
                            onOpen = { index ->
                                val item = sortedAlbumMedia.getOrNull(index)
                                if (item != null) {
                                    if (item.isVideo) {
                                        val queue = sortedAlbumMedia.filter { it.isVideo }
                                        activeVideos = queue
                                        startAt = queue.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
                                        miniPlayerActive = false
                                        screen = "player"
                                    } else {
                                        val queue = sortedAlbumMedia.filter { !it.isVideo }
                                        activePhotos = queue
                                        photoStart = queue.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
                                        screen = "photo"
                                    }
                                }
                            }
                        )

                        "photo" -> PhotoViewerScreen(
                            photos = activePhotos,
                            initialIndex = photoStart,
                            onBack = { screen = "videos" }
                        )

                        "player" -> VideoPlayerScreen(
                            videos = activeVideos,
                            initialIndex = startAt,
                            player = player,
                            playbackMode = playbackMode,
                            perfMode = perfMode,
                            onModeChange = {
                                playbackMode = if (playbackMode == PlaybackMode.REPEAT) PlaybackMode.AUTO_NEXT else PlaybackMode.REPEAT
                            },
                            onBack = leavePlayerToPlaylist
                        )
                    }
                }

                if (perfMode.animationsEnabled && screen != "player" && screen != "photo") {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val goingForward = (initialState == "albums" && targetState == "videos") ||
                                (initialState == "videos" && targetState == "player")
                            if (goingForward) {
                                (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 7 }) togetherWith
                                    (fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { -it / 9 })
                            } else {
                                (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { -it / 7 }) togetherWith
                                    (fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { it / 9 })
                            }
                        },
                        label = "screen_transition"
                    ) { currentScreen ->
                        renderScreenContent(currentScreen)
                    }
                } else {
                    renderScreenContent(screen)
                }
            }

            val showMini = hasPermission && screen != "player" && miniPlayerActive
            if (perfMode.animationsEnabled) {
                AnimatedVisibility(
                    visible = showMini,
                    enter = fadeIn(tween(180)) + scaleIn(
                        initialScale = 0.88f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                    ) + slideInVertically(tween(200)) { it / 3 },
                    exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.9f, animationSpec = tween(140)) + slideOutVertically(tween(160)) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 14.dp, bottom = 76.dp)
                ) {
                    MiniPlayerOverlay(
                        player = player,
                        onExpand = {
                            val currentIdx = player.currentMediaItemIndex.coerceIn(0, (activeVideos.size - 1).coerceAtLeast(0))
                            startAt = currentIdx
                            miniPlayerActive = false
                            screen = "player"
                        },
                        onClose = closeMiniPlayer
                    )
                }
            } else if (showMini) {
                MiniPlayerOverlay(
                    player = player,
                    onExpand = {
                        val currentIdx = player.currentMediaItemIndex.coerceIn(0, (activeVideos.size - 1).coerceAtLeast(0))
                        startAt = currentIdx
                        miniPlayerActive = false
                        screen = "player"
                    },
                    onClose = closeMiniPlayer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 14.dp, bottom = 76.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerOverlay(
    player: ExoPlayer,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(168.dp)
            .height(98.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(12.dp))
            .clickable { onExpand() }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            update = { it.player = player }
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xAA000000))
                .clickable { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close mini player",
                tint = TextMain,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ASTRA PLAYER", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Allow photo & video access to show your phone albums.", color = TextMuted)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant) { Text("Allow access") }
    }
}

@Composable
private fun AlbumScreen(
    albums: List<AlbumEntry>,
    total: Int,
    perfMode: PerformanceMode,
    onSelectPerfMode: (PerformanceMode) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (AlbumEntry) -> Unit
) {
    var settingsMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("ASTRA PLAYER", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = 1.sp)
                Text("$total items · ${albums.size} albums", color = TextMuted, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRefresh) { Text("Refresh", color = TextMain) }
                Box {
                    IconButton(onClick = { settingsMenuOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextMain)
                    }
                    DropdownMenu(
                        expanded = settingsMenuOpen,
                        onDismissRequest = { settingsMenuOpen = false },
                        containerColor = SurfaceDark
                    ) {
                        Text(
                            "Performance",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        PerformanceMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label, color = TextMain, fontSize = 14.sp) },
                                trailingIcon = {
                                    if (perfMode == mode) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = TextMain)
                                    }
                                },
                                onClick = {
                                    onSelectPerfMode(mode)
                                    settingsMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos found.", color = TextMuted)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(albums, key = { it.id }) { album ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(14.dp))
                            .clickable { onSelect(album) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val cover = album.cover
                        if (cover != null) {
                            MediaThumbnail(
                                uri = cover,
                                isVideo = album.coverIsVideo,
                                perfMode = perfMode,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                                square = true
                            )
                        } else {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = TextMuted, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(album.name, color = TextMain, fontWeight = FontWeight.Medium)
                            val counts = buildList {
                                if (album.videoCount > 0) add("${album.videoCount} videos")
                                if (album.photoCount > 0) add("${album.photoCount} photos")
                            }.joinToString(" · ")
                            Text(counts, color = TextMuted, fontSize = 12.sp)
                        }
                        Text("›", color = TextMuted, fontSize = 28.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(
    uri: Uri,
    isVideo: Boolean,
    perfMode: PerformanceMode,
    modifier: Modifier = Modifier,
    square: Boolean = false
) {
    val context = LocalContext.current
    val thumbW = if (square) perfMode.thumbWidth * 2 else perfMode.thumbWidth
    val thumbH = if (square) perfMode.thumbWidth * 2 else perfMode.thumbHeight
    val cacheKey = remember(uri, thumbW, thumbH) { "${uri}_${thumbW}x$thumbH" }
    var bitmap by remember(cacheKey) { mutableStateOf(ThumbnailCache.cache.get(cacheKey)) }

    LaunchedEffect(cacheKey) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        context.contentResolver.loadThumbnail(uri, Size(thumbW, thumbH), null)
                    } else {
                        @Suppress("DEPRECATION")
                        if (isVideo) {
                            MediaStore.Video.Thumbnails.getThumbnail(
                                context.contentResolver,
                                ContentUris.parseId(uri),
                                MediaStore.Video.Thumbnails.MINI_KIND,
                                null
                            )
                        } else {
                            MediaStore.Images.Thumbnails.getThumbnail(
                                context.contentResolver,
                                ContentUris.parseId(uri),
                                MediaStore.Images.Thumbnails.MINI_KIND,
                                null
                            )
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (loaded != null) {
                ThumbnailCache.cache.put(cacheKey, loaded)
                bitmap = loaded
            }
        }
    }

    Box(
        modifier.background(ThumbPlaceholder),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = if (isVideo) Icons.Default.PlayArrow else Icons.Default.ImageIcon,
                contentDescription = null,
                tint = Color(0xFF9E9E9E),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) TextMain else SurfaceDark)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (selected) Bg else TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AlbumContentScreen(
    album: String,
    items: List<VideoEntry>,
    filter: MediaFilter,
    onFilter: (MediaFilter) -> Unit,
    sortMode: SortMode,
    ascending: Boolean,
    gridMode: Boolean,
    onToggleGrid: () -> Unit,
    perfMode: PerformanceMode,
    gridState: LazyGridState,
    listState: LazyListState,
    onBack: () -> Unit,
    onSort: (SortMode) -> Unit,
    onOpen: (Int) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextMain)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    album,
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("${items.size} items", color = TextMuted, fontSize = 12.sp)
            }
            IconButton(onClick = onToggleGrid) {
                Icon(
                    imageVector = if (gridMode) Icons.Default.ViewList else Icons.Default.GridView,
                    contentDescription = if (gridMode) "List view" else "Grid view",
                    tint = TextMain
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.Sort, contentDescription = "Sort", tint = TextMain)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Name ${if (sortMode == SortMode.NAME) directionLabel(ascending) else ""}") },
                        onClick = { onSort(SortMode.NAME); menuOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Date ${if (sortMode == SortMode.DATE) directionLabel(ascending) else ""}") },
                        onClick = { onSort(SortMode.DATE); menuOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Size ${if (sortMode == SortMode.SIZE) directionLabel(ascending) else ""}") },
                        onClick = { onSort(SortMode.SIZE); menuOpen = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("All", filter == MediaFilter.ALL) { onFilter(MediaFilter.ALL) }
            FilterPill("Videos", filter == MediaFilter.VIDEOS) { onFilter(MediaFilter.VIDEOS) }
            FilterPill("Photos", filter == MediaFilter.PHOTOS) { onFilter(MediaFilter.PHOTOS) }
        }
        Spacer(Modifier.height(10.dp))

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing here.", color = TextMuted)
            }
        } else if (gridMode) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                gridItemsIndexed(items, key = { _, item -> item.uri.toString() }) { index, item ->
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onOpen(index) }
                    ) {
                        MediaThumbnail(
                            uri = item.uri,
                            isVideo = item.isVideo,
                            perfMode = perfMode,
                            modifier = Modifier.fillMaxSize(),
                            square = true
                        )
                        if (item.isVideo) {
                            Row(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = TextMain,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(formatDuration(item.duration), color = TextMain, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.uri.toString() }) { index, item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(12.dp))
                            .clickable { onOpen(index) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MediaThumbnail(
                            uri = item.uri,
                            isVideo = item.isVideo,
                            perfMode = perfMode,
                            modifier = Modifier.width(116.dp).height(68.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                color = TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (item.isVideo) formatDuration(item.duration) else "Photo · ${formatBytes(item.size)}",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun clampOffset(offset: Offset, scale: Float, width: Float, height: Float): Offset {
    val maxX = (width * (scale - 1f)) / 2f
    val maxY = (height * (scale - 1f)) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

private fun decodePhoto(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    return try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / sample > maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun ZoomablePhoto(
    uri: Uri,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { decodePhoto(context, uri, 2400) }
    }
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(scale > 1f) { onZoomChanged(scale > 1f) }

    Box(
        Modifier
            .fillMaxSize()
            // Tap / double tap
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapPos ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = target
                            offset = clampOffset(
                                (tapPos - center) * (1f - target),
                                target,
                                size.width.toFloat(),
                                size.height.toFloat()
                            )
                        }
                    }
                )
            }
            // Pinch zoom + pan (cuma consume bila zoom / 2 jari, supaya swipe antara gambar masih jalan)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale <= 1f) {
                                Offset.Zero
                            } else {
                                clampOffset(
                                    offset + pan,
                                    newScale,
                                    size.width.toFloat(),
                                    size.height.toFloat()
                                )
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        } else {
            CircularProgressIndicator(color = TextMain, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun PhotoViewerScreen(
    photos: List<VideoEntry>,
    initialIndex: Int,
    onBack: () -> Unit
) {
    val safeInitial = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeInitial) { photos.size }
    var barsVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val current = photos.getOrNull(pagerState.currentPage)

    LaunchedEffect(pagerState.currentPage) { zoomed = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photos.getOrNull(page)
            if (photo != null) {
                ZoomablePhoto(
                    uri = photo.uri,
                    onTap = { barsVisible = !barsVisible },
                    onZoomChanged = { z -> if (page == pagerState.currentPage) zoomed = z }
                )
            }
        }

        if (barsVisible && current != null) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 8.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextMain)
                }
                Text(
                    text = current.name,
                    color = TextMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                Text(
                    text = "${pagerState.currentPage + 1} / ${photos.size}",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }
    }
}

private fun resolveDurationMs(player: ExoPlayer, fallbackDurationMs: Long): Long {
    val playerDur = player.duration
    return when {
        playerDur != C.TIME_UNSET && playerDur > 0L -> playerDur
        fallbackDurationMs > 0L -> fallbackDurationMs
        else -> 0L
    }
}

@Composable
private fun VideoPlayerScreen(
    videos: List<VideoEntry>,
    initialIndex: Int,
    player: ExoPlayer,
    playbackMode: PlaybackMode,
    perfMode: PerformanceMode,
    onModeChange: () -> Unit,
    onBack: () -> Unit
) {
    val safeInitial = initialIndex.coerceIn(0, (videos.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeInitial) { videos.size }
    val coroutineScope = rememberCoroutineScope()
    val activeVideo = videos.getOrNull(pagerState.currentPage)

    var controlsVisible by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var currentPos by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(activeVideo?.duration?.coerceAtLeast(0L) ?: 0L) }
    var touchTick by remember { mutableIntStateOf(0) }
    val rotations = remember { mutableStateMapOf<Uri, Float>() }
    val activeRotation = activeVideo?.let { rotations[it.uri] ?: 0f } ?: 0f
    var videoSize by remember { mutableStateOf(player.videoSize) }

    LaunchedEffect(videos) {
        if (videos.isEmpty()) return@LaunchedEffect
        val alreadyLoadedSameQueue = player.mediaItemCount == videos.size &&
            videos.indices.all { idx -> player.getMediaItemAt(idx).localConfiguration?.uri == videos[idx].uri }
        if (!alreadyLoadedSameQueue) {
            player.setMediaItems(videos.map { MediaItem.fromUri(it.uri) }, safeInitial, 0L)
            player.prepare()
        }
        player.playWhenReady = true
        player.play()
    }

    LaunchedEffect(playbackMode) {
        player.repeatMode = if (playbackMode == PlaybackMode.REPEAT) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
    }

    DisposableEffect(player, playbackMode, activeVideo?.duration) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentPos = 0L
                val fallback = videos.getOrNull(player.currentMediaItemIndex)?.duration ?: activeVideo?.duration ?: 0L
                duration = resolveDurationMs(player, fallback)
                if (playbackMode == PlaybackMode.AUTO_NEXT && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    val next = player.currentMediaItemIndex
                    if (next in videos.indices && next != pagerState.currentPage) {
                        coroutineScope.launch { pagerState.animateScrollToPage(next) }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val fallback = videos.getOrNull(player.currentMediaItemIndex)?.duration ?: activeVideo?.duration ?: 0L
                duration = resolveDurationMs(player, fallback)
                isPlaying = player.isPlaying
                if (playbackState == Player.STATE_ENDED && playbackMode == PlaybackMode.AUTO_NEXT) {
                    val nextIndex = (pagerState.currentPage + 1).takeIf { it < videos.size }
                    if (nextIndex != null) {
                        coroutineScope.launch { pagerState.animateScrollToPage(nextIndex) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val targetPage = pagerState.currentPage
                val pos = player.currentPosition.coerceAtLeast(0L)
                if (targetPage in videos.indices) {
                    player.seekTo(targetPage, pos)
                    player.prepare()
                    player.playWhenReady = true
                    player.play()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(pagerState.currentPage, activeVideo?.uri) {
        controlsVisible = false
        settingsOpen = false
        val targetPage = pagerState.currentPage
        val targetVideo = videos.getOrNull(targetPage)
        currentPos = 0L
        duration = targetVideo?.duration?.coerceAtLeast(0L) ?: 0L
        if (targetVideo != null && player.mediaItemCount == videos.size) {
            if (player.currentMediaItemIndex != targetPage) {
                player.seekTo(targetPage, 0L)
            }
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.playWhenReady = true
            player.play()
        }
    }

    LaunchedEffect(controlsVisible, settingsOpen, touchTick) {
        if (controlsVisible && !settingsOpen) {
            delay(3500)
            controlsVisible = false
        }
    }

    LaunchedEffect(player, controlsVisible, activeVideo?.duration) {
        while (controlsVisible) {
            currentPos = player.currentPosition.coerceAtLeast(0L)
            duration = resolveDurationMs(player, activeVideo?.duration ?: 0L)
            isPlaying = player.isPlaying
            delay(250)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) touchTick++
                        else settingsOpen = false
                    }
                )
            }
    ) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val pageVideo = videos.getOrNull(page)
            val isCurrentPage = page == pagerState.currentPage
            val pageRotation = if (isCurrentPage && pageVideo != null) (rotations[pageVideo.uri] ?: 0f) else 0f
            BoxWithConstraints(
                Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrentPage) {
                    val rotDeg = ((pageRotation.toInt() % 360) + 360) % 360
                    val isSideways = rotDeg == 90 || rotDeg == 270

                    // Aspect ratio video (ikut saiz sebenar video)
                    val vsRotated = videoSize.unappliedRotationDegrees % 180 != 0
                    val vw = (if (vsRotated) videoSize.height else videoSize.width).toFloat()
                    val vh = (if (vsRotated) videoSize.width else videoSize.height).toFloat()
                    val aspect = if (vw > 0f && vh > 0f) (vw * videoSize.pixelWidthHeightRatio) / vh else 0f

                    // Kalau rotate 90/270, ruang yang ada ditukar (lebar <-> tinggi)
                    val availW = if (isSideways) maxHeight else maxWidth
                    val availH = if (isSideways) maxWidth else maxHeight
                    val boxW: Dp
                    val boxH: Dp
                    if (aspect > 0f) {
                        if (availW.value / availH.value > aspect) {
                            boxH = availH
                            boxW = availH * aspect
                        } else {
                            boxW = availW
                            boxH = availW / aspect
                        }
                    } else {
                        boxW = availW
                        boxH = availH
                    }

                    AndroidView(
                        modifier = Modifier
                            .requiredSize(boxW, boxH)
                            .graphicsLayer { rotationZ = pageRotation },
                        factory = { ctx ->
                            TextureView(ctx).also { tv -> player.setVideoTextureView(tv) }
                        },
                        onRelease = { tv -> player.clearVideoTextureView(tv) }
                    )
                }
            }
        }

        val showTopBar = controlsVisible && activeVideo != null
        val topBarContent: @Composable () -> Unit = {
            if (activeVideo != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0x99000000))
                        .statusBarsPadding()
                        .padding(top = 12.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextMain)
                    }
                    Text(
                        text = activeVideo.name,
                        color = TextMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                    )
                    Box {
                        IconButton(onClick = {
                            settingsOpen = true
                            touchTick++
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextMain)
                        }
                        DropdownMenu(expanded = settingsOpen, onDismissRequest = { settingsOpen = false }) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text("Auto next", color = TextMain)
                                Switch(
                                    checked = playbackMode == PlaybackMode.AUTO_NEXT,
                                    onCheckedChange = {
                                        onModeChange()
                                        settingsOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (perfMode.animationsEnabled) {
            AnimatedVisibility(
                visible = showTopBar,
                enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { -it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { -it / 2 },
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                topBarContent()
            }
        } else if (showTopBar) {
            Box(Modifier.align(Alignment.TopStart)) {
                topBarContent()
            }
        }

        val showBottomBar = controlsVisible && activeVideo != null
        val bottomBarContent: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xAA000000))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val effectiveDuration = if (duration > 0L) duration else (activeVideo?.duration?.coerceAtLeast(0L) ?: 0L)
                val safeDuration = effectiveDuration.coerceAtLeast(1L)
                val progress = (currentPos.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                Slider(
                    value = progress,
                    onValueChange = { fraction ->
                        touchTick++
                        val target = (fraction * safeDuration).toLong()
                        currentPos = target
                        player.seekTo(target)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = TextMain,
                        activeTrackColor = TextMain,
                        inactiveTrackColor = Color(0x55FFFFFF)
                    )
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDuration(currentPos), color = TextMuted, fontSize = 12.sp)
                    Text(formatDuration(effectiveDuration), color = TextMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        touchTick++
                        val target = (player.currentPosition - 5000L).coerceAtLeast(0L)
                        player.seekTo(target)
                        currentPos = target
                    }) {
                        Icon(
                            Icons.Default.Replay5,
                            contentDescription = "Back 5s",
                            tint = TextMain,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = {
                        touchTick++
                        if (player.isPlaying) player.pause() else player.play()
                    }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = TextMain,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = {
                        touchTick++
                        val maxPos = if (effectiveDuration > 0L) effectiveDuration else player.currentPosition + 5000L
                        val target = (player.currentPosition + 5000L).coerceAtMost(maxPos)
                        player.seekTo(target)
                        currentPos = target
                    }) {
                        Icon(
                            Icons.Default.Forward5,
                            contentDescription = "Forward 5s",
                            tint = TextMain,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = {
                        touchTick++
                        val currentUri = activeVideo?.uri
                        if (currentUri != null) {
                            val currentDeg = rotations[currentUri] ?: 0f
                            rotations[currentUri] = (currentDeg + 90f) % 360f
                        }
                    }) {
                        Icon(
                            Icons.Default.RotateRight,
                            contentDescription = "Rotate video (${activeRotation.toInt()}°)",
                            tint = TextMain,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }

        if (perfMode.animationsEnabled) {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { it / 2 },
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                bottomBarContent()
            }
        } else if (showBottomBar) {
            Box(Modifier.align(Alignment.BottomStart)) {
                bottomBarContent()
            }
        }
    }
}

private fun launchFloatingWindow(context: Context, videos: List<VideoEntry>, index: Int) {
    if (!Settings.canDrawOverlays(context)) {
        Toast.makeText(context, "Allow display over other apps, then tap Float again.", Toast.LENGTH_LONG).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    }
    val intent = Intent(context, FloatingPlayerService::class.java).apply {
        action = FloatingPlayerService.ACTION_START
        putStringArrayListExtra(
            FloatingPlayerService.EXTRA_URIS,
            ArrayList(videos.map { it.uri.toString() })
        )
        putExtra(FloatingPlayerService.EXTRA_INDEX, index)
    }
    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
    else context.startService(intent)
}

private fun queryMedia(): List<VideoEntry> {
    val resolver = AppHolder.resolver ?: return emptyList()
    val videos = try { queryVideos(resolver) } catch (_: Exception) { emptyList() }
    val images = try { queryImages(resolver) } catch (_: Exception) { emptyList() }
    return videos + images
}

private fun queryVideos(resolver: android.content.ContentResolver): List<VideoEntry> {
    val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.BUCKET_ID,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION
    )
    val result = mutableListOf<VideoEntry>()
    resolver.query(collection, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(collection, id)
            result += VideoEntry(
                uri,
                cursor.getString(nameCol) ?: "Video",
                cursor.getString(albumCol) ?: "Other",
                cursor.getLong(bucketIdCol),
                cursor.getLong(dateCol),
                cursor.getLong(sizeCol),
                cursor.getLong(durationCol),
                true
            )
        }
    }
    return result
}

private fun queryImages(resolver: android.content.ContentResolver): List<VideoEntry> {
    val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.SIZE
    )
    val result = mutableListOf<VideoEntry>()
    resolver.query(collection, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(collection, id)
            result += VideoEntry(
                uri,
                cursor.getString(nameCol) ?: "Photo",
                cursor.getString(albumCol) ?: "Other",
                cursor.getLong(bucketIdCol),
                cursor.getLong(dateCol),
                cursor.getLong(sizeCol),
                0L,
                false
            )
        }
    }
    return result
}

private object AppHolder {
    var resolver: android.content.ContentResolver? = null
}

private fun sortVideos(videos: List<VideoEntry>, mode: SortMode, ascending: Boolean): List<VideoEntry> {
    val sorted = when (mode) {
        SortMode.NAME -> videos.sortedBy { it.name.lowercase(Locale.ROOT) }
        SortMode.DATE -> videos.sortedBy { it.dateAdded }
        SortMode.SIZE -> videos.sortedBy { it.size }
    }
    return if (ascending) sorted else sorted.reversed()
}

private fun directionLabel(ascending: Boolean) = if (ascending) "↑" else "↓"
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> "${bytes / 1_000} KB"
}
private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return "%02d:%02d".format(total / 60, total % 60)
}
