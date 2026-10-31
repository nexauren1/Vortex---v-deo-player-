package com.nexauren.vortex

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.os.CancellationSignal
import android.util.Rational
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.nexauren.vortex.data.LocalVideo
import com.nexauren.vortex.data.VideoRepository
import com.nexauren.vortex.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Purple = Color(0xFF713BFF)
private val Blue = Color(0xFF168BFF)
private val Ink = Color(0xFF111323)
private val Muted = Color(0xFF85899C)
private val Pale = Color(0xFFF4F1FF)

class MainActivity : ComponentActivity() {
    var isInPipMode by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        setContent { VortexTheme { VortexRoot(this) } }
    }

    fun enterVortexPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    fun setPlayerLandscape(landscape: Boolean) {
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    fun restoreDefaultOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onPictureInPictureModeChanged(value: Boolean, config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(value, config)
        isInPipMode = value
    }
}

private enum class Tab(val title: String) {
    HOME("Início"), LIBRARY("Biblioteca"), FOLDERS("Pastas"), FAVORITES("Favoritos"), SETTINGS("Definições")
}

@Composable
private fun VortexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Purple, secondary = Blue, background = Color.White, surface = Color.White, onSurface = Ink),
        content = content
    )
}

@Composable
private fun VortexRoot(activity: MainActivity) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vortex_preferences", Context.MODE_PRIVATE) }
    val repository = remember { VideoRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var welcomeDone by remember { mutableStateOf(prefs.getBoolean("welcome_done", false)) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var videos by remember { mutableStateOf<List<LocalVideo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var permission by remember { mutableStateOf(hasVideoPermission(context)) }
    var selectedVideo by remember { mutableStateOf<LocalVideo?>(null) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var pipEnabled by remember { mutableStateOf(prefs.getBoolean("pip_enabled", true)) }
    var autoPlayNext by remember { mutableStateOf(prefs.getBoolean("auto_play_next", true)) }
    var backgroundPlayback by remember { mutableStateOf(prefs.getBoolean("background_playback", true)) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", true)) }
    var historyRefresh by remember { mutableIntStateOf(0) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorite_ids", emptySet())?.toSet() ?: emptySet()) }

    fun scan() {
        scope.launch {
            scanning = true
            runCatching { repository.loadVideos() }.onSuccess { videos = it; permission = true }.onFailure { permission = false }
            scanning = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permission = hasVideoPermission(context)
        if (permission) scan()
    }

    fun requestAccess() { permissionLauncher.launch(videoPermissions()) }

    val localVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    var title = "Vídeo local"
                    var size = 0L
                    var duration = 0L
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0) title = cursor.getString(nameIndex) ?: title
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        }
                    }
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (_: Exception) {
                        duration = 0L
                    } finally {
                        retriever.release()
                    }
                    LocalVideo(
                        id = uri.toString().hashCode().toLong(),
                        title = title,
                        uri = uri,
                        durationMs = duration,
                        sizeBytes = size,
                        folder = "Ficheiros abertos",
                        dateAddedSeconds = System.currentTimeMillis() / 1000L
                    )
                }
                selectedVideo = imported
            }
        }
    }

    LaunchedEffect(welcomeDone) {
        if (welcomeDone) {
            if (hasVideoPermission(context)) scan() else requestAccess()
        }
    }

    fun toggleFavorite(video: LocalVideo) {
        val id = video.id.toString()
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putStringSet("favorite_ids", favorites).apply()
    }

    fun moveInLibrary(step: Int) {
        val current = selectedVideo ?: return
        val index = videos.indexOfFirst { it.id == current.id }
        val nextIndex = if (index < 0) {
            if (step > 0) 0 else -1
        } else {
            index + step
        }
        if (nextIndex in videos.indices) selectedVideo = videos[nextIndex]
        else if (step > 0 && autoPlayNext) selectedVideo = null
    }

    val resumeVideo = remember(videos, historyRefresh) {
        val ids = prefs.getString("history_order", "").orEmpty().split("|").filter { it.isNotBlank() }
        ids.firstNotNullOfOrNull { savedId -> videos.firstOrNull { it.id.toString() == savedId } }
    }
    val resumePosition = resumeVideo?.let { prefs.getLong("watch_pos_${it.id}", 0L) } ?: 0L
    val recentlyWatched = remember(videos, historyRefresh) {
        prefs.getString("history_order", "").orEmpty().split("|")
            .filter { it.isNotBlank() }
            .mapNotNull { savedId -> videos.firstOrNull { it.id.toString() == savedId } }
            .distinctBy { it.id }
            .take(8)
    }

    BackHandler(enabled = selectedVideo != null) {
        selectedVideo = null
        historyRefresh++
    }

    if (!welcomeDone) {
        WelcomeScreen {
            welcomeDone = true
            prefs.edit().putBoolean("welcome_done", true).apply()
        }
        return
    }

    selectedVideo?.let {
        PlayerScreen(
            activity = activity,
            video = it,
            pipEnabled = pipEnabled,
            autoPlayNext = autoPlayNext,
            backgroundPlayback = backgroundPlayback,
            keepScreenOn = keepScreenOn,
            prefs = prefs,
            onPrevious = { moveInLibrary(-1) },
            onNext = { moveInLibrary(1) },
            onBack = {
                selectedVideo = null
                historyRefresh++
            }
        )
        return
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            if (!activity.isInPipMode) {
                NavigationBar(containerColor = Color.White, tonalElevation = 5.dp) {
                    Tab.values().forEach { item ->
                        val icon = when (item) {
                            Tab.HOME -> Icons.Default.Home
                            Tab.LIBRARY -> Icons.Default.VideoLibrary
                            Tab.FOLDERS -> Icons.Default.Folder
                            Tab.FAVORITES -> Icons.Default.FavoriteBorder
                            Tab.SETTINGS -> Icons.Default.Settings
                        }
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item; if (item != Tab.LIBRARY) selectedFolder = null },
                            icon = { Icon(icon, contentDescription = item.title) },
                            label = { Text(item.title, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Purple, selectedTextColor = Purple, indicatorColor = Pale,
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            TopBar(
                if (tab == Tab.LIBRARY && selectedFolder != null) selectedFolder!! else tab.title,
                onSearch = { tab = Tab.LIBRARY },
                onRefresh = { if (hasVideoPermission(context)) scan() else requestAccess() },
                onOpenFile = { localVideoPicker.launch(arrayOf("video/*")) }
            )
            when (tab) {
                Tab.HOME -> HomeScreen(videos, scanning, permission, resumeVideo, resumePosition, recentlyWatched,
                    onRefresh = { if (hasVideoPermission(context)) scan() else requestAccess() },
                    onOpenFile = { localVideoPicker.launch(arrayOf("video/*")) },
                    onOpen = { selectedVideo = it }, onNavigate = { tab = it })
                Tab.LIBRARY -> LibraryScreen(
                    videos.filter { selectedFolder == null || it.folder == selectedFolder }, favorites,
                    onOpen = { selectedVideo = it }, onFavorite = ::toggleFavorite
                )
                Tab.FOLDERS -> FoldersScreen(videos) { selectedFolder = it; tab = Tab.LIBRARY }
                Tab.FAVORITES -> FavoritesScreen(videos.filter { it.id.toString() in favorites },
                    onOpen = { selectedVideo = it }, onFavorite = ::toggleFavorite)
                Tab.SETTINGS -> SettingsScreen(
                    pipEnabled = pipEnabled,
                    onPipChange = {
                        pipEnabled = it
                        prefs.edit().putBoolean("pip_enabled", it).apply()
                    },
                    autoPlayNext = autoPlayNext,
                    onAutoPlayNextChange = {
                        autoPlayNext = it
                        prefs.edit().putBoolean("auto_play_next", it).apply()
                    },
                    backgroundPlayback = backgroundPlayback,
                    onBackgroundPlaybackChange = {
                        backgroundPlayback = it
                        prefs.edit().putBoolean("background_playback", it).apply()
                    },
                    keepScreenOn = keepScreenOn,
                    onKeepScreenOnChange = {
                        keepScreenOn = it
                        prefs.edit().putBoolean("keep_screen_on", it).apply()
                    },
                    onClearHistory = {
                        prefs.getString("history_order", "").orEmpty().split("|").filter { it.isNotBlank() }.forEach { id ->
                            prefs.edit().remove("watch_pos_$id").remove("watch_duration_$id").apply()
                        }
                        prefs.edit().remove("history_order").apply()
                        historyRefresh++
                    },
                    onRescan = { if (hasVideoPermission(context)) scan() else requestAccess() }
                )
            }
        }
    }
}

private fun hasVideoPermission(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= 34 ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    Build.VERSION.SDK_INT >= 33 ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    else ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

private fun videoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
private fun VortexLogo(size: androidx.compose.ui.unit.Dp = 54.dp) {
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF080B22), Color(0xFF271078), Color(0xFF071F4C))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(size * 0.15f)) {
            val w = this.size.width
            val h = this.size.height
            val ribbon = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.14f, h * 0.05f)
                cubicTo(w * 0.04f, h * 0.00f, w * 0.00f, h * 0.10f, w * 0.02f, h * 0.24f)
                lineTo(w * 0.02f, h * 0.76f)
                cubicTo(w * 0.02f, h * 0.93f, w * 0.09f, h * 0.99f, w * 0.23f, h * 0.91f)
                lineTo(w * 0.88f, h * 0.58f)
                cubicTo(w * 1.03f, h * 0.51f, w * 1.03f, h * 0.45f, w * 0.88f, h * 0.38f)
                lineTo(w * 0.14f, h * 0.05f)
                close()
            }
            drawPath(
                ribbon,
                Brush.linearGradient(
                    listOf(Color(0xFF9D35FF), Color(0xFF4B3CFF), Color(0xFF00BFFF))
                )
            )
            val play = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.37f, h * 0.28f)
                cubicTo(w * 0.34f, h * 0.26f, w * 0.32f, h * 0.29f, w * 0.32f, h * 0.34f)
                lineTo(w * 0.32f, h * 0.66f)
                cubicTo(w * 0.32f, h * 0.71f, w * 0.35f, h * 0.73f, w * 0.39f, h * 0.70f)
                lineTo(w * 0.66f, h * 0.55f)
                cubicTo(w * 0.71f, h * 0.52f, w * 0.71f, h * 0.48f, w * 0.66f, h * 0.45f)
                lineTo(w * 0.37f, h * 0.28f)
                close()
            }
            drawPath(play, Color(0xFF10122F))
        }
    }
}
@Composable
private fun WelcomeScreen(onStart: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            VortexLogo(112.dp)
            Spacer(Modifier.height(22.dp))
            Text("VORTEX", fontSize = 37.sp, fontWeight = FontWeight.Black, color = Color(0xFF10111A), letterSpacing = 1.5.sp)
            Text("VIDEO PLAYER", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Purple, letterSpacing = 4.sp)
            Spacer(Modifier.height(12.dp))
            Text("Seu mundo em alta definição.", color = Ink, fontSize = 15.sp)
        }
        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(190.dp)) {
            val wave = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, size.height * .52f)
                cubicTo(size.width * .28f, size.height * .08f, size.width * .58f, size.height * .98f, size.width, size.height * .22f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(wave, Brush.linearGradient(listOf(Color(0xFF6C29F5), Color(0xFF178EF7))))
            val wave2 = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, size.height * .76f)
                cubicTo(size.width * .3f, size.height * .3f, size.width * .7f, size.height * 1.08f, size.width, size.height * .56f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(wave2, Brush.linearGradient(listOf(Color(0xFF2C21C9), Color(0xFF101B55))))
        }
        Button(
            onClick = onStart,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp).fillMaxWidth().padding(horizontal = 34.dp).height(54.dp),
            shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple)
        ) {
            Text("Começar", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.PlayArrow, contentDescription = null)
        }
    }
}

@Composable
private fun TopBar(title: String, onSearch: () -> Unit, onRefresh: () -> Unit, onOpenFile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("VORTEX  •  VIDEO PLAYER", fontSize = 9.sp, letterSpacing = 1.6.sp, color = Muted)
        }
        IconButton(onClick = onOpenFile) { Icon(Icons.Default.VideoFile, contentDescription = "Abrir ficheiro de vídeo", tint = Purple) }
        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Pesquisar", tint = Ink) }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = Ink) }
    }
}

@Composable
private fun HomeScreen(
    videos: List<LocalVideo>, scanning: Boolean, permission: Boolean,
    resumeVideo: LocalVideo?, resumePosition: Long, recentlyWatched: List<LocalVideo>,
    onRefresh: () -> Unit, onOpenFile: () -> Unit, onOpen: (LocalVideo) -> Unit, onNavigate: (Tab) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (videos.isEmpty()) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Pale)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    VortexLogo(66.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(if (scanning) "A procurar vídeos..." else "A tua biblioteca começa aqui", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(if (!permission) "Permite o acesso aos vídeos para organizar a biblioteca local." else "Ainda não foram encontrados vídeos neste dispositivo.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                        Text(if (!permission) "Permitir acesso" else "Procurar novamente")
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onOpenFile, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Abrir vídeo do dispositivo")
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F4FF))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VortexLogo(48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("O teu cinema pessoal", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("Os teus vídeos, organizados num só lugar.", fontSize = 11.sp, color = Muted)
                    }
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Purple)
                }
            }
            val folderCount = videos.map { it.folder }.distinct().size
            val storageUsed = videos.sumOf { it.sizeBytes }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                HomeStatCard("VÍDEOS", videos.size.toString(), Icons.Default.VideoLibrary, Modifier.weight(1f))
                HomeStatCard("PASTAS", folderCount.toString(), Icons.Default.FolderOpen, Modifier.weight(1f))
                HomeStatCard("OCUPADO", formatBytes(storageUsed), Icons.Default.Storage, Modifier.weight(1f))
            }
            val featured = resumeVideo ?: videos.first()
            val isResume = resumeVideo != null && resumePosition > 0L && resumePosition < featured.durationMs
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(if (isResume) "Continuar a assistir" else "Vídeo em destaque", "Abrir", { onOpen(featured) })
                Card(Modifier.fillMaxWidth().clickable { onOpen(featured) }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF11162D))) {
                    Box {
                        VideoThumbnail(featured, Modifier.fillMaxWidth().height(190.dp))
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000015)))).padding(16.dp)) {
                            Column {
                                Text(if (isResume) "RETOMAR DE ONDE FICASTE" else "VÍDEO NO TEU DISPOSITIVO", fontSize = 9.sp, color = Color(0xFFBDA8FF), letterSpacing = 1.5.sp)
                                Text(featured.title, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text((if (isResume) "Retomar em " + formatDuration(resumePosition) + " / " else "") + formatDuration(featured.durationMs) + "  •  " + featured.folder, fontSize = 11.sp, color = Color.White.copy(alpha = .8f))
                            }
                        }
                        Box(Modifier.align(Alignment.Center).size(54.dp).clip(CircleShape).background(Purple), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Reproduzir", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
                }
                if (isResume) {
                    val progress = (resumePosition.toFloat() / featured.durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).height(5.dp),
                        color = Purple,
                        trackColor = Color(0xFFE9E5F5)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Recentes", "Ver todos", { onNavigate(Tab.LIBRARY) })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(videos.take(12), key = { it.id }) { video ->
                        Column(Modifier.width(148.dp).clickable { onOpen(video) }) {
                            Box {
                                VideoThumbnail(video, Modifier.fillMaxWidth().height(92.dp))
                                Text(formatDuration(video.durationMs), Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xB9000010)).padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, fontSize = 9.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(video.title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(video.folder, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (recentlyWatched.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Vistos recentemente", null, null)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recentlyWatched, key = { "watched_${it.id}" }) { video ->
                            RecentWatchCard(video = video, onClick = { onOpen(video) })
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Explorar", null, null)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExploreTile("Biblioteca", Icons.Default.VideoLibrary, Modifier.weight(1f)) { onNavigate(Tab.LIBRARY) }
                    ExploreTile("Pastas", Icons.Default.FolderOpen, Modifier.weight(1f)) { onNavigate(Tab.FOLDERS) }
                    ExploreTile("Favoritos", Icons.Default.Favorite, Modifier.weight(1f)) { onNavigate(Tab.FAVORITES) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RecentWatchCard(video: LocalVideo, onClick: () -> Unit) {
    Column(
        Modifier.width(132.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            VideoThumbnail(video, Modifier.fillMaxWidth().height(82.dp).clip(RoundedCornerShape(13.dp)))
            Text(
                formatDuration(video.durationMs),
                Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(5.dp))
                    .background(Color(0xC9000010)).padding(horizontal = 5.dp, vertical = 2.dp),
                color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold
            )
        }
        Text(video.title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Visto recentemente", color = Purple, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeStatCard(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F6FC))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 13.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Purple, modifier = Modifier.size(20.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, fontSize = 9.sp, color = Muted, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String?, onClick: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onClick != null) TextButton(onClick = onClick) { Text(action, color = Purple, fontSize = 12.sp) }
    }
}

@Composable
private fun ExploreTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F6FC))) {
        Column(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Purple, modifier = Modifier.size(27.dp))
            Spacer(Modifier.height(7.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
    }
}

@Composable
private fun LibraryScreen(videos: List<LocalVideo>, favorites: Set<String>, onOpen: (LocalVideo) -> Unit, onFavorite: (LocalVideo) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("Todos") }
    var sortBy by remember { mutableStateOf("Recentes") }
    var sortExpanded by remember { mutableStateOf(false) }
    var detailsVideo by remember { mutableStateOf<LocalVideo?>(null) }

    val filtered = remember(videos, query, selectedFilter, favorites) {
        val recentCutoff = System.currentTimeMillis() / 1000L - 7L * 24L * 60L * 60L
        videos.filter { video ->
            val matchesQuery = video.title.contains(query, ignoreCase = true) || video.folder.contains(query, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "Favoritos" -> video.id.toString() in favorites
                "Curtos" -> video.durationMs in 1L..600_000L
                "Longos" -> video.durationMs >= 1_200_000L
                "Recentes" -> video.dateAddedSeconds >= recentCutoff
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }
    val sorted = remember(filtered, sortBy) {
        when (sortBy) {
            "Nome A–Z" -> filtered.sortedBy { it.title.lowercase(Locale.getDefault()) }
            "Maior duração" -> filtered.sortedByDescending { it.durationMs }
            "Maior tamanho" -> filtered.sortedByDescending { it.sizeBytes }
            else -> filtered.sortedByDescending { it.dateAddedSeconds }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        OutlinedTextField(
            query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, contentDescription = "Limpar pesquisa")
                }
            },
            placeholder = { Text("Pesquisar vídeos ou pastas") }, shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(listOf("Todos", "Recentes", "Favoritos", "Curtos", "Longos")) { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter, fontSize = 11.sp) },
                    leadingIcon = if (selectedFilter == filter) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp)) }
                    } else null,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(sorted.size.toString() + " de " + videos.size + " vídeos", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { sortExpanded = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Default.Sort, contentDescription = null, tint = Purple, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(sortBy, color = Purple, fontSize = 11.sp)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Purple)
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    listOf("Recentes", "Nome A–Z", "Maior duração", "Maior tamanho").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { sortBy = option; sortExpanded = false },
                            leadingIcon = if (sortBy == option) ({ Icon(Icons.Default.Check, contentDescription = null, tint = Purple) }) else null
                        )
                    }
                }
            }
        }
        if (sorted.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                EmptyState(
                    if (videos.isEmpty()) "Biblioteca vazia" else "Nenhum resultado",
                    if (videos.isEmpty()) "Procura vídeos no dispositivo ou abre um ficheiro diretamente." else "Experimenta remover os filtros ou alterar a pesquisa.",
                    Icons.Default.VideoLibrary
                )
                if (query.isNotEmpty() || selectedFilter != "Todos") {
                    TextButton(onClick = { query = ""; selectedFilter = "Todos" }) { Text("Limpar filtros", color = Purple) }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(sorted, key = { it.id }) { video ->
                    Column {
                        Box {
                            VideoThumbnail(
                                video,
                                Modifier.fillMaxWidth().aspectRatio(1.42f).clip(RoundedCornerShape(15.dp)).clickable { onOpen(video) }
                            )
                            Text(
                                formatDuration(video.durationMs),
                                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xC9000010)).padding(horizontal = 5.dp, vertical = 2.dp),
                                color = Color.White, fontSize = 9.sp
                            )
                            Row(Modifier.align(Alignment.TopEnd)) {
                                IconButton(onClick = { detailsVideo = video }) {
                                    Icon(Icons.Default.Info, contentDescription = "Detalhes do vídeo", tint = Color.White)
                                }
                                IconButton(onClick = { onFavorite(video) }) {
                                    Icon(
                                        if (video.id.toString() in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Favorito",
                                        tint = if (video.id.toString() in favorites) Color(0xFFFF4D85) else Color.White
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(video.title, fontSize = 12.sp, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(video.folder + "  •  " + formatBytes(video.sizeBytes), fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    detailsVideo?.let { video ->
        AlertDialog(
            onDismissRequest = { detailsVideo = null },
            icon = { Icon(Icons.Default.Movie, contentDescription = null, tint = Purple) },
            title = { Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailLine("Pasta", video.folder)
                    DetailLine("Duração", formatDuration(video.durationMs))
                    DetailLine("Tamanho", formatBytes(video.sizeBytes))
                    DetailLine("Origem", "Armazenamento local")
                    DetailLine("Identificador", video.id.toString())
                }
            },
            confirmButton = {
                Button(onClick = { detailsVideo = null; onOpen(video) }, colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Reproduzir")
                }
            },
            dismissButton = { TextButton(onClick = { detailsVideo = null }) { Text("Fechar") } },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(86.dp))
        Text(value, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FoldersScreen(videos: List<LocalVideo>, onClick: (String) -> Unit) {
    val folders = videos.groupBy { it.folder }.toList().sortedBy { it.first.lowercase(Locale.getDefault()) }
    if (folders.isEmpty()) EmptyState("Nenhuma pasta encontrada", "As pastas com vídeos aparecerão aqui.", Icons.Default.FolderOpen)
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(folders, key = { it.first }) { pair ->
            Card(Modifier.fillMaxWidth().clickable { onClick(pair.first) }, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F7FC))) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Pale), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Purple, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pair.first, fontWeight = FontWeight.Bold, color = Ink)
                        Text(pair.second.size.toString() + " vídeos", fontSize = 11.sp, color = Muted)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(videos: List<LocalVideo>, onOpen: (LocalVideo) -> Unit, onFavorite: (LocalVideo) -> Unit) {
    if (videos.isEmpty()) EmptyState("Ainda sem favoritos", "Adiciona o coração aos vídeos que queres encontrar rapidamente.", Icons.Default.Favorite)
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(videos, key = { it.id }) { video ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VideoThumbnail(video, Modifier.size(width = 122.dp, height = 78.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(video) })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable { onOpen(video) }) {
                    Text(video.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(video.folder + "  •  " + formatDuration(video.durationMs), color = Muted, fontSize = 10.sp)
                }
                IconButton(onClick = { onFavorite(video) }) { Icon(Icons.Default.Favorite, contentDescription = "Remover favorito", tint = Color(0xFFFF4D85)) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    pipEnabled: Boolean,
    onPipChange: (Boolean) -> Unit,
    autoPlayNext: Boolean,
    onAutoPlayNextChange: (Boolean) -> Unit,
    backgroundPlayback: Boolean,
    onBackgroundPlaybackChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onRescan: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SettingsHeading("Reprodução")
        SettingSwitch(Icons.Default.PictureInPictureAlt, "Picture-in-Picture", "Continuar a ver enquanto utilizas outras aplicações", pipEnabled, onPipChange)
        SettingSwitch(Icons.Default.SkipNext, "Reprodução automática", "Passar para o próximo vídeo ao terminar", autoPlayNext, onAutoPlayNextChange)
        SettingSwitch(Icons.Default.Headphones, "Áudio em segundo plano", "Continuar a reprodução ao sair do Vortex", backgroundPlayback, onBackgroundPlaybackChange)
        SettingSwitch(Icons.Default.WbSunny, "Manter ecrã ligado", "Evitar que o ecrã desligue durante o vídeo", keepScreenOn, onKeepScreenOnChange)
        SettingRow(Icons.Default.Subtitles, "Legendas", "Faixas incorporadas e controlos no player")
        SettingRow(Icons.Default.Speed, "Velocidade de reprodução", "Controlos de velocidade no player")
        HorizontalDivider(color = Color(0xFFECECF2))
        SettingsHeading("Biblioteca")
        SettingRow(Icons.Default.Refresh, "Atualizar biblioteca", "Voltar a procurar vídeos neste dispositivo", onRescan)
        SettingRow(Icons.Default.History, "Limpar histórico", "Apagar posições de retoma guardadas", onClearHistory)
        SettingRow(Icons.Default.Lock, "Privacidade", "Os vídeos permanecem no teu dispositivo")
        SettingRow(Icons.Default.Cast, "Chromecast", "Transmissão de vídeos locais ainda não está disponível nesta versão")
        HorizontalDivider(color = Color(0xFFECECF2))
        SettingsHeading("Aplicação")
        SettingRow(Icons.Default.Language, "Idioma", "Português")
        SettingRow(Icons.Default.Info, "Sobre o Vortex", "Vortex – Video Player  •  versão 1.0.0")
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsHeading(title: String) {
    Text(title.uppercase(Locale.getDefault()), color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 9.dp, bottom = 2.dp))
}

@Composable
private fun SettingSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Purple, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(subtitle, fontSize = 10.sp, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Purple, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(subtitle, fontSize = 10.sp, color = Muted)
        }
        if (onClick != null) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, icon: ImageVector) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(86.dp).clip(CircleShape).background(Pale), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Purple, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(17.dp))
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

private object VideoThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(20 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun loadCached(uri: android.net.Uri): Bitmap? = cache.get(uri.toString())

    suspend fun load(context: Context, uri: android.net.Uri): Bitmap? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache.get(key)?.let { return@withContext it }
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(384, 216), CancellationSignal())
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.getFrameAtTime(1_000_000)
                } finally {
                    retriever.release()
                }
            }
        }.getOrNull()
        if (bitmap != null) cache.put(key, bitmap)
        bitmap
    }
}

@Composable
private fun VideoThumbnail(video: LocalVideo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(video.uri) { mutableStateOf<Bitmap?>(VideoThumbnailCache.loadCached(video.uri)) }
    LaunchedEffect(video.uri) {
        bitmap = VideoThumbnailCache.load(context, video.uri)
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF171A3A), Color(0xFF5B31B8))))) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = video.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.align(Alignment.Center).size(34.dp))
    }
}


@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(
    activity: MainActivity,
    video: LocalVideo,
    pipEnabled: Boolean,
    autoPlayNext: Boolean,
    backgroundPlayback: Boolean,
    keepScreenOn: Boolean,
    prefs: android.content.SharedPreferences,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controller by remember(video.id) { mutableStateOf<MediaController?>(null) }
    var position by remember(video.id) { mutableLongStateOf(0L) }
    var duration by remember(video.id) { mutableLongStateOf(video.durationMs.coerceAtLeast(1L)) }
    var playing by remember(video.id) { mutableStateOf(false) }
    var subtitles by remember(video.id) { mutableStateOf(false) }
    var hasSubtitleTracks by remember(video.id) { mutableStateOf(false) }
    var externalSubtitleName by remember(video.id) { mutableStateOf<String?>(null) }
    var pendingSubtitleName by remember(video.id) { mutableStateOf<String?>(null) }
    var subtitleStatus by remember(video.id) { mutableStateOf("A verificar legendas deste vídeo…") }
    var speed by remember(video.id) { mutableStateOf(1.0f) }
    var speedDialog by remember(video.id) { mutableStateOf(false) }
    var subtitleScale by remember(video.id) { mutableFloatStateOf(prefs.getFloat("subtitle_scale", 1.0f)) }
    var repeatOne by remember(video.id) { mutableStateOf(false) }
    var resizeMode by remember(video.id) { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var toolsExpanded by remember(video.id) { mutableStateOf(false) }
    var timerDialog by remember(video.id) { mutableStateOf(false) }
    var sleepMinutes by remember(video.id) { mutableStateOf<Int?>(null) }
    var dragging by remember(video.id) { mutableStateOf(false) }
    var dragPosition by remember(video.id) { mutableLongStateOf(0L) }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            subtitleStatus = "Nenhum ficheiro de legenda selecionado."
        } else {
            scope@ run {
                val displayName = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "legenda"

                val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val mimeType = when (extension) {
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    "vtt" -> MimeTypes.TEXT_VTT
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "ttml", "xml" -> MimeTypes.APPLICATION_TTML
                    else -> null
                }

                if (mimeType == null) {
                    subtitleStatus = "Formato não suportado. Usa SRT, VTT, ASS, SSA ou TTML."
                } else {
                    val readable = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input -> input.read() >= 0 } ?: false
                    }.getOrDefault(false)

                    if (!readable) {
                        subtitleStatus = "Não foi possível ler este ficheiro de legenda."
                    } else {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val player = controller
                        if (player == null) {
                            subtitleStatus = "O leitor ainda está a iniciar. Tenta novamente."
                        } else {
                            val resumeAt = player.currentPosition.coerceAtLeast(0L)
                            val wasPlaying = player.isPlaying
                            val metadata = MediaMetadata.Builder()
                                .setTitle(video.title)
                                .setArtist("Vortex – Video Player")
                                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                                .build()
                            val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
                                .setMimeType(mimeType)
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build()
                            val item = MediaItem.Builder()
                                .setUri(video.uri)
                                .setMediaMetadata(metadata)
                                .setSubtitleConfigurations(listOf(subtitle))
                                .build()

                            pendingSubtitleName = displayName
                            externalSubtitleName = null
                            hasSubtitleTracks = false
                            subtitles = false
                            subtitleStatus = "A carregar legenda: $displayName"
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .build()
                            player.setMediaItem(item)
                            player.prepare()
                            player.seekTo(resumeAt)
                            player.setPlaybackSpeed(speed)
                            player.playWhenReady = wasPlaying
                        }
                    }
                }
            }
        }
    }

    val future = remember(video.id) {
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    }
    DisposableEffect(future) {
        var disposed = false
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (!disposed && connected != null) {
                controller = connected
                val metadata = MediaMetadata.Builder()
                    .setTitle(video.title)
                    .setArtist("Vortex – Video Player")
                    .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(video.uri)
                    .setMediaMetadata(metadata)
                    .build()
                connected.setMediaItem(mediaItem)
                val resumeAt = prefs.getLong("watch_pos_${video.id}", 0L).coerceIn(0L, video.durationMs.coerceAtLeast(0L))
                if (resumeAt > 2_000L && resumeAt < video.durationMs - 3_000L) connected.seekTo(resumeAt)
                connected.prepare()
                connected.playWhenReady = true
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; MediaController.releaseFuture(future) }
    }

    DisposableEffect(activity) {
        onDispose {
            activity.restoreDefaultOrientation()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(activity, isLandscape) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (isLandscape) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars.show(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(controller) {
        val player = controller
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                    hasSubtitleTracks = textGroups.isNotEmpty()
                    subtitles = textGroups.any { it.isSelected }

                    val pending = pendingSubtitleName
                    if (pending != null && hasSubtitleTracks) {
                        externalSubtitleName = pending
                        pendingSubtitleName = null
                        subtitleStatus = "Legenda carregada: $pending"
                    } else if (pending == null && externalSubtitleName == null) {
                        subtitleStatus = when {
                            subtitles -> "Legendas incorporadas ativas."
                            hasSubtitleTracks -> "Há legendas incorporadas disponíveis."
                            else -> "Este vídeo não apresenta legendas incorporadas."
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && pendingSubtitleName != null && !hasSubtitleTracks) {
                        subtitleStatus = "A legenda não foi reconhecida. Confirma se o ficheiro está válido."
                        pendingSubtitleName = null
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (pendingSubtitleName != null) {
                        subtitleStatus = "Erro ao carregar legenda: ${error.errorCodeName}"
                        pendingSubtitleName = null
                        externalSubtitleName = null
                    }
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }

    LaunchedEffect(sleepMinutes, controller) {
        val minutes = sleepMinutes ?: return@LaunchedEffect
        delay(minutes * 60_000L)
        controller?.pause()
        playing = false
        sleepMinutes = null
    }

    LaunchedEffect(controller) {
        while (isActive) {
            controller?.let { player ->
                position = player.currentPosition.coerceAtLeast(0L)
                if (player.duration > 0L && player.duration != C.TIME_UNSET) duration = player.duration
                playing = player.isPlaying
                val savedPosition = if (duration > 0L && player.currentPosition >= duration - 2_000L) 0L else player.currentPosition.coerceAtLeast(0L)
                saveWatchProgress(prefs, video, savedPosition, duration)
            }
            delay(5_000)
        }
    }

    DisposableEffect(controller, autoPlayNext, onNext) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (autoPlayNext && playbackState == Player.STATE_ENDED) onNext()
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }

    DisposableEffect(activity, backgroundPlayback, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !backgroundPlayback && !activity.isInPipMode) {
                controller?.pause()
                playing = false
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(activity, keepScreenOn) {
        if (keepScreenOn) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(controller) {
        onDispose {
            controller?.let { player ->
                val savedPosition = if (duration > 0L && player.currentPosition >= duration - 2_000L) 0L else player.currentPosition.coerceAtLeast(0L)
                saveWatchProgress(prefs, video, savedPosition, duration)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .then(if (isLandscape) Modifier else Modifier.navigationBarsPadding())
    ) {
        if (!activity.isInPipMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (isLandscape) 6.dp else 10.dp, vertical = if (isLandscape) 2.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        video.title,
                        color = Color.White,
                        fontSize = if (isLandscape) 11.sp else 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isLandscape) {
                        Text("VORTEX PLAYER", color = Color(0xFFBBA8FF), fontSize = 9.sp, letterSpacing = 1.4.sp)
                    }
                }
                IconButton(
                    enabled = controller != null,
                    onClick = {
                        val player = controller ?: return@IconButton
                        if (!hasSubtitleTracks) {
                            subtitleStatus = "Sem faixa de legenda. Adiciona um ficheiro SRT, VTT, ASS, SSA ou TTML."
                        } else {
                            subtitles = !subtitles
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitles)
                                .build()
                            subtitleStatus = if (subtitles) "Legendas ativadas." else "Legendas desativadas."
                        }
                    }
                ) {
                    Icon(Icons.Default.Subtitles, contentDescription = "Ativar ou desativar legendas", tint = if (subtitles) Color(0xFFBBA8FF) else Color.White)
                }
                IconButton(onClick = { activity.setPlayerLandscape(!isLandscape) }) {
                    Icon(Icons.Default.ScreenRotation, contentDescription = if (isLandscape) "Rodar para vertical" else "Assistir na horizontal", tint = Color.White)
                }
                IconButton(enabled = pipEnabled, onClick = { activity.enterVortexPip() }) {
                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture-in-Picture", tint = if (pipEnabled) Color.White else Muted)
                }
                Box {
                    IconButton(onClick = { toolsExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Mais opções", tint = Color.White)
                    }
                    DropdownMenu(expanded = toolsExpanded, onDismissRequest = { toolsExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Adicionar legenda externa") },
                            leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                            onClick = {
                                toolsExpanded = false
                                subtitlePicker.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Selecionar faixa de áudio") },
                            leadingIcon = { Icon(Icons.Default.VolumeUp, contentDescription = null) },
                            onClick = {
                                controller?.let { player ->
                                    TrackSelectionDialogBuilder(context, "Faixa de áudio", player, C.TRACK_TYPE_AUDIO).build().show()
                                }
                                toolsExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Selecionar faixa de legendas") },
                            leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                            onClick = {
                                val player = controller
                                if (player != null && hasSubtitleTracks) {
                                    TrackSelectionDialogBuilder(context, "Faixa de legendas", player, C.TRACK_TYPE_TEXT)
                                        .setShowDisableOption(true)
                                        .build()
                                        .show()
                                } else {
                                    subtitleStatus = "Não existem faixas de legenda disponíveis neste vídeo."
                                }
                                toolsExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Velocidade de reprodução") },
                            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) },
                            onClick = { speedDialog = true; toolsExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Ajustar ao ecrã") },
                            onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; toolsExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Preencher e cortar") },
                            onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; toolsExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Esticar imagem") },
                            onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; toolsExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Legendas pequenas") },
                            onClick = {
                                subtitleScale = 0.85f
                                prefs.edit().putFloat("subtitle_scale", subtitleScale).apply()
                                toolsExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Legendas normais") },
                            onClick = {
                                subtitleScale = 1.0f
                                prefs.edit().putFloat("subtitle_scale", subtitleScale).apply()
                                toolsExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Legendas grandes") },
                            onClick = {
                                subtitleScale = 1.25f
                                prefs.edit().putFloat("subtitle_scale", subtitleScale).apply()
                                toolsExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (repeatOne) "Desativar repetição" else "Repetir este vídeo") },
                            leadingIcon = { Icon(Icons.Default.RepeatOne, contentDescription = null) },
                            onClick = {
                                repeatOne = !repeatOne
                                controller?.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                                toolsExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (sleepMinutes == null) "Temporizador para dormir" else "Temporizador: $sleepMinutes min") },
                            leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                            onClick = { timerDialog = true; toolsExpanded = false }
                        )
                    }
                }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = {
                it.player = controller
                it.resizeMode = resizeMode
                it.subtitleView?.apply {
                    setApplyEmbeddedStyles(false)
                    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale)
                }
            }
        )
        if (!activity.isInPipMode) {
            Column(
                Modifier.fillMaxWidth().padding(
                    horizontal = if (isLandscape) 36.dp else 22.dp,
                    vertical = if (isLandscape) 3.dp else 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 2.dp else 8.dp)
            ) {
                Slider(
                    value = (if (dragging) dragPosition else position).toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragging = true; dragPosition = it.toLong() },
                    onValueChangeFinished = { controller?.seekTo(dragPosition); dragging = false },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple, inactiveTrackColor = Color(0xFF494957))
                )
                Row {
                    Text(formatDuration(if (dragging) dragPosition else position), color = Color.White, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(duration), color = Color(0xFF9B9BAA), fontSize = 10.sp)
                }
                if (subtitleStatus.isNotBlank()) {
                    Text(
                        subtitleStatus,
                        color = if (subtitleStatus.startsWith("Erro") || subtitleStatus.startsWith("Não foi")) Color(0xFFFF9BA8) else Color(0xFFBBA8FF),
                        fontSize = if (isLandscape) 9.sp else 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!isLandscape && (sleepMinutes != null || repeatOne || resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT)) {
                    Text(
                        listOfNotNull(
                            if (repeatOne) "Repetir ativo" else null,
                            sleepMinutes?.let { "Temporizador $it min" },
                            if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "Preencher" else if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) "Esticado" else null
                        ).joinToString("  •  "),
                        color = Color(0xFFBBA8FF),
                        fontSize = 9.sp
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Vídeo anterior", tint = Color.White)
                    }
                    IconButton(onClick = { controller?.seekTo((position - 10_000L).coerceAtLeast(0L)) }) {
                        Icon(Icons.Default.Replay10, contentDescription = "Retroceder 10 segundos", tint = Color.White)
                    }
                    Box(
                        Modifier.size(if (isLandscape) 48.dp else 58.dp)
                            .clip(CircleShape)
                            .background(Purple)
                            .clickable {
                                if (playing) controller?.pause() else controller?.play()
                                playing = !playing
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(31.dp))
                    }
                    IconButton(onClick = { controller?.seekTo((position + 10_000L).coerceAtMost(duration)) }) {
                        Icon(Icons.Default.Forward10, contentDescription = "Avançar 10 segundos", tint = Color.White)
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Próximo vídeo", tint = Color.White)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = if (isLandscape) 0.dp else 3.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { speedDialog = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFFBBA8FF), modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(speed.toString() + "x", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(18.dp))
                    IconButton(enabled = pipEnabled, onClick = { activity.enterVortexPip() }) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture-in-Picture", tint = if (pipEnabled) Color.White else Muted)
                    }
                    IconButton(onClick = { activity.setPlayerLandscape(!isLandscape) }) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = if (isLandscape) "Rodar para vertical" else "Assistir na horizontal", tint = Color.White)
                    }
                }
            }
        }
    }

    if (speedDialog) {
        AlertDialog(
            onDismissRequest = { speedDialog = false },
            icon = { Icon(Icons.Default.Speed, contentDescription = null, tint = Purple) },
            title = { Text("Velocidade de reprodução") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).chunked(3).forEach { rowSpeeds ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowSpeeds.forEach { value ->
                                FilterChip(
                                    selected = speed == value,
                                    onClick = {
                                        speed = value
                                        controller?.setPlaybackSpeed(value)
                                        speedDialog = false
                                    },
                                    label = { Text("${value}x", fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { speedDialog = false }) { Text("Fechar") } },
            shape = RoundedCornerShape(24.dp)
        )
    }


    if (timerDialog) {
        AlertDialog(
            onDismissRequest = { timerDialog = false },
            icon = { Icon(Icons.Default.Timer, contentDescription = null, tint = Purple) },
            title = { Text("Temporizador para dormir") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("O Vortex pausa a reprodução automaticamente.", color = Muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 45, 60).forEach { minutes ->
                            OutlinedButton(
                                onClick = { sleepMinutes = minutes; timerDialog = false },
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)
                            ) { Text("$minutes min", fontSize = 11.sp) }
                        }
                    }
                    if (sleepMinutes != null) {
                        TextButton(onClick = { sleepMinutes = null; timerDialog = false }) {
                            Text("Cancelar temporizador", color = Color(0xFFD84B62))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { timerDialog = false }) { Text("Fechar") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

private fun formatDuration(milliseconds: Long): String {
    val total = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}


private fun saveWatchProgress(
    prefs: android.content.SharedPreferences,
    video: LocalVideo,
    positionMs: Long,
    durationMs: Long
) {
    val id = video.id.toString()
    val order = prefs.getString("history_order", "").orEmpty()
        .split("|")
        .filter { it.isNotBlank() && it != id }
        .toMutableList()
    order.add(0, id)
    prefs.edit()
        .putLong("watch_pos_$id", positionMs.coerceAtLeast(0L))
        .putLong("watch_duration_$id", durationMs.coerceAtLeast(0L))
        .putString("history_order", order.take(30).joinToString("|"))
        .apply()
}
