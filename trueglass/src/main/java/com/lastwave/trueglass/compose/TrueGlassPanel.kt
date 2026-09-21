package com.lastwave.trueglass.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.lastwave.trueglass.engine.GlassConfig

@Composable
fun TrueGlassPanel(
    backdrop: TrueGlassBackdropState?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    config: GlassConfig = GlassConfig.Card,
    isDark: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.trueGlassChrome(shape, config, backdrop, true, isDark),
        contentAlignment = contentAlignment,
        content = content,
    )
}

@Composable
fun TrueGlassIconButton(
    backdrop: TrueGlassBackdropState?,
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = Color.White,
    isDark: Boolean = true,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .trueGlassChrome(shape, GlassConfig.IconButton, backdrop, true, isDark)
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painter,
            contentDescription = contentDescription,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
fun TrueGlassActionPill(
    backdrop: TrueGlassBackdropState?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    isDark: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .trueGlassChrome(shape, GlassConfig.Control, backdrop, true, isDark),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
