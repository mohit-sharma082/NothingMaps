package com.shantanu.nmaps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.offline.Bounds
import com.shantanu.nmaps.offline.OfflineMaps
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import java.util.Locale

/** Share of the map (each side) left outside the download frame. */
private const val FRAME_INSET = 0.1

fun formatBytes(bytes: Long): String = when {
    bytes < 1_000_000 -> "${(bytes / 1000).coerceAtLeast(1)} KB"
    bytes < 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1e6)
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1e9)
}

@Composable
fun OfflineScreen(vm: MapViewModel) {
    val p = LocalPalette.current
    val areas by vm.offline.areas.collectAsState()
    Page("Offline maps", { vm.back() }) {
        NText(
            "Downloaded areas work with no connection: the map, search, and directions for driving, cycling and walking. " +
                "Transit, weather and speed limits still need the internet.",
            NType.caption, Modifier.padding(horizontal = 8.dp), color = p.muted,
        )
        Spacer(Modifier.height(16.dp))
        Pill("Select an area to download", vm::selectOfflineArea, Modifier.fillMaxWidth(), style = PillStyle.Filled, glyph = Glyphs.Download)
        Spacer(Modifier.height(20.dp))
        if (areas.isEmpty()) {
            EmptyState(Glyphs.Download, "Nothing downloaded", "Save an area before you travel, and the map works without signal.")
        } else {
            SectionLabel("Downloaded", Modifier.padding(start = 8.dp, bottom = 4.dp))
            areas.forEach { a ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    ListRow(
                        a.name,
                        { vm.showArea(a.bounds) },
                        when {
                            a.error != null -> a.error
                            a.complete -> "${formatBytes(a.bytes)}, ready offline"
                            a.downloading -> "Downloading ${(a.progress * 100).toInt()}%, ${formatBytes(a.bytes)}"
                            else -> "Paused at ${(a.progress * 100).toInt()}%"
                        },
                        Glyphs.Download,
                    ) {
                        Row {
                            if (!a.complete && !a.downloading) {
                                Pill("Resume", { vm.offline.resume(a.id) }, height = 36.dp)
                                Spacer(Modifier.size(6.dp))
                            }
                            RoundButton(Glyphs.Close, "Delete ${a.name}", { vm.offline.delete(a.id) }, Modifier.size(40.dp), iconModifier = Modifier.size(12.dp))
                        }
                    }
                    if (!a.complete) DottedProgress(a.progress, Modifier.padding(start = 64.dp, end = 8.dp))
                }
            }
        }
    }
}

/** The bounds inside the on-screen frame: the visible map minus [FRAME_INSET] on each side. */
fun frameBounds(visible: Bounds): Bounds {
    val dLat = (visible.north - visible.south) * FRAME_INSET
    val dLon = (visible.east - visible.west) * FRAME_INSET
    return Bounds(visible.south + dLat, visible.west + dLon, visible.north - dLat, visible.east - dLon)
}

/** Dashed frame over the map marking what will be downloaded. */
@Composable
fun OfflineFrame() {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = Offset(size.width * FRAME_INSET.toFloat(), size.height * FRAME_INSET.toFloat())
            val box = Size(size.width - inset.x * 2, size.height - inset.y * 2)
            // Dim everything outside the frame.
            val shade = p.background.copy(alpha = 0.55f)
            drawRect(shade, Offset.Zero, Size(size.width, inset.y))
            drawRect(shade, Offset(0f, size.height - inset.y), Size(size.width, inset.y))
            drawRect(shade, Offset(0f, inset.y), Size(inset.x, box.height))
            drawRect(shade, Offset(size.width - inset.x, inset.y), Size(inset.x, box.height))
            drawRoundRect(
                p.content, inset, box, CornerRadius(12.dp.toPx()),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx()))),
            )
        }
    }
}

@Composable
fun OfflineSelectSheet(vm: MapViewModel) {
    val p = LocalPalette.current
    // Keep the frame meaningful: north-up and flat.
    LaunchedEffect(Unit) { vm.resetNorth() }
    val visible = vm.visible
    Column(Modifier.sheet().padding(20.dp)) {
        SheetTitle("Download this area?", { vm.back() })
        Spacer(Modifier.height(8.dp))
        if (visible == null) {
            NText("Move the map to frame the area you need.", NType.body, color = p.muted)
            return@Column
        }
        val frame = frameBounds(visible)
        val tiles = OfflineMaps.tileCount(frame)
        val tooBig = tiles > OfflineMaps.MAX_TILES
        val firstDownload = vm.offline.areas.collectAsState().value.none { it.complete }
        NText(
            when {
                tooBig -> "This area is too large. Zoom in to choose a smaller one."
                // Map fonts (~10 MB) are fetched once and shared by every area.
                firstDownload -> "About ${formatBytes(OfflineMaps.estimatedBytes(frame) + OfflineMaps.FONT_BYTES)}, including map fonts downloaded once. Move or zoom the map to change the area."
                else -> "About ${formatBytes(OfflineMaps.estimatedBytes(frame))} for the framed area. Move or zoom the map to change it."
            },
            NType.body, color = if (tooBig) p.red else p.muted,
        )
        Spacer(Modifier.height(16.dp))
        Pill(
            "Download",
            { if (!tooBig) vm.downloadArea(frame) },
            Modifier.fillMaxWidth(),
            style = if (tooBig) PillStyle.Outlined else PillStyle.Filled,
            glyph = Glyphs.Download,
        )
    }
}

/** Shown at the top of the map while there's no connection. */
@Composable
fun OfflineBanner() {
    val p = LocalPalette.current
    Row(
        Modifier
            .padding(top = 8.dp)
            .background(p.surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(p.red, RoundedCornerShape(4.dp)))
        Spacer(Modifier.size(10.dp))
        Column {
            NText("You're offline", NType.bodyStrong)
            NText("Downloaded areas and saved places still work.", NType.caption, color = p.muted)
        }
    }
}
