package com.satobii.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Bg = Color(0xFF101010)
private val SurfaceDark = Color(0xFF1D1D1D)
private val ThumbPlaceholder = Color(0xFF3A3A3A)
private val TextMain = Color(0xFFF4F4F4)
private val TextMuted = Color(0xFF9B9B9B)

data class VideoEntry(
    val uri: Uri,
    val name: String,
    val album: String,
    val albumId: Long,
    val dateAdded: Long,
    val size: Long,
    val duration: Long
)

data class AlbumEntry(val id: Long, val name: String, val count: Int, val cover: Uri?)

enum class SortMode { NAME, DATE, SIZE }
enum class PlaybackMode { REPEAT, AUTO_NEXT }

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
    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var albums by remember { mutableStateOf<List<AlbumEntry>>(emptyList()) }
    var allVideos by remember { mutableStateOf<List<VideoEntry>>(emptyList()) }
    var selectedAlbum by remember { mutableStateOf<Long?>(null) }
    var screen by remember { mutableStateOf("albums") }
    var sortMode by remember { mutableStateOf(SortMode.DATE) }
    var ascending by remember { mutableStateOf(false) }
    var playbackMode by remember { mutableStateOf(PlaybackMode.REPEAT) }
    var activeVideos by remember { mutableStateOf<List<VideoEntry>>(emptyList()) }
    var startAt by remember { mutableIntStateOf(0) }
    var refreshToken by remember { mutableIntStateOf(0) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) refreshToken++
    }

    LaunchedEffect(hasPermission, refreshToken) {
        if (hasPermission) {
            val result = withContext(Dispatchers.IO) { queryVideos() }
            allVideos = result
            albums = result.groupBy { it.albumId }
                .map { (id, items) -> AlbumEntry(id, items.first().album, items.size, items.firstOrNull()?.uri) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
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
        Surface(Modifier.fillMaxSize(), color = Bg) {
            if (!hasPermission) {
                PermissionScreen {
                    if (Build.VERSION.SDK_INT >= 33) {
                        permissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                    } else {
                        permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            } else when (screen) {
                "albums" -> AlbumScreen(albums, allVideos.size, onRefresh = { refreshToken++ }) { album ->
                    selectedAlbum = album.id
                    val videos = sortVideos(allVideos.filter { it.albumId == album.id }, sortMode, ascending)
                    activeVideos = videos
                    startAt = 0
                    screen = "videos"
                }

                "videos" -> VideoListScreen(
                    album = albums.firstOrNull { it.id == selectedAlbum }?.name ?: "Videos",
                    videos = sortVideos(allVideos.filter { it.albumId == selectedAlbum }, sortMode, ascending),
                    sortMode = sortMode,
                    ascending = ascending,
                    onBack = { screen = "albums" },
                    onSort = { mode ->
                        if (sortMode == mode) ascending = !ascending
                        else {
                            sortMode = mode
                            ascending = mode == SortMode.NAME
                        }
                    },
                    onPlay = { index ->
                        activeVideos = sortVideos(allVideos.filter { it.albumId == selectedAlbum }, sortMode, ascending)
                        startAt = index
                        screen = "player"
                    }
                )

                "player" -> VideoPlayerScreen(
                    videos = activeVideos,
                    initialIndex = startAt,
                    player = player,
                    playbackMode = playbackMode,
                    onModeChange = {
                        playbackMode = if (playbackMode == PlaybackMode.REPEAT) PlaybackMode.AUTO_NEXT else PlaybackMode.REPEAT
                    },
                    onBack = { screen = "videos" }
                )
            }
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
        Text("Allow video access to show your phone albums.", color = TextMuted)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant) { Text("Allow access") }
    }
}

@Composable
private fun AlbumScreen(
    albums: List<AlbumEntry>,
    total: Int,
    onRefresh: () -> Unit,
    onSelect: (AlbumEntry) -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("ASTRA PLAYER", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = 1.sp)
                Text("$total videos · ${albums.size} albums", color = TextMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onRefresh) { Text("Refresh", color = TextMain) }
        }
        Spacer(Modifier.height(16.dp))
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos found.", color = TextMuted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(albums, key = { it.id }) { album ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(14.dp))
                            .clickable { onSelect(album) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = TextMuted, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(album.name, color = TextMain, fontWeight = FontWeight.Medium)
                            Text("${album.count} videos", color = TextMuted, fontSize = 12.sp)
                        }
                        Text("›", color = TextMuted, fontSize = 28.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoThumbnail(uri: Uri) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(uri, Size(240, 160), null)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        Modifier.width(116.dp)
            .height(68.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ThumbPlaceholder),
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
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF9E9E9E),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun VideoListScreen(
    album: String,
    videos: List<VideoEntry>,
    sortMode: SortMode,
    ascending: Boolean,
    onBack: () -> Unit,
    onSort: (SortMode) -> Unit,
    onPlay: (Int) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextMain)
            }
            Column(Modifier.weight(1f)) {
                Text(album, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("${videos.size} videos", color = TextMuted, fontSize = 12.sp)
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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(videos, key = { it.uri.toString() }) { video ->
                val index = videos.indexOf(video)
                Row(
                    Modifier.fillMaxWidth()
                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                        .clickable { onPlay(index) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VideoThumbnail(uri = video.uri)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            video.name,
                            color = TextMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatDuration(video.duration),
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { onPlay(index) }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayerScreen(
    videos: List<VideoEntry>,
    initialIndex: Int,
    player: ExoPlayer,
    playbackMode: PlaybackMode,
    onModeChange: () -> Unit,
    onBack: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (videos.size - 1).coerceAtLeast(0))
    ) { videos.size }
    val coroutineScope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(videos, playbackMode) {
        player.repeatMode = if (playbackMode == PlaybackMode.REPEAT) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        player.clearMediaItems()
        videos.forEach { player.addMediaItem(MediaItem.fromUri(it.uri)) }
        player.prepare()
        player.seekTo(pagerState.currentPage, 0L)
        player.playWhenReady = true
        player.play()
    }

    DisposableEffect(player, playbackMode) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (playbackMode == PlaybackMode.AUTO_NEXT && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    val next = player.currentMediaItemIndex
                    if (next >= 0) coroutineScope.launch { pagerState.animateScrollToPage(next) }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (videos.isNotEmpty() && player.currentMediaItemIndex != pagerState.currentPage) {
            player.seekTo(pagerState.currentPage, 0L)
            player.playWhenReady = true
            player.play()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            Box(Modifier.fillMaxSize()) {
                if (page == pagerState.currentPage) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = true
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            }
                        },
                        update = { it.player = player }
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color(0x66000000))
                        .padding(16.dp)
                ) {
                    Text(videos[page].name, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${page + 1} / ${videos.size}", color = TextMuted, fontSize = 12.sp)
                }
            }
        }

        Row(
            Modifier.align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 12.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextMain)
            }
            Box {
                IconButton(onClick = { settingsOpen = true }) {
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

private fun queryVideos(): List<VideoEntry> {
    val resolver = AppHolder.resolver ?: return emptyList()
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
                cursor.getLong(durationCol)
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
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}
