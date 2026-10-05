package com.lastwave.app.ui.player

import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.FormatSize
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.Slider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable
import com.lastwave.app.data.lyrics.isRtlText
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ModernLyricsPanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    progressState: StateFlow<PlaybackProgressState>? = null,
    wavySeekbarEnabled: Boolean = true,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    onRetry: () -> Unit = {},
    onOpenLyricsOffset: (() -> Unit)? = null,
    lyricsFontScale: Float = 1.0f,
    onLyricsFontScaleChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Manual sync correction (ms, + = lyrics earlier). Applies to lyric
     *  focus/highlight only — the seekbar below keeps true position. */
    lyricsOffsetMs: Long = 0L,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    state.current ?: return

    // High-frequency progress is collected ONLY in the leaf hosts below
    // (lyrics list + controls). Collecting it here would recompose the whole
    // panel incl. AnimatedContent + badge ~16x/sec. Other players isolate it.
    val progressFlow = progressState ?: player.progressState
    val progressInitial = PlaybackProgressState(positionMs = state.positionMs, durationMs = state.durationMs)


    Column(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = lyricsState,
            transitionSpec = {
                (fadeIn(tween(ExpressiveMotion.Quick)) +
                    androidx.compose.animation.scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(ExpressiveMotion.Quick)) +
                        androidx.compose.animation.scaleOut(tween(ExpressiveMotion.Quick), targetScale = 0.96f))
            },
            label = "modernLyricsStateContent",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { targetState ->
            when (targetState) {
                is LyricsUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ExpressiveInlineLoadingIndicator(
                                size = 42.dp,
                                strokeWidth = 3.5.dp,
                                color = Color.White,
                            )
                            Text(
                                "Finding lyrics…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.70f),
                            )
                        }
                    }
                }

                is LyricsUiState.Empty, is LyricsUiState.Error -> {
                    ModernEmptyLyricsView(
                        isInstrumental = false,
                        onRetry = onRetry,
                    )
                }

                is LyricsUiState.Success -> {
                    if (targetState.isInstrumental) {
                        ModernEmptyLyricsView(
                            isInstrumental = true,
                            onRetry = onRetry,
                        )
                    } else if (targetState.isSynced && targetState.lines.isNotEmpty()) {
                        // Reference parity: one provider timestamp stays one
                        // drawn row — long rows wrap visually via Text
                        // soft-wrap on their own timestamp, never sliced
                        // into fabricated timed chunks.
                        val isWordSynced = targetState.isWordSynced ||
                            remember(targetState.lines) { targetState.lines.any { it.hasSyllables } }
                        val isOverallRtl = remember(targetState.lines) {
                            val meaningful = targetState.lines.filter { it.text.isNotBlank() && it.text != "♪" }
                            if (meaningful.isEmpty()) false
                            else meaningful.count { it.isRtl } > meaningful.size / 2
                        }
                        // Apple Music word-sync rows run full-sentence wide, so
                        // they keep a compact size while other providers use
                        // the standard tier. Sizes stay moderate so wrapped
                        // rows clear the edges at the focus zoom.
                        val isAppleMusic = remember(targetState.source) {
                            targetState.source?.contains("Apple Music", ignoreCase = true) == true
                        }
                        // Shared style instances drawn by every row below.
                        val currentTextStyle = LocalTextStyle.current
                        val karaokeNormalStyle = remember(currentTextStyle, isAppleMusic, isWordSynced, lyricsFontScale) {
                            currentTextStyle.copy(
                                fontSize = ((if (isAppleMusic) 28f else if (isWordSynced) 32f else 30f) * lyricsFontScale).sp,
                                fontWeight = FontWeight.Bold,
                                textMotion = TextMotion.Animated,
                            )
                        }
                        val karaokeAccompanimentStyle = remember(currentTextStyle, isAppleMusic, isWordSynced, lyricsFontScale) {
                            currentTextStyle.copy(
                                fontSize = ((if (isAppleMusic) 22f else if (isWordSynced) 24f else 22f) * lyricsFontScale).sp,
                                fontWeight = FontWeight.Bold,
                                textMotion = TextMotion.Animated,
                            )
                        }

                        val layoutDirection = if (isOverallRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                        // Short provider badge: makes it visible why words
                        // animate (word-sync) or just scroll (line-sync).
                        val syncLabel = remember(targetState.source, isWordSynced) {
                            val provider = targetState.source
                                ?.substringBefore(" (")
                                ?.takeIf { it.isNotBlank() } ?: "Lyrics"
                            "${if (isWordSynced) "WORD SYNC" else "LINE SYNC"} • $provider"
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 8.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = liquidGlassContainerColor(Color.White.copy(alpha = 0.16f)),
                                        contentColor = Color.White.copy(alpha = 0.92f),
                                    ) {
                                        Text(
                                            text = syncLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                letterSpacing = 0.6.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                                SyncedLyricsProgressHost(
                                    lines = targetState.lines,
                                    isOverallRtl = isOverallRtl,
                                    normalStyle = karaokeNormalStyle,
                                    accompanimentStyle = karaokeAccompanimentStyle,
                                    progressFlow = progressFlow,
                                    progressInitial = progressInitial,
                                    state = state,
                                    player = player,
                                    lyricsOffsetMs = lyricsOffsetMs,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                )
                            }
                        }
                    } else if (!targetState.plainLyrics.isNullOrBlank()) {
                        ModernPlainLyricsView(
                            plainLyrics = targetState.plainLyrics,
                            lyricsFontScale = lyricsFontScale,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ModernEmptyLyricsView(
                            isInstrumental = false,
                            onRetry = onRetry,
                        )
                    }
                }

                is LyricsUiState.Idle -> {
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        ModernLyricsControlsHost(
            state = state,
            progressFlow = progressFlow,
            progressInitial = progressInitial,
            player = player,
            wavySeekbarEnabled = wavySeekbarEnabled,
            onToggleFullscreen = onToggleFullscreen,
            isFullscreen = isFullscreen,
            lyricsOffsetMs = lyricsOffsetMs,
            onOpenLyricsOffset = onOpenLyricsOffset,
            lyricsFontScale = lyricsFontScale,
            onLyricsFontScaleChange = onLyricsFontScaleChange,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp, top = 6.dp),
        )
    }
}

/**
 * Reference lyrics sync list (Metrolist/SimpMusic parity, no gradle canvas).
 *
 * Sync contract, identical for word-by-word and line-by-line rows:
 * - One provider timestamp = one drawn row. Rows are never merged, split,
 *   or re-timed here; [LyricLine.timeMs] is the single source of truth.
 * - The active row is the last row with `timeMs <= effectivePosition`
 *   (binary search, same as the classic view and the reference clients'
 *   `findCurrentLineIndex`). A row's end is implied by the next row's start,
 *   never stored, so overlapping focus windows are impossible by
 *   construction (the old `maxOf(naturalEnd, nextStart)` mapping kept two
 *   rows lit around every boundary).
 * - Word fill reads each syllable's own `[timeMs, timeMs + durationMs)`
 *   window (already de-overlapped upstream by `normalizeLyricTiming`). A
 *   zero-duration syllable holds until the next syllable starts, matching
 *   the reference `findActiveLineIndices` end-time fallback.
 * - Position is the raw playhead plus the manual offset on every tick — no
 *   time quantization. Reference clients sample per frame (8ms /
 *   withFrameNanos); the 60ms progress ticker feeding this list is already
 *   the coarsest step word fills can tolerate (the old 120ms bucketing
 *   visibly stalled syllables 100-200ms long).
 * - Long rows wrap visually via [Text] soft-wrap on their own timestamp.
 *   They are never sliced into fabricated timed chunks: the old
 *   proportional-width slicing invented timestamps the vocals never sang,
 *   which is why line-by-line drifted and word-by-word stalled.
 */
@Composable
private fun ModernSyncedLyricsList(
    lines: List<LyricLine>,
    isOverallRtl: Boolean,
    normalStyle: TextStyle,
    accompanimentStyle: TextStyle,
    currentPositionMs: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    lyricsOffsetMs: Long = 0L,
) {
    val listState = rememberLazyListState()
    var userScrolledTime by remember { mutableLongStateOf(0L) }
    val currentPositionMsState by rememberUpdatedState(currentPositionMs)

    val activeIndex by remember(lines) {
        derivedStateOf { activeLineIndex(lines, currentPositionMsState()) }
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    if (isDragged) {
        userScrolledTime = System.currentTimeMillis()
    }

    LaunchedEffect(activeIndex, isPlaying) {
        val timeSinceUserScroll = System.currentTimeMillis() - userScrolledTime
        if (timeSinceUserScroll > 2200L && activeIndex in lines.indices) {
            runCatching {
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                val targetItem = layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                if (targetItem != null && viewportHeight > 0) {
                    val targetY = viewportHeight / 3
                    val delta = targetItem.offset - targetY
                    listState.animateScrollBy(delta.toFloat())
                } else {
                    val targetIndex = (activeIndex - 1).coerceAtLeast(0)
                    listState.animateScrollToItem(index = targetIndex, scrollOffset = 0)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.clipToBounds(),
        contentPadding = PaddingValues(top = 40.dp, bottom = 130.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        itemsIndexed(lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
            val isActive = index == activeIndex
            val isPast = activeIndex >= 0 && index < activeIndex
            val distance = kotlin.math.abs(index - activeIndex)
            val isBgRow = line.syllables.isNotEmpty() && line.syllables.all { it.isBackground }
            val isLineRtl = remember(line, isOverallRtl) {
                line.isRtl || (isOverallRtl && (line.text.isBlank() || line.text == "♪"))
            }

            val scaleTarget = if (isActive) 1.06f else if (distance == 1) 0.99f else 0.97f
            val scale by animateFloatAsState(
                targetValue = scaleTarget,
                animationSpec = spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow),
                label = "modernLyricScale_$index",
            )
            val alphaTarget = if (isActive) 1f else if (distance == 1) 0.64f else if (isPast) 0.50f else 0.43f
            val alpha by animateFloatAsState(
                targetValue = alphaTarget,
                animationSpec = tween(160),
                label = "modernLyricAlpha_$index",
            )

            val lineLayoutDirection = if (isLineRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides lineLayoutDirection) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha * if (isBgRow) (if (isActive) 0.85f else 0.55f) else 1f
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onSeek(line.timeMs)
                        }
                        .padding(horizontal = 12.dp, vertical = if (isActive) 8.dp else 6.dp),
                ) {
                    val fontStyle = if (isBgRow) accompanimentStyle else normalStyle
                    ModernWordByWordLine(
                        line = line,
                        currentPositionMs = currentPositionMs,
                        isActive = isActive,
                        activeColor = Color.White,
                        inactiveColor = Color.White.copy(alpha = 0.55f),
                        fontStyle = fontStyle,
                        isRtl = isLineRtl,
                        modifier = Modifier.padding(start = if (isBgRow) 14.dp else 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernWordByWordLine(
    line: LyricLine,
    currentPositionMs: () -> Long,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    fontStyle: TextStyle,
    isRtl: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val lineLayoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides lineLayoutDirection) {
        if (!line.hasSyllables || !isActive) {
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = fontStyle,
                    color = if (isActive) activeColor else inactiveColor,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!line.transliteration.isNullOrBlank() && isActive) {
                    Text(
                        text = line.transliteration,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp,
                        ),
                        color = activeColor.copy(alpha = 0.72f),
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                    )
                }
            }
            return@CompositionLocalProvider
        }

        val displayTexts = remember(line) { resolveSyllableDisplayTexts(line) }
        val fullText = remember(line, displayTexts) {
            line.syllables.indices.joinToString("") { displayTexts.getOrElse(it) { line.syllables[it].text } }
        }
        // Char range of each syllable inside [fullText].
        val charRanges = remember(line, displayTexts) {
            var offset = 0
            line.syllables.indices.map { i ->
                val start = offset
                offset += displayTexts.getOrElse(i) { line.syllables[i].text }.length
                start to offset
            }
        }
        var layout by remember(fullText) { mutableStateOf<TextLayoutResult?>(null) }

        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            // Karaoke fill: dim base text + bright copy clipped to the sung
            // extent. Position is read ONLY in the draw phase, so the fill
            // advances every display frame with zero recomposition/relayout.
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = fullText,
                    style = fontStyle,
                    color = inactiveColor.copy(alpha = 0.44f),
                    textAlign = TextAlign.Start,
                    onTextLayout = { layout = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = fullText,
                    style = fontStyle,
                    color = activeColor,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            val l = layout ?: return@drawWithContent
                            val textLength = fullText.length
                            if (textLength == 0) return@drawWithContent
                            val pos = currentPositionMs()
                            var fill = 0f
                            for (i in line.syllables.indices) {
                                val syl = line.syllables[i]
                                if (pos < syl.timeMs) break
                                val (cs, ce) = charRanges[i]
                                val p = if (syl.durationMs <= 0L) 1f
                                else ((pos - syl.timeMs).toFloat() / syl.durationMs).coerceIn(0f, 1f)
                                fill = cs + (ce - cs) * p
                                if (p < 1f) break
                            }
                            if (fill <= 0f) return@drawWithContent
                            if (fill >= textLength) {
                                drawContent()
                                return@drawWithContent
                            }
                            val charIdx = fill.toInt().coerceIn(0, textLength - 1)
                            val frac = fill - charIdx
                            val lineIdx = l.getLineForOffset(charIdx)
                            val x0 = l.getHorizontalPosition(charIdx, true)
                            val x1 = if (charIdx + 1 < textLength && l.getLineForOffset(charIdx + 1) == lineIdx) {
                                l.getHorizontalPosition(charIdx + 1, true)
                            } else if (isRtl) l.getLineLeft(lineIdx) else l.getLineRight(lineIdx)
                            val x = x0 + (x1 - x0) * frac
                            val top = l.getLineTop(lineIdx)
                            val bottom = l.getLineBottom(lineIdx)
                            if (top > 0f) {
                                clipRect(0f, 0f, size.width, top) { this@drawWithContent.drawContent() }
                            }
                            if (isRtl) {
                                clipRect(x, top, size.width, bottom) { this@drawWithContent.drawContent() }
                            } else {
                                clipRect(0f, top, x, bottom) { this@drawWithContent.drawContent() }
                            }
                        },
                )
            }

            if (!line.transliteration.isNullOrBlank()) {
                Text(
                    text = line.transliteration,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp,
                    ),
                    color = activeColor.copy(alpha = 0.76f),
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ModernPlainLyricsView(
    plainLyrics: String,
    lyricsFontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val isRtl = remember(plainLyrics) { isRtlText(plainLyrics) }
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier
                .verticalScroll(scrollState)
                .padding(top = 24.dp, bottom = 90.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.SyncDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White.copy(alpha = 0.90f),
                )
                Text(
                    "Lyrics not time-synced",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.70f),
                )
            }

            Text(
                text = plainLyrics,
                style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = (28f * lyricsFontScale).sp,
lineHeight = (46f * lyricsFontScale).sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.1.sp,
                ),
                textAlign = TextAlign.Start,
                color = Color.White.copy(alpha = 0.94f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModernEmptyLyricsView(
    isInstrumental: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = if (isInstrumental) Icons.Filled.MusicOff else Icons.Filled.Lyrics,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = Color.White.copy(alpha = 0.90f),
            )

            Text(
                text = if (isInstrumental) "Instrumental" else "No lyrics",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            Text(
                text = if (isInstrumental) {
                    "This track has no vocal lyrics."
                } else {
                    "No synced lyrics found for this track."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )

            if (!isInstrumental) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }
        }
    }
}

/**
 * Leaf host: collects progress ONLY for the lyrics list, so badge +
 * AnimatedContent above never recompose on ticks. Reference-client parity
 * (Metrolist/SimpMusic): the raw playhead position drives focus every tick
 * with no time quantization — word fills step at syllable granularity
 * (>=50ms), so throttling would visibly stall the karaoke highlight.
 */
@Composable
private fun SyncedLyricsProgressHost(
    lines: List<com.lastwave.app.data.lyrics.LyricLine>,
    isOverallRtl: Boolean,
    normalStyle: TextStyle,
    accompanimentStyle: TextStyle,
    progressFlow: StateFlow<PlaybackProgressState>,
    progressInitial: PlaybackProgressState,
    state: MusicPlayerState,
    player: MusicPlayer,
    lyricsOffsetMs: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = progressInitial)
    val latestSampleMs by rememberUpdatedState(progress.positionMs)
    val offsetState by rememberUpdatedState(lyricsOffsetMs)
    val framePositionMs = remember { mutableLongStateOf(progress.positionMs) }
    // Vsync clock (90/120Hz): advances by real frame time and is steered
    // toward the ExoPlayer playhead (sampled every ~60ms). Never runs
    // backwards on normal drift; snaps instantly on seeks/track changes.
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) {
            snapshotFlow { latestSampleMs }.collect { framePositionMs.longValue = it }
            return@LaunchedEffect
        }
        var lastSample = latestSampleMs
        var sampleFrameNanos = -1L
        var lastFrameNanos = -1L
        var display = latestSampleMs.toDouble()
        while (true) {
            withFrameNanos { now ->
                val sample = latestSampleMs
                if (sample != lastSample || sampleFrameNanos < 0L) {
                    lastSample = sample
                    sampleFrameNanos = now
                }
                val target = sample + ((now - sampleFrameNanos) / 1_000_000.0).coerceAtMost(250.0)
                val frameDt = if (lastFrameNanos < 0L) 0.0 else (now - lastFrameNanos) / 1_000_000.0
                lastFrameNanos = now
                val predicted = display + frameDt
                val err = target - predicted
                display = if (kotlin.math.abs(err) > 300.0) {
                    target
                } else {
                    maxOf(display, predicted + err * 0.2)
                }
                framePositionMs.longValue = display.toLong()
            }
        }
    }
    val currentPosition: () -> Long = remember {
        { framePositionMs.longValue + offsetState }
    }
    ModernSyncedLyricsList(
        lines = lines,
        isOverallRtl = isOverallRtl,
        normalStyle = normalStyle,
        accompanimentStyle = accompanimentStyle,
        currentPositionMs = currentPosition,
        isPlaying = state.isPlaying,
        onSeek = { lineTimeMs ->
            // Inverse of the highlight shift: tap targets audio time.
            player.seekTo((lineTimeMs - lyricsOffsetMs).coerceAtLeast(0L))
        },
        lyricsOffsetMs = lyricsOffsetMs,
        modifier = modifier,
    )
}

/**
 * Leaf host for transport controls: owns the 60ms seekbar clock so the panel
 * above stays static. No behavior change, strictly fewer recompositions.
 */
@Composable
private fun ModernLyricsControlsHost(
    state: MusicPlayerState,
    progressFlow: StateFlow<PlaybackProgressState>,
    progressInitial: PlaybackProgressState,
    player: MusicPlayer,
    wavySeekbarEnabled: Boolean = true,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    lyricsOffsetMs: Long = 0L,
    onOpenLyricsOffset: (() -> Unit)? = null,
    lyricsFontScale: Float = 1f,
    onLyricsFontScaleChange: (Float) -> Unit = {},
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    modifier: Modifier = Modifier,
) {
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = progressInitial)
    ModernLyricsControls(
        state = state,
        currentPositionMs = progress.positionMs,
        totalDurationMs = if (progress.durationMs > 0) progress.durationMs else state.durationMs,
        player = player,
        wavySeekbarEnabled = wavySeekbarEnabled,
        onToggleFullscreen = onToggleFullscreen,
        isFullscreen = isFullscreen,
        lyricsOffsetMs = lyricsOffsetMs,
        onOpenLyricsOffset = onOpenLyricsOffset,
        lyricsFontScale = lyricsFontScale,
        onLyricsFontScaleChange = onLyricsFontScaleChange,
        primaryColor = primaryColor,
        secondaryColor = secondaryColor,
        tertiaryColor = tertiaryColor,
        modifier = modifier,
    )
}

@Composable
private fun ModernLyricsControls(
    state: MusicPlayerState,
    currentPositionMs: Long,
    totalDurationMs: Long,
    player: MusicPlayer,
    wavySeekbarEnabled: Boolean = true,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    lyricsOffsetMs: Long = 0L,
    onOpenLyricsOffset: (() -> Unit)? = null,
    lyricsFontScale: Float = 1f,
    onLyricsFontScaleChange: (Float) -> Unit = {},
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    modifier: Modifier = Modifier,
) {
    var showFontSlider by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = showFontSlider,
            enter = fadeIn(tween(150)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Lyrics size: ${(lyricsFontScale * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.90f),
                        )
                        if (lyricsFontScale != 1.0f) {
                            TextButton(
                                onClick = { onLyricsFontScaleChange(1.0f) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp),
                            ) {
                                Text(
                                    "Reset",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "A",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.70f),
                        )
                        Slider(
                            value = lyricsFontScale,
                            onValueChange = onLyricsFontScaleChange,
                            valueRange = 0.7f..1.5f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "A",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.95f),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Lyrics Font Scale toggle button placed immediately to the LEFT of Full Screen
            val fontInteraction = remember { MutableInteractionSource() }
            val isFontPressed by fontInteraction.collectIsPressedAsState()
            val fontScaleAnim by animateFloatAsState(
                targetValue = if (isFontPressed) 0.82f else 1.0f,
                animationSpec = ExpressiveMotion.spatialSpring(),
                label = "fontScaleAnim",
            )
            val isCustomFont = lyricsFontScale != 1.0f
            IconButton(
                onClick = { showFontSlider = !showFontSlider },
                interactionSource = fontInteraction,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = fontScaleAnim
                        scaleY = fontScaleAnim
                    }
                    .clip(CircleShape)
                    .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = fontInteraction)
                    .background(
                        liquidGlassContainerColor(
                            if (showFontSlider || isCustomFont) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                            else Color.White.copy(alpha = 0.14f)
                        ),
                    ),
            ) {
                Icon(
                    Icons.Filled.FormatSize,
                    contentDescription = "Adjust lyrics text size",
                    modifier = Modifier.size(22.dp),
                    tint = if (showFontSlider || isCustomFont) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.90f),
                )
            }

            Spacer(Modifier.width(10.dp))

            if (onToggleFullscreen != null) {
                val playerInteraction = remember { MutableInteractionSource() }
                val isPlayerPressed by playerInteraction.collectIsPressedAsState()
                val playerScale by animateFloatAsState(
                    targetValue = if (isPlayerPressed) 0.82f else 1.0f,
                    animationSpec = ExpressiveMotion.spatialSpring(),
                    label = "playerTabScale",
                )
                IconButton(
                    onClick = onToggleFullscreen,
                    interactionSource = playerInteraction,
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer {
                            scaleX = playerScale
                            scaleY = playerScale
                        }
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = playerInteraction)
                        .background(
                            liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                        ),
                ) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "Exit fullscreen lyrics" else "Fullscreen lyrics",
                        modifier = Modifier.size(24.dp),
                        tint = Color.White.copy(alpha = 0.90f),
                    )
                }
            }
        }

        if (isFullscreen) return@Column

        // Current-gesture value only; null = finger off, show live position.
        // Keyed by track so a previous song's drag can never leak into this
        // one, and nullable so a press without movement seeks nowhere while a
        // gesture that ends without onValueChangeFinished can't pin the bar.
        val lyricsTrackKey = state.current?.let { it.videoId ?: "${it.artist}|${it.title}" }
        val seekInteraction = remember(lyricsTrackKey) { MutableInteractionSource() }
        var isInteracting by remember(lyricsTrackKey) { mutableStateOf(false) }
        LaunchedEffect(seekInteraction, lyricsTrackKey) {
            var dragCount = 0
            var pressCount = 0
            seekInteraction.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> dragCount++
                    is DragInteraction.Stop, is DragInteraction.Cancel -> dragCount = maxOf(0, dragCount - 1)
                    is PressInteraction.Press -> pressCount++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressCount = maxOf(0, pressCount - 1)
                }
                isInteracting = dragCount > 0 || pressCount > 0
            }
        }
        var dragValue by remember(lyricsTrackKey) { mutableStateOf<Float?>(null) }
        var lastSeekValue by remember(lyricsTrackKey) { mutableStateOf<Float?>(null) }
        LaunchedEffect(isInteracting, lyricsTrackKey) {
            if (!isInteracting) {
                delay(120L)
                dragValue = null
                lastSeekValue = null
            }
        }
        LaunchedEffect(dragValue, isInteracting, lyricsTrackKey) {
            if (dragValue != null && !isInteracting) {
                delay(250L)
                dragValue = null
                lastSeekValue = null
            }
        }
        val end = totalDurationMs.coerceAtLeast(1).toFloat()
        val shown = (dragValue ?: currentPositionMs.coerceIn(0, totalDurationMs.coerceAtLeast(0)).toFloat())
            .coerceIn(0f, end)

        if (wavySeekbarEnabled) {
            WavySeekBar(
                positionMs = currentPositionMs,
                durationMs = totalDurationMs,
                isPlaying = state.isPlaying,
                onSeek = player::seekTo,
                isTranslucent = true,
                trackKey = state.current?.let { it.videoId ?: "${it.artist}|${it.title}" },
                showTimeLabels = false,
                modifier = Modifier.fillMaxWidth(),
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                tertiaryColor = tertiaryColor,
            )
        } else {
            PlayerProgressSlider(
                value = shown,
                onValueChange = {
                    dragValue = it
                    lastSeekValue = it
                },
                onValueChangeFinished = {
                    // Commit only this gesture's value; no value = no seek.
                    val target = (lastSeekValue ?: dragValue)?.toLong()
                    dragValue = null
                    lastSeekValue = null
                    if (target != null) player.seekTo(target)
                },
                valueRange = 0f..end,
                enabled = totalDurationMs > 0,
                modifier = Modifier.fillMaxWidth(),
                interactionSource = seekInteraction,
                primaryColor = primaryColor,
                tertiaryColor = tertiaryColor,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatTime(shown.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val prevInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = player::previous,
                    interactionSource = prevInteraction,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = prevInteraction)
                        .background(liquidGlassContainerColor(Color.White.copy(alpha = 0.14f))),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        "Previous",
                        Modifier.size(24.dp),
                        tint = Color.White.copy(alpha = 0.94f),
                    )
                }

                val playInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = player::togglePlayPause,
                    interactionSource = playInteraction,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = playInteraction)
                        .background(liquidGlassContainerColor(Color.White.copy(alpha = 0.18f))),
                ) {
                    if (state.isBuffering) {
                        ExpressiveInlineLoadingIndicator(
                            size = 24.dp,
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(28.dp))
                    }
                }

                val nextInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = player::next,
                    interactionSource = nextInteraction,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = nextInteraction)
                        .background(liquidGlassContainerColor(Color.White.copy(alpha = 0.14f))),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        "Next",
                        Modifier.size(24.dp),
                        tint = Color.White.copy(alpha = 0.94f),
                    )
                }
            }

            Text(
                "−${formatTime((totalDurationMs - shown.toLong()).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Animated pixel scroll for [LazyListState], which only ships instant
 * [LazyListState.scrollBy] and indexed [LazyListState.animateScrollToItem].
 * Ease-out-cubic frame loop so the active-line follow stays smooth instead
 * of jumping. Callers already guard with runCatching. Mirrors the classic
 * view's helper (same package, file-private there so duplicated here).
 */
private suspend fun LazyListState.animateScrollBy(pixels: Float) {
    if (pixels == 0f) return
    var consumed = 0f
    var startNanos = -1L
    var done = false
    while (!done) {
        val target = withFrameNanos { now ->
            if (startNanos < 0L) startNanos = now
            val t = ((now - startNanos) / 350_000_000f).coerceIn(0f, 1f)
            done = t >= 1f
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            pixels * eased
        }
        val delta = target - consumed
        val scrolled = scrollBy(delta)
        consumed += scrolled
        if (scrolled == 0f && delta != 0f) {
            break
        }
    }
}
