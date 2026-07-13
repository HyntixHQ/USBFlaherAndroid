package com.hyntix.android.usbflasher.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.Trash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.util.AppLogger
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    
    val logs by AppLogger.logFlow.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val horizontalScrollState = rememberScrollState()
    val shareLogsTitle = stringResource(R.string.share_logs_title)
    val logsEmpty = stringResource(R.string.logs_empty)

    // Track whether user has manually scrolled away from the bottom.
    // When true, new logs won't force-scroll to bottom.
    var userScrolledUp by remember { mutableStateOf(false) }

    // Detect user scroll via snapshotFlow of firstVisibleItemIndex.
    // If the first visible item isn't at the very end, user scrolled up.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@snapshotFlow true
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleIndex < totalItems - 1
        }.collect { isScrolledUp ->
            userScrolledUp = isScrolledUp
        }
    }

    // Auto-scroll to bottom when new logs arrive, but only if user is at bottom
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && !userScrolledUp) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.logs_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = stringResource(R.string.cd_back))
                        }
                },
                actions = {
                    // Share button
                    IconButton(onClick = {
                        val logFile = AppLogger.getLogFile()
                        if (logFile != null && logFile.exists()) {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    logFile
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, shareLogsTitle))
                            } catch (e: Exception) {
                                // FileProvider not configured — fall back to text share
                                scope.launch {
                                    val text = AppLogger.getAllLogs()
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, shareLogsTitle))
                                }
                            }
                        }
                    }) {
                        Icon(PhosphorIcons.Regular.ShareNetwork, contentDescription = stringResource(R.string.cd_share_logs))
                    }
                    // Clear button
                    IconButton(onClick = {
                        scope.launch { AppLogger.clearLogs() }
                    }) {
                        Icon(PhosphorIcons.Regular.Trash, contentDescription = stringResource(R.string.cd_clear_logs))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    logsEmpty,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(logs) { line ->
                    val color = when {
                        line.contains(" E/") -> MaterialTheme.colorScheme.error
                        line.contains(" W/") -> Color(0xFFFFB74D) // Amber
                        line.contains(" D/") -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    }
                    val bgColor = when {
                        line.contains(" E/") -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = color,
                        maxLines = 3,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .horizontalScroll(horizontalScrollState)
                            .widthIn(min = 1000.dp)
                            .background(bgColor)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
