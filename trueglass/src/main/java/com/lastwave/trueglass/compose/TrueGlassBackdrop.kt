package com.lastwave.trueglass.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toIntSize

/**
 * Single GPU backdrop (RenderNode offscreen, stays on GPU — never a Bitmap,
 * never uploaded from CPU, never a GLSurfaceView). One per window scope,
 * shared by all glass panels beneath it.
 *
 * Ownership rule (crash-proofing): consumers must be SIBLINGS of the source
 * carrier, never descendants of their own capture. Recording a layer inside
 * its own draw = feedback loop / native crash (Kyant issue 54 class).
 *
 * Alignment: the source records its own [sourceCoordinates] so each glass
 * panel can translate the shared fullscreen capture to the pixels actually
 * behind it. Without this the lens would sample the wrong region (top-left
 * content drawn under a bottom dock) and read as generic frost.
 */
@Stable
class TrueGlassBackdropState internal constructor(
    internal val layer: GraphicsLayer,
) {
    // Snapshot state: composition observes the first successful record and
    // switches never-recorded consumers to the static fallback (no wasted
    // RenderEffects on backdrops that are never attached to a source).
    internal var recorded by mutableStateOf(false)
    internal var sourceCoordinates: LayoutCoordinates? = null
    internal var isRecording = false
    internal var sourceAnchor: Offset = Offset.Unspecified
}

@Composable
fun rememberTrueGlassBackdrop(): TrueGlassBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { TrueGlassBackdropState(layer) }
}

/** Attach to the background sibling that glass panels sample. */
fun Modifier.trueGlassSource(state: TrueGlassBackdropState?): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned {
            state.sourceCoordinates = it
            val pos = it.positionInRoot()
            if (state.sourceAnchor != pos) state.sourceAnchor = pos
        }
        .drawWithContent {
            runCatching {
                state.isRecording = true
                try {
                    state.layer.record(this, layoutDirection, size.toIntSize()) {
                        this@drawWithContent.drawContent()
                    }
                    state.recorded = true
                } finally {
                    state.isRecording = false
                }
            }
            drawContent()
        }
}

/** Draw the recorded backdrop. Internal use. */
internal fun ContentDrawScope.drawTrueGlassBackdrop(
    state: TrueGlassBackdropState?,
) {
    if (state == null || !state.recorded) return
    runCatching { drawLayer(state.layer) }
}
