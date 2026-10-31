package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VpnKey
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import com.example.ui.theme.EmeraldSuccess
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.model.DiscoveredMediaCandidate
import com.example.data.model.DownloadedFile
import com.example.data.model.DownloadStatus
import com.example.data.model.MediaMetadata
import com.example.data.model.MediaType
import com.example.data.model.SourceProtocol
import com.example.ui.components.CollapsibleLogsCard
import com.example.ui.components.FormatSelectionDialog
import com.example.ui.components.SettingsDialog
import com.example.ui.components.VideoPreviewDialog
import com.example.ui.components.YouTubeLoginDialog
import com.example.ui.viewmodel.AppTab
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    BackHandler(enabled = uiState.activeTab == AppTab.LIBRARY) {
        viewModel.setTab(AppTab.DOWNLOAD)
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (uiState.showSettingsDialog) {
        SettingsDialog(
            engineVersion = uiState.engineVersion,
            isEngineReady = uiState.isEngineReady,
            isUpdatingEngine = uiState.isUpdatingEngine,
            hasActiveCookies = uiState.hasActiveCookies,
            downloadsCount = uiState.downloads.size,
            selectedDirectoryType = uiState.selectedDirectoryType,
            tempCacheSizeBytes = uiState.tempCacheSizeBytes,
            isClearingCache = uiState.isClearingCache,
            onSelectDirectoryType = viewModel::setDownloadDirectoryType,
            onClearTempCache = viewModel::clearTempCache,
            onUpdateEngine = viewModel::updateEngine,
            onOpenCookieDialog = viewModel::openCookieDialog,
            onClearCookies = viewModel::clearCookies,
            onOpenDownloadsFolder = { viewModel.openDownloadsFolder(context) },
            onDismiss = viewModel::closeSettingsDialog
        )
    }

    if (uiState.showFormatDialog && uiState.status is DownloadStatus.Ready) {
        val readyMeta = (uiState.status as DownloadStatus.Ready).metadata
        FormatSelectionDialog(
            metadata = readyMeta,
            selectedCandidateId = uiState.selectedCandidateId,
            onSelectCandidate = viewModel::onSelectCandidate,
            onDownloadSelected = { candidate ->
                viewModel.downloadCandidate(candidate)
            },
            onPreviewCandidate = { candidate ->
                viewModel.openPreview(candidate)
            },
            onDismiss = viewModel::closeFormatDialog
        )
    }

    if (uiState.showCookieDialog) {
        YouTubeLoginDialog(
            hasActiveCookies = uiState.hasActiveCookies,
            onSaveCookiesFromWebView = viewModel::saveCookiesFromWebView,
            onSaveRawCookies = viewModel::saveRawCookies,
            onClearCookies = viewModel::clearCookies,
            onDismiss = viewModel::closeCookieDialog
        )
    }

    if (uiState.previewState.isOpen) {
        VideoPreviewDialog(
            title = uiState.previewState.title,
            mediaUrlOrPath = uiState.previewState.mediaUrlOrPath,
            candidate = uiState.previewState.candidate,
            isLocalFile = uiState.previewState.isLocalFile,
            onDownloadCandidate = { candidate ->
                viewModel.downloadCandidate(candidate)
            },
            onShareFile = {
                uiState.downloads.firstOrNull { it.file.absolutePath == uiState.previewState.mediaUrlOrPath }?.let { item ->
                    viewModel.shareMediaFile(context, item.file, item.type)
                }
            },
            onDismiss = viewModel::closePreview
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "StreamRip",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "StreamRip",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.3).sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(EmeraldSuccess)
                                        )
                                        Text(
                                            text = uiState.engineVersion ?: "Ready",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "Universal Stream & Media Downloader",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (uiState.hasActiveCookies) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            IconButton(
                                onClick = { viewModel.openCookieDialog() },
                                modifier = Modifier.size(36.dp).testTag("cookie_status_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VpnKey,
                                    contentDescription = "Active Cookies",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { viewModel.openSettingsDialog() },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings & yt-dlp Core",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = uiState.activeTab == AppTab.DOWNLOAD,
                    onClick = { viewModel.setTab(AppTab.DOWNLOAD) },
                    icon = { Icon(Icons.Default.Download, contentDescription = "Downloader") },
                    label = { Text("Downloader") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("nav_downloader")
                )
                NavigationBarItem(
                    selected = uiState.activeTab == AppTab.LIBRARY,
                    onClick = { viewModel.setTab(AppTab.LIBRARY) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.downloads.isNotEmpty()) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Text("${uiState.downloads.size}")
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = "Downloads")
                        }
                    },
                    label = { Text("Downloads") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("nav_library")
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = uiState.activeTab,
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        (slideInHorizontally { width -> width / 3 } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width / 3 } + fadeOut()
                        )
                    } else {
                        (slideInHorizontally { width -> -width / 3 } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width / 3 } + fadeOut()
                        )
                    }.using(SizeTransform(clip = false))
                },
                label = "tab_animation"
            ) { tab ->
                when (tab) {
                    AppTab.DOWNLOAD -> {
                        DownloaderContent(
                            uiState = uiState,
                            onUrlChanged = viewModel::onUrlChanged,
                            onPaste = {
                                val clipboardText = clipboardManager.getText()?.text
                                if (!clipboardText.isNullOrBlank()) {
                                    viewModel.onUrlChanged(clipboardText)
                                    viewModel.fetchMetadata()
                                }
                            },
                            onClearUrl = { viewModel.onUrlChanged("") },
                            onSelectType = viewModel::onSelectMediaType,
                            onSelectVideoQuality = viewModel::onSelectVideoQuality,
                            onSelectAudioQuality = viewModel::onSelectAudioQuality,
                            onSelectCandidate = viewModel::onSelectCandidate,
                            onPreviewCandidate = viewModel::openPreview,
                            onDownloadCandidate = viewModel::downloadCandidate,
                            onFetchInfo = viewModel::fetchMetadata,
                            onStartDownload = viewModel::startDownload,
                            onCancelDownload = viewModel::cancelDownload,
                            onOpenSettings = viewModel::openSettingsDialog,
                            onOpenCookieDialog = viewModel::openCookieDialog,
                            onOpenMedia = { file, type ->
                                viewModel.openLocalFilePreview(
                                    DownloadedFile(
                                        id = file.absolutePath,
                                        title = file.nameWithoutExtension,
                                        file = file,
                                        type = type,
                                        sizeBytes = file.length(),
                                        timestamp = file.lastModified()
                                    )
                                )
                            },
                            onShareMedia = { file, type -> viewModel.shareMediaFile(context, file, type) },
                            onOpenDownloadsFolder = { viewModel.openDownloadsFolder(context) },
                            onOpenFormatDialog = viewModel::openFormatDialog,
                            onShowMessage = { msg ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        )
                    }
                    AppTab.LIBRARY -> {
                        LibraryContent(
                            downloads = uiState.downloads,
                            onRefresh = viewModel::refreshDownloads,
                            onOpenMedia = { file, type ->
                                viewModel.openLocalFilePreview(
                                    DownloadedFile(
                                        id = file.absolutePath,
                                        title = file.nameWithoutExtension,
                                        file = file,
                                        type = type,
                                        sizeBytes = file.length(),
                                        timestamp = file.lastModified()
                                    )
                                )
                            },
                            onShareMedia = { file, type -> viewModel.shareMediaFile(context, file, type) },
                            onDeleteFile = viewModel::deleteDownloadedFile,
                            onOpenDownloadsFolder = { viewModel.openDownloadsFolder(context) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloaderContent(
    uiState: MainUiState,
    onUrlChanged: (String) -> Unit,
    onPaste: () -> Unit,
    onClearUrl: () -> Unit,
    onSelectType: (MediaType) -> Unit,
    onSelectVideoQuality: (String) -> Unit,
    onSelectAudioQuality: (String) -> Unit,
    onSelectCandidate: (DiscoveredMediaCandidate) -> Unit = {},
    onPreviewCandidate: (DiscoveredMediaCandidate) -> Unit = {},
    onDownloadCandidate: (DiscoveredMediaCandidate) -> Unit = {},
    onFetchInfo: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCookieDialog: () -> Unit,
    onOpenMedia: (java.io.File, MediaType) -> Unit,
    onShareMedia: (java.io.File, MediaType) -> Unit,
    onOpenDownloadsFolder: () -> Unit,
    onOpenFormatDialog: () -> Unit = {},
    onShowMessage: (String) -> Unit = {}
) {
    val isDownloading = uiState.status is DownloadStatus.Downloading

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("url_input_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Media Source URL",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = uiState.url,
                        onValueChange = onUrlChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("url_input_field"),
                        placeholder = { Text("Paste video, audio, or HLS stream link...") },
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = "URL Link")
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (uiState.url.isNotEmpty()) {
                                    IconButton(
                                        onClick = onClearUrl,
                                        modifier = Modifier.testTag("clear_url_button")
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                                IconButton(
                                    onClick = onPaste,
                                    modifier = Modifier.testTag("paste_button")
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    // Quick demo links for testing protocols
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                onClick = {
                                    onUrlChanged("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4")
                                    onFetchInfo()
                                },
                                modifier = Modifier.height(30.dp).testTag("demo_mp4_chip")
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text("Direct MP4", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                onClick = {
                                    onUrlChanged("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
                                    onFetchInfo()
                                },
                                modifier = Modifier.height(30.dp).testTag("demo_hls_chip")
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text("HLS (.m3u8)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                onClick = {
                                    onUrlChanged("https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd")
                                    onFetchInfo()
                                },
                                modifier = Modifier.height(30.dp).testTag("demo_dash_chip")
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text("DASH (.mpd)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                onClick = {
                                    onUrlChanged("https://player.castbuddy.app/help/sample")
                                    onFetchInfo()
                                },
                                modifier = Modifier.height(30.dp).testTag("demo_html5_chip")
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text("HTML5 Webpage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                onClick = {
                                    onUrlChanged("https://raw.githubusercontent.com/cdnbye/hlsjs-p2p-engine/master/demo/playerjs/index.html")
                                    onFetchInfo()
                                },
                                modifier = Modifier.height(30.dp).testTag("demo_playerjs_chip")
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text("PlayerJS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        OutlinedButton(
                            onClick = onFetchInfo,
                            enabled = uiState.url.isNotBlank() && !isDownloading && uiState.status !is DownloadStatus.FetchingInfo,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("fetch_info_button")
                        ) {
                            if (uiState.status is DownloadStatus.FetchingInfo) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Analyzing...")
                            } else {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Analyze")
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("format_selection_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Output Format",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FormatChoiceCard(
                            title = "Video (MP4)",
                            subtitle = "Full video & audio",
                            icon = Icons.Default.Videocam,
                            isSelected = uiState.selectedType == MediaType.VIDEO_MP4,
                            onClick = { onSelectType(MediaType.VIDEO_MP4) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("format_mp4_button")
                        )

                        FormatChoiceCard(
                            title = "Music (MP3)",
                            subtitle = "Audio only extract",
                            icon = Icons.Default.Audiotrack,
                            isSelected = uiState.selectedType == MediaType.AUDIO_MP3,
                            onClick = { onSelectType(MediaType.AUDIO_MP3) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("format_mp3_button")
                        )
                    }

                    if (uiState.selectedType == MediaType.VIDEO_MP4) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Video Resolution",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val qualities = listOf("Best", "1080p", "720p", "480p", "360p")
                                items(qualities) { q ->
                                    FilterChip(
                                        selected = uiState.videoQuality == q,
                                        onClick = { onSelectVideoQuality(q) },
                                        label = { Text(q) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Audio Quality / Bitrate",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val bitrates = listOf("320k", "256k", "192k", "128k")
                                items(bitrates) { b ->
                                    FilterChip(
                                        selected = uiState.audioQuality == b,
                                        onClick = { onSelectAudioQuality(b) },
                                        label = { Text(if (b == "320k") "320 kbps (Best)" else "$b kbps") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val previewMeta = when (val s = uiState.status) {
            is DownloadStatus.Ready -> s.metadata
            is DownloadStatus.Downloading -> s.metadata
            else -> null
        }

        if (previewMeta != null) {
            item {
                MediaPreviewCard(
                    metadata = previewMeta,
                    selectedCandidateId = uiState.selectedCandidateId,
                    onSelectCandidate = onSelectCandidate,
                    onPreviewStream = {
                        val active = previewMeta.selectedCandidate
                        if (active != null) {
                            onPreviewCandidate(active)
                        }
                    },
                    onOpenFormatDialog = onOpenFormatDialog
                )
            }

            if (previewMeta.candidates.size > 1) {
                item {
                    MultipleCandidatesCard(
                        candidates = previewMeta.candidates,
                        selectedCandidateId = uiState.selectedCandidateId,
                        onSelectCandidate = onSelectCandidate,
                        onPreviewCandidate = onPreviewCandidate,
                        onDownloadCandidate = onDownloadCandidate
                    )
                }
            }
        }

        if (uiState.status is DownloadStatus.Downloading) {
            item {
                DownloadProgressCard(
                    status = uiState.status,
                    onCancel = onCancelDownload
                )
            }
        }

        if (uiState.status is DownloadStatus.Completed) {
            item {
                DownloadCompletedCard(
                    status = uiState.status,
                    onPlay = { onOpenMedia(uiState.status.file, uiState.status.type) },
                    onShare = { onShareMedia(uiState.status.file, uiState.status.type) },
                    onOpenDownloadsFolder = onOpenDownloadsFolder
                )
            }
        }

        if (uiState.status is DownloadStatus.Failed) {
            item {
                DownloadFailedCard(
                    status = uiState.status,
                    requiresVerification = uiState.requiresVerification,
                    onRetry = onStartDownload,
                    onOpenSettings = onOpenSettings,
                    onOpenCookieDialog = onOpenCookieDialog
                )
            }
        }

        item {
            Button(
                onClick = onStartDownload,
                enabled = uiState.url.isNotBlank() && !isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("download_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                val actionLabel = if (uiState.selectedType == MediaType.VIDEO_MP4) {
                    "Download MP4 Video"
                } else {
                    "Download MP3 Music"
                }
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        item {
            CollapsibleLogsCard(
                onShowMessage = onShowMessage
            )
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun FormatChoiceCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MediaPreviewCard(
    metadata: MediaMetadata,
    selectedCandidateId: String? = null,
    onSelectCandidate: (DiscoveredMediaCandidate) -> Unit = {},
    onPreviewStream: (() -> Unit)? = null,
    onOpenFormatDialog: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("media_preview_card")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!metadata.thumbnailUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = metadata.thumbnailUrl,
                        contentDescription = metadata.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    if (metadata.formattedDuration.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.75f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = metadata.formattedDuration,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = metadata.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    val protocol = metadata.sourceProtocol ?: metadata.selectedCandidate?.protocol
                    if (protocol != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when (protocol) {
                                SourceProtocol.HLS -> MaterialTheme.colorScheme.tertiaryContainer
                                SourceProtocol.DASH -> MaterialTheme.colorScheme.secondaryContainer
                                SourceProtocol.PLAYERJS -> MaterialTheme.colorScheme.primaryContainer
                                SourceProtocol.HTML5_TAG -> MaterialTheme.colorScheme.surfaceVariant
                                SourceProtocol.DIRECT_HTTP -> MaterialTheme.colorScheme.primaryContainer
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = protocol.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (protocol) {
                                    SourceProtocol.HLS -> MaterialTheme.colorScheme.onTertiaryContainer
                                    SourceProtocol.DASH -> MaterialTheme.colorScheme.onSecondaryContainer
                                    SourceProtocol.PLAYERJS -> MaterialTheme.colorScheme.onPrimaryContainer
                                    SourceProtocol.HTML5_TAG -> MaterialTheme.colorScheme.onSurfaceVariant
                                    SourceProtocol.DIRECT_HTTP -> MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                metadata.uploader?.let { uploader ->
                    Text(
                        text = uploader,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    onPreviewStream?.let { previewAction ->
                        OutlinedButton(
                            onClick = previewAction,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("preview_stream_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Preview", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    onOpenFormatDialog?.let { formatAction ->
                        Button(
                            onClick = formatAction,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("open_format_dialog_button")
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("All Formats (${metadata.candidates.size})", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Discovered streams and candidate selection list
            if (metadata.candidates.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Discovered Streams & Qualities (${metadata.candidates.size})",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                val effectiveSelectedId = selectedCandidateId ?: metadata.selectedCandidateId ?: metadata.candidates.firstOrNull()?.id

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(metadata.candidates) { candidate ->
                        val isSelected = candidate.id == effectiveSelectedId
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectCandidate(candidate) },
                            label = {
                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = candidate.label,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (candidate.protocol != SourceProtocol.DIRECT_HTTP) {
                                            Text(
                                                text = "• ${candidate.protocol.displayName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    candidate.formattedSize?.let { sizeStr ->
                                        Text(
                                            text = sizeStr,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultipleCandidatesCard(
    candidates: List<DiscoveredMediaCandidate>,
    selectedCandidateId: String?,
    onSelectCandidate: (DiscoveredMediaCandidate) -> Unit,
    onPreviewCandidate: (DiscoveredMediaCandidate) -> Unit,
    onDownloadCandidate: (DiscoveredMediaCandidate) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("multiple_candidates_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Discovered Videos on this Page (${candidates.size})",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Choose any video below to preview in the player or download directly:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val effectiveSelectedId = selectedCandidateId ?: candidates.firstOrNull()?.id

            candidates.forEachIndexed { index, candidate ->
                val isSelected = candidate.id == effectiveSelectedId
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }
                    ),
                    border = if (isSelected) {
                        androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectCandidate(candidate) }
                        .testTag("candidate_card_$index")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (!candidate.thumbnailUrl.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .size(68.dp, 48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    AsyncImage(
                                        model = candidate.thumbnailUrl,
                                        contentDescription = candidate.label,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = candidate.protocol.displayName,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                    candidate.resolution?.let { res ->
                                        Text(
                                            text = res,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    candidate.formattedSize?.let { sz ->
                                        Text(
                                            text = "• $sz",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { onPreviewCandidate(candidate) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("preview_candidate_$index")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Preview", style = MaterialTheme.typography.labelMedium)
                            }

                            Button(
                                onClick = { onDownloadCandidate(candidate) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("download_candidate_$index")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Download", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(
    status: DownloadStatus.Downloading,
    onCancel: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_progress_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Converting & Downloading...",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (status.etaSeconds > 0) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "ETA: ${status.etaSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            val progressValue = (status.progress / 100f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progressValue },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format("%.1f%%", status.progress),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("cancel_download_button")
                ) {
                    Text(
                        "Cancel",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (status.statusLine.isNotBlank()) {
                Text(
                    text = status.statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DownloadCompletedCard(
    status: DownloadStatus.Completed,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onOpenDownloadsFolder: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_completed_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        text = "Saved to Downloads!",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = status.file.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Saved in Downloads folder (/Download) • Accessible by all apps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onPlay,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("play_completed_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (status.type == MediaType.VIDEO_MP4) "Play Video in App" else "Play Music in App",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("share_completed_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onOpenDownloadsFolder,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_folder_completed_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Folder", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DownloadFailedCard(
    status: DownloadStatus.Failed,
    requiresVerification: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCookieDialog: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_failed_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = if (requiresVerification) "Stream Blocked (HTTP 403 / Bot Check)" else "Download Failed",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (requiresVerification) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("open_settings_from_error_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Update Core")
                    }

                    Button(
                        onClick = onOpenCookieDialog,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("verify_cookies_button")
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Cookies / Login")
                    }

                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("retry_button")
                    ) {
                        Text("Retry")
                    }
                } else {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("open_settings_from_error_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Settings")
                    }

                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("retry_button")
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryContent(
    downloads: List<DownloadedFile>,
    onRefresh: () -> Unit,
    onOpenMedia: (java.io.File, MediaType) -> Unit,
    onShareMedia: (java.io.File, MediaType) -> Unit,
    onDeleteFile: (DownloadedFile) -> Unit,
    onOpenDownloadsFolder: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf<MediaType?>(null) }
    var fileToDelete by remember { mutableStateOf<DownloadedFile?>(null) }

    val filteredList = remember(downloads, selectedFilter) {
        if (selectedFilter == null) downloads else downloads.filter { it.type == selectedFilter }
    }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete File") },
            text = { Text("Are you sure you want to delete \"${fileToDelete?.title}\" from your Downloads folder?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        fileToDelete?.let { onDeleteFile(it) }
                        fileToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Public Downloads Directory info header
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("downloads_folder_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "Stored in Device Downloads",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "/Download • Accessible to other apps",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = onOpenDownloadsFolder,
                    modifier = Modifier.testTag("open_downloads_button")
                ) {
                    Text("Open")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text("All (${downloads.size})") }
            )
            FilterChip(
                selected = selectedFilter == MediaType.VIDEO_MP4,
                onClick = { selectedFilter = MediaType.VIDEO_MP4 },
                label = { Text("Videos (MP4)") },
                leadingIcon = { Icon(Icons.Default.Videocam, null, modifier = Modifier.size(16.dp)) }
            )
            FilterChip(
                selected = selectedFilter == MediaType.AUDIO_MP3,
                onClick = { selectedFilter = MediaType.AUDIO_MP3 },
                label = { Text("Music (MP3)") },
                leadingIcon = { Icon(Icons.Default.Audiotrack, null, modifier = Modifier.size(16.dp)) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Text(
                        text = "No downloads found",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Media converted using yt-dlp will appear here for playback and sharing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredList, key = { it.id }) { item ->
                    DownloadedItemCard(
                        item = item,
                        onPlay = { onOpenMedia(item.file, item.type) },
                        onShare = { onShareMedia(item.file, item.type) },
                        onDelete = { fileToDelete = item }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DownloadedItemCard(
    item: DownloadedFile,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_item_${item.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (item.type == MediaType.VIDEO_MP4) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onPlay() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (item.type == MediaType.VIDEO_MP4) Icons.Default.Videocam else Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = if (item.type == MediaType.VIDEO_MP4) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (item.type == MediaType.VIDEO_MP4) "MP4" else "MP3",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    ) {
                        Text(
                            text = "Downloads",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = item.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier.testTag("play_button_${item.id}")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.testTag("share_button_${item.id}")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_button_${item.id}")
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
