package com.lastwave.trueglass.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.toIntSize

/**
 * Single GPU backdrop (RenderNode offscreen, stays on GPU — never a Bitmap,
 * never uploaded from CPU, never a GLSurfaceView). One per window scope,
 * shared by all glass panels beneath it.
 *
 * Ownership rule (crash-proofing): consumers must be SIBLINGS of the source
 * carrier, never descendants of their own capture. Recording a layer inside
 * its own draw = feedback loop / native crash (Kyant issue 54 class).
 */
@Stable
class TrueGlassBackdropState internal constructor(
    internal val layer: GraphicsLayer,
) {
    internal var recorded: Boolean = false
}

@Composable
fun rememberTrueGlassBackdrop(): TrueGlassBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { TrueGlassBackdropState(layer) }
}

/** Attach to the background sibling that glass panels sample. */
fun Modifier.trueGlassSource(state: TrueGlassBackdropState?): Modifier {
    if (state == null) return this
    return this.drawWithContent {
        runCatching {
            state.layer.record(this, layoutDirection, size.toIntSize()) {
                this@drawWithContent.drawContent()
            }
            state.recorded = true
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
