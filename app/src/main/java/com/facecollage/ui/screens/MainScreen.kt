package com.facecollage.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.facecollage.domain.model.PersonCluster
import com.facecollage.domain.model.ProcessingState
import com.facecollage.ui.MainViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state        by viewModel.state.collectAsState()
    val selectedUri  by viewModel.selectedVideoUri.collectAsState()
    val videoName    by viewModel.videoName.collectAsState()
    val context      = LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Resolve display name from ContentResolver
            var name: String? = null
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
            }
            viewModel.onVideoSelected(it, name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Face Collage",
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.primary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Video picker card ─────────────────────────────────────────
            VideoPickerCard(
                videoName  = videoName,
                onPickVideo = { videoPicker.launch("video/*") }
            )

            Spacer(Modifier.height(16.dp))

            // ── Action button ─────────────────────────────────────────────
            AnimatedVisibility(visible = selectedUri != null) {
                when (state) {
                    is ProcessingState.Idle -> {
                        Button(
                            onClick   = viewModel::startProcessing,
                            modifier  = Modifier.fillMaxWidth().height(52.dp),
                            shape     = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Process Video", fontSize = 16.sp)
                        }
                    }
                    is ProcessingState.Done -> {
                        OutlinedButton(
                            onClick  = viewModel::resetState,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Process Another Video")
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick  = viewModel::cancelProcessing,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Cancel")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── State display ─────────────────────────────────────────────
            AnimatedContent(targetState = state, label = "state_content") { currentState ->
                when (currentState) {
                    is ProcessingState.Idle          -> if (selectedUri == null) IdleHint()
                    is ProcessingState.Extracting    -> ProgressCard(
                        label         = "Extracting frames…",
                        detail        = "${currentState.frameCount} frames extracted",
                        progress      = currentState.progress
                    )
                    is ProcessingState.Detecting     -> ProgressCard(
                        label         = "Detecting & embedding faces…",
                        detail        = "${currentState.detectedCount} faces found so far",
                        progress      = currentState.progress
                    )
                    is ProcessingState.Clustering    -> ProgressCard(
                        label         = "Identifying people…",
                        detail        = "Clustering embeddings with DBSCAN",
                        progress      = currentState.progress,
                        indeterminate = currentState.progress == 0f
                    )
                    is ProcessingState.BuildingCollage -> ProgressCard(
                        label         = "Building collage…",
                        detail        = "",
                        progress      = currentState.progress
                    )
                    is ProcessingState.Done          -> ResultScreen(
                        clusters     = currentState.clusters,
                        collagePath  = currentState.collagePath,
                        onShare      = { viewModel.shareCollage(currentState.collagePath) }
                    )
                    is ProcessingState.Error         -> ErrorCard(message = currentState.message)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun VideoPickerCard(videoName: String?, onPickVideo: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onPickVideo),
        color         = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.VideoFile,
                    contentDescription = null,
                    tint   = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text  = if (videoName != null) "Video selected" else "Select a video",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text      = videoName ?: "Tap to pick a portrait video",
                    style     = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color     = MaterialTheme.colorScheme.onSurface,
                    maxLines  = 1,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ProgressCard(
    label: String,
    detail: String,
    progress: Float,
    indeterminate: Boolean = false,
) {
    Surface(
        modifier      = Modifier.fillMaxWidth(),
        color         = MaterialTheme.colorScheme.surface,
        shape         = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(12.dp))
            if (indeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color    = MaterialTheme.colorScheme.primary,
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color    = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(progress * 100).toInt()}%",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun ResultScreen(
    clusters: List<PersonCluster>,
    collagePath: String,
    onShare: () -> Unit,
) {
    Column {
        // ── Summary chips ─────────────────────────────────────────────────
        Row(
            modifier             = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatChip(icon = Icons.Default.People,     value = "${clusters.size}",
                label = "People")
            StatChip(icon = Icons.Default.Visibility, value = "${clusters.sumOf { it.appearanceCount }}",
                label = "Appearances")
        }

        Spacer(Modifier.height(16.dp))

        // ── Collage preview ────────────────────────────────────────────────
        Text("Generated Collage", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        AsyncImage(
            model             = File(collagePath),
            contentDescription = "Collage",
            modifier          = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(16.dp)),
            contentScale      = ContentScale.Fit,
        )

        Spacer(Modifier.height(12.dp))

        // ── Share / Save ───────────────────────────────────────────────────
        Button(
            onClick  = onShare,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Save & Share", fontSize = 16.sp)
        }

        Spacer(Modifier.height(20.dp))

        // ── Per-person breakdown ───────────────────────────────────────────
        Text("People Detected", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        clusters.forEachIndexed { index, cluster ->
            PersonRow(index = index, cluster = cluster)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RowScope.StatChip(
    icon: ImageVector,
    value: String,
    label: String,
) {
    Surface(
        modifier = Modifier.weight(1f),
        color    = MaterialTheme.colorScheme.primaryContainer,
        shape    = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun PersonRow(index: Int, cluster: PersonCluster) {
    Surface(
        color         = MaterialTheme.colorScheme.surface,
        shape         = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model             = cluster.representative.bitmap,
                contentDescription = "Person ${index + 1}",
                modifier          = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale      = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Person ${index + 1}",
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${cluster.appearanceCount} appearance${if (cluster.appearanceCount != 1) "s" else ""}  ·  " +
                            "${cluster.faces.size} detections",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // Quality indicator badge
            val quality = cluster.representative.score.composite
            Surface(
                color = when {
                    quality > 0.7f -> Color(0xFF1E6B34)
                    quality > 0.4f -> Color(0xFF5C4200)
                    else           -> Color(0xFF6B1E1E)
                },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = when {
                        quality > 0.7f -> "Great"
                        quality > 0.4f -> "Good"
                        else           -> "Fair"
                    },
                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style      = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = MaterialTheme.colorScheme.errorContainer,
        shape    = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, contentDescription = null,
                tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun IdleHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Face,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint     = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Pick a portrait video to detect\nand collage unique faces",
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}