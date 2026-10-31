package com.nexauren.vortex

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Rational
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
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

    BackHandler(enabled = selectedVideo != null) { selectedVideo = null }

    if (!welcomeDone) {
        WelcomeScreen {
            welcomeDone = true
            prefs.edit().putBoolean("welcome_done", true).apply()
        }
        return
    }

    selectedVideo?.let {
        PlayerScreen(activity, it, pipEnabled) { selectedVideo = null }
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
                onRefresh = { if (hasVideoPermission(context)) scan() else requestAccess() }
            )
            when (tab) {
                Tab.HOME -> HomeScreen(videos, scanning, permission,
                    onRefresh = { if (hasVideoPermission(context)) scan() else requestAccess() },
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
        Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(
            Brush.linearGradient(listOf(Color(0xFF090B25), Color(0xFF3420A4), Color(0xFF0D79E8)))
        ), contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Vortex", tint = Color.White, modifier = Modifier.size(size * 0.74f))
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
private fun TopBar(title: String, onSearch: () -> Unit, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("VORTEX  •  VIDEO PLAYER", fontSize = 9.sp, letterSpacing = 1.6.sp, color = Muted)
        }
        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Pesquisar", tint = Ink) }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = Ink) }
    }
}

@Composable
private fun HomeScreen(
    videos: List<LocalVideo>, scanning: Boolean, permission: Boolean,
    onRefresh: () -> Unit, onOpen: (LocalVideo) -> Unit, onNavigate: (Tab) -> Unit
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
                }
            }
        } else {
            val featured = videos.first()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Vídeo em destaque", "Abrir", { onOpen(featured) })
                Card(Modifier.fillMaxWidth().clickable { onOpen(featured) }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF11162D))) {
                    Box {
                        VideoThumbnail(featured, Modifier.fillMaxWidth().height(190.dp))
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000015)))).padding(16.dp)) {
                            Column {
                                Text("VÍDEO NO TEU DISPOSITIVO", fontSize = 9.sp, color = Color(0xFFBDA8FF), letterSpacing = 1.5.sp)
                                Text(featured.title, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatDuration(featured.durationMs) + "  •  " + featured.folder, fontSize = 11.sp, color = Color.White.copy(alpha = .8f))
                            }
                        }
                        Box(Modifier.align(Alignment.Center).size(54.dp).clip(CircleShape).background(Purple), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Reproduzir", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
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
    val filtered = videos.filter { it.title.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        OutlinedTextField(
            query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Pesquisar vídeos") }, shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(filtered.size.toString() + " vídeos", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            EmptyState("Nenhum vídeo encontrado", "Os vídeos locais disponíveis aparecerão aqui.", Icons.Default.VideoLibrary)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(filtered, key = { it.id }) { video ->
                    Column {
                        Box {
                            VideoThumbnail(video, Modifier.fillMaxWidth().aspectRatio(1.42f).clip(RoundedCornerShape(15.dp)).clickable { onOpen(video) })
                            Text(formatDuration(video.durationMs), Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xC9000010)).padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, fontSize = 9.sp)
                            IconButton(onClick = { onFavorite(video) }, modifier = Modifier.align(Alignment.TopEnd)) {
                                Icon(if (video.id.toString() in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favorito", tint = if (video.id.toString() in favorites) Color(0xFFFF4D85) else Color.White)
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
private fun SettingsScreen(pipEnabled: Boolean, onPipChange: (Boolean) -> Unit, onRescan: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SettingsHeading("Reprodução")
        SettingSwitch(Icons.Default.PictureInPictureAlt, "Picture-in-Picture", "Continuar a ver enquanto utilizas outras aplicações", pipEnabled, onPipChange)
        SettingRow(Icons.Default.VolumeUp, "Reprodução em segundo plano", "Sessão multimédia preparada para continuar fora do ecrã")
        SettingRow(Icons.Default.Subtitles, "Legendas", "Faixas incorporadas e controlos no player")
        SettingRow(Icons.Default.Speed, "Velocidade de reprodução", "Controlos de velocidade no player")
        HorizontalDivider(color = Color(0xFFECECF2))
        SettingsHeading("Biblioteca")
        SettingRow(Icons.Default.Refresh, "Atualizar biblioteca", "Voltar a procurar vídeos neste dispositivo", onRescan)
        SettingRow(Icons.Default.Lock, "Privacidade", "Os vídeos permanecem no teu dispositivo")
        SettingRow(Icons.Default.Cast, "Chromecast", "Base Media3 Cast incluída; ligação ao recetor na próxima etapa")
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
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
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

@Composable
private fun VideoThumbnail(video: LocalVideo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(video.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(video.uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(video.uri, Size(640, 360), CancellationSignal())
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, video.uri)
                        retriever.getFrameAtTime(1_000_000)
                    } finally { retriever.release() }
                }
            }.getOrNull()
        }
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF171A3A), Color(0xFF5B31B8))))) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = video.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.align(Alignment.Center).size(34.dp))
    }
}

@Composable
private fun PlayerScreen(activity: MainActivity, video: LocalVideo, pipEnabled: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var controller by remember(video.id) { mutableStateOf<MediaController?>(null) }
    var position by remember(video.id) { mutableLongStateOf(0L) }
    var duration by remember(video.id) { mutableLongStateOf(video.durationMs.coerceAtLeast(1L)) }
    var playing by remember(video.id) { mutableStateOf(false) }
    var subtitles by remember(video.id) { mutableStateOf(true) }
    var speed by remember(video.id) { mutableStateOf(1.0f) }
    var dragging by remember(video.id) { mutableStateOf(false) }
    var dragPosition by remember(video.id) { mutableLongStateOf(0L) }

    val future = remember(video.id) {
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    }
    DisposableEffect(future) {
        var disposed = false
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (!disposed && connected != null) {
                controller = connected
                connected.setMediaItem(MediaItem.fromUri(video.uri))
                connected.prepare()
                connected.playWhenReady = true
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; MediaController.releaseFuture(future) }
    }

    LaunchedEffect(controller) {
        while (isActive) {
            controller?.let { player ->
                position = player.currentPosition.coerceAtLeast(0L)
                if (player.duration > 0L && player.duration != C.TIME_UNSET) duration = player.duration
                playing = player.isPlaying
            }
            delay(350)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding()) {
        if (!activity.isInPipMode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(video.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("VORTEX PLAYER", color = Color(0xFFBBA8FF), fontSize = 9.sp, letterSpacing = 1.4.sp)
                }
                IconButton(onClick = {
                    controller?.let { player ->
                        subtitles = !subtitles
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitles).build()
                    }
                }) { Icon(Icons.Default.Subtitles, contentDescription = "Legendas", tint = if (subtitles) Color.White else Muted) }
                IconButton(enabled = pipEnabled, onClick = { activity.enterVortexPip() }) {
                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture-in-Picture", tint = if (pipEnabled) Color.White else Muted)
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
                }
            },
            update = { it.player = controller }
        )
        if (!activity.isInPipMode) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                Slider(
                    value = (if (dragging) dragPosition else position).toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragging = true; dragPosition = it.toLong() },
                    onValueChangeFinished = { controller?.seekTo(dragPosition); dragging = false },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple, inactiveTrackColor = Color(0xFF494957))
                )
                Row {
                    Text(formatDuration(if (dragging) dragPosition else position), color = Color.White, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(duration), color = Color(0xFF9B9BAA), fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        speed = when (speed) { 0.75f -> 1f; 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 0.75f }
                        controller?.setPlaybackSpeed(speed)
                    }) { Text(speed.toString() + "x", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    IconButton(onClick = { controller?.seekTo((position - 10_000L).coerceAtLeast(0L)) }) {
                        Icon(Icons.Default.FastRewind, contentDescription = "Retroceder", tint = Color.White)
                    }
                    Box(Modifier.size(58.dp).clip(CircleShape).background(Purple).clickable {
                        if (playing) controller?.pause() else controller?.play()
                        playing = !playing
                    }, contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = { controller?.seekTo((position + 10_000L).coerceAtMost(duration)) }) {
                        Icon(Icons.Default.FastForward, contentDescription = "Avançar", tint = Color.White)
                    }
                    IconButton(enabled = pipEnabled, onClick = { activity.enterVortexPip() }) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture-in-Picture", tint = if (pipEnabled) Color.White else Muted)
                    }
                }
            }
        }
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
