package com.lastwave.app.ui.shell

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import android.view.HapticFeedbackConstants
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.PredictiveBackScreen
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.feed.FeedScreen
import com.lastwave.app.ui.home.HomeScreen
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.playlist.PlaylistScreen
import androidx.compose.foundation.shape.CornerBasedShape
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.lastwave.app.ui.theme.LocalIsDarkTheme
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LayerBackdrop
import com.lastwave.app.ui.theme.SquircleShape
import com.lastwave.app.ui.theme.rememberLayerBackdrop
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.liquidGlassSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Thin bridge exposing AppUpdateManager's live update state to MainShell */
@HiltViewModel
class MainShellViewModel @Inject constructor(
    val appUpdateManager: com.lastwave.app.data.update.AppUpdateManager,
) : ViewModel() {
    val updateInfo = appUpdateManager.updateInfo

    fun dismissUpdate(version: String) {
        appUpdateManager.dismissUpdate(version)
    }

    fun openUpdate(context: android.content.Context) {
        appUpdateManager.openUpdate(context)
    }
}

private enum class MainTab(val labelRes: Int) {
    FEED(com.lastwave.app.R.string.nav_feed),
    STATS(com.lastwave.app.R.string.nav_stats),
    PLAYLISTS(com.lastwave.app.R.string.nav_playlists),
}

/** Shared with any screen hosted inside [MainShell] so their scrolling
 *  lists know how much bottom content padding to reserve — the nav
 *  overlays content (it's not a Scaffold bottomBar reserving space), so
 *  each screen leaves this much room for its last item to clear it. */
object FloatingNavDefaults {
    val ContentBottomPadding = 112.dp

    /**
     * Full bottom clearance for edge-to-edge scrolling content: the floating
     * dock's visual height + margins ([ContentBottomPadding]) PLUS the live
     * navigation-bar (gesture area) inset. Screens that let their list draw
     * beneath the transparent gesture area must use this instead of the raw
     * constant, otherwise the last row hides behind the dock/gesture bar.
     */
    @Composable
    fun contentBottomPadding(): Dp =
        ContentBottomPadding +
            LocalMiniPlayerScrollClearance.current +
            WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
}

private val DockShape: CornerBasedShape = SquircleShape(percent = 50)
private val PillShape: CornerBasedShape = SquircleShape(percent = 50)

private fun <T> navSpring() = ExpressiveMotion.spatialSpring<T>()

@Composable
fun MainShell(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenGenres: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenFriendProfile: (username: String, displayName: String?, avatarUrl: String?) -> Unit = { _, _, _ -> },
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenGenerator: () -> Unit = {},
    onOpenNewReleases: () -> Unit = {},
    mainShellViewModel: MainShellViewModel = hiltViewModel(),
) {
    val tabs = MainTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updateInfo by mainShellViewModel.updateInfo.collectAsStateWithLifecycle()
    val showUpdateBanner = updateInfo.isUpdateAvailable && !updateInfo.isDismissed
    val backgroundColor = MaterialTheme.colorScheme.background
    // Unconditional remember keeps composition stable; usage gated below.
    val navigationBackdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }
    val navGlass = isLiquidGlassBackdropSupported()

    Box(Modifier.fillMaxSize()) {
        val feedIndex = tabs.indexOf(MainTab.FEED)
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize().liquidGlassSource(if (navGlass) navigationBackdrop else null),
        ) { page ->
            val isCurrent = page == pagerState.currentPage
            PredictiveBackScreen(
                enabled = isCurrent && tabs[page] != MainTab.FEED,
                onBack = { scope.launch { pagerState.animateScrollToPage(feedIndex) } },
            ) {
                when (tabs[page]) {
                    MainTab.FEED -> FeedScreen(
                        onOpenSettings = onOpenSettings,
                        onOpenSearch = onOpenSearch,
                        onOpenDiscover = onOpenDiscover,
                        onOpenPlaylist = onOpenPlaylist,
                        onOpenFeedPlaylist = onOpenFeedPlaylist,
                        onOpenGenerator = onOpenGenerator,
                        onOpenFriends = onOpenFriends,
                        onOpenFriendProfile = onOpenFriendProfile,
                        onOpenNewReleases = onOpenNewReleases,
                    )
                    MainTab.STATS -> HomeScreen(
                        onOpenSettings = onOpenSettings,
                        onOpenSearch = onOpenSearch,
                        onOpenDiscover = onOpenDiscover,
                        onOpenGenres = onOpenGenres,
                        onOpenFriends = onOpenFriends,
                    )
                    MainTab.PLAYLISTS -> PlaylistScreen(onOpenPlaylist = onOpenPlaylist)
                }
            }
        }

        // App update prompt banner (only shown on app open when an update is available and not dismissed)
        AnimatedVisibility(
            visible = showUpdateBanner,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .adaptiveContentWidth(maxWidth = 600.dp)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .zIndex(10f),
        ) {
            UpdatePromptCard(
                version = updateInfo.latestVersion,
                onUpdate = { mainShellViewModel.openUpdate(context) },
                onDismiss = { mainShellViewModel.dismissUpdate(updateInfo.latestVersion) },
            )
        }

        FloatingNavBar(
            backdrop = navigationBackdrop,
            tabs = tabs,
            selectedIndex = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            onOpenGenerator = onOpenGenerator,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun UpdatePromptCard(
    version: String,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 8.dp,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.lastwave.app.R.string.update_available),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(com.lastwave.app.R.string.update_ready_to_install, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onUpdate),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.lastwave.app.R.string.update),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = androidx.compose.ui.res.stringResource(com.lastwave.app.R.string.dismiss_update),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun FloatingNavBar(
    backdrop: LayerBackdrop?,
    tabs: List<MainTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onOpenGenerator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val liquidGlass = LocalLiquidGlass.current
    val isGlass = liquidGlass && isLiquidGlassBackdropSupported() && backdrop != null
    val isDark = LocalIsDarkTheme.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pressProgress = remember { Animatable(0f) }
    val touchPosition = remember { mutableStateOf<Offset?>(null) }
    val glassHoverIndex = remember(liquidGlass) { mutableStateOf<Int?>(null) }
    val glassNavBounds = remember { mutableStateMapOf<Int, Rect>() }
    val dockInteraction = remember { MutableInteractionSource() }
    val fabInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .animateContentSize(animationSpec = navSpring()),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val dockModifier = if (isGlass) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { DockShape },
                    effects = {
                        if (!size.isSpecified || !size.width.isFinite() || !size.height.isFinite() ||
                            size.width <= 0f || size.height <= 0f
                        ) return@drawBackdrop

                        // 1. Color Vibrancy (API 33+) or Saturation boost (API 31+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            vibrancy()
                        } else {
                            colorControls(saturation = 1.15f)
                        }

                        // 2. Optical Blur (subtle 5dp so underlying content is clearly refracted through lens)
                        blur(5.dp.toPx())

                        // 3. Continuous-curvature squircle lens (API 33+) - high refraction for intense liquify!
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && DockShape is CornerBasedShape) {
                            val minCorner = minOf(
                                DockShape.topStart.toPx(size, density),
                                DockShape.topEnd.toPx(size, density),
                                DockShape.bottomStart.toPx(size, density),
                                DockShape.bottomEnd.toPx(size, density),
                            ).coerceAtLeast(0f)
                            val lensH = 20.dp.toPx().coerceIn(0f, if (minCorner > 0f) minCorner else 32.dp.toPx())
                            val lensA = 40.dp.toPx().coerceIn(0f, size.minDimension)
                            if (lensH > 0f && lensA > 0f) {
                                lens(
                                    refractionHeight = lensH,
                                    refractionAmount = lensA,
                                    depthEffect = true,
                                    chromaticAberration = true,
                                )
                            }
                        }
                    },
                    layerBlock = {
                        val progress = pressProgress.value
                        val scale = lerp(1f, 0.96f, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    highlight = {
                        Highlight(
                            alpha = lerp(if (isDark) 0.45f else 0.32f, 0.72f, pressProgress.value),
                            style = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) HighlightStyle.Default else HighlightStyle.Plain,
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = lerp(14f, 4f, pressProgress.value).dp,
                            color = Color.Black.copy(alpha = if (isDark) 0.38f else 0.18f),
                        )
                    },
                    onDrawSurface = {
                        // Translucent glass substrate
                        val baseAlpha = if (isDark) 0.18f else 0.40f
                        drawRect(if (isDark) Color(0xFF0A0A0A).copy(alpha = baseAlpha) else Color.White.copy(alpha = baseAlpha))

                        // Glossy top-to-bottom sheen gradient
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = if (isDark) 0.28f else 0.50f),
                                0.28f to Color.White.copy(alpha = if (isDark) 0.08f else 0.18f),
                                0.60f to Color.Transparent,
                                1f to Color.Black.copy(alpha = if (isDark) 0.12f else 0.04f),
                                startY = 0f,
                                endY = size.height,
                            ),
                        )

                        // Dynamic directional specular shine following touch motion across the squircle
                        val highlightCenter = touchPosition.value ?: Offset(size.width * 0.5f, 0f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (isDark) 0.34f else 0.48f),
                                    Color.Transparent,
                                ),
                                center = highlightCenter,
                                radius = size.width * 0.55f,
                            ),
                            radius = size.width * 0.55f,
                            center = highlightCenter,
                        )
                    },
                )
            } else {
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (liquidGlass) 0.75f else 1f),
                        shape = DockShape,
                    )
                    .clip(DockShape)
            }

            Surface(
                shape = DockShape,
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = if (isGlass) 0.dp else 12.dp,
                modifier = dockModifier,
            ) {
                Row(
                    modifier = Modifier
                        .then(
                            if (liquidGlass) {
                                Modifier.pointerInput(Unit) {
                                    val bridge = 6.dp.toPx()
                                    val springSpec = spring<Float>(0.6f, 400f, 0.001f)
                                    val hitIndex: (Offset) -> Int? = { pos ->
                                        glassNavBounds.entries
                                            .firstOrNull { it.value.inflate(bridge).contains(pos) }
                                            ?.key
                                    }
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        touchPosition.value = down.position
                                        glassHoverIndex.value = hitIndex(down.position)
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        scope.launch { pressProgress.animateTo(1f, springSpec) }
                                        while (true) {
                                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null || !change.pressed) {
                                                touchPosition.value = null
                                                glassHoverIndex.value = null
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                scope.launch { pressProgress.animateTo(0f, springSpec) }
                                                break
                                            }
                                            touchPosition.value = change.position
                                            glassHoverIndex.value = hitIndex(change.position)
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val onClick = remember(index) { { onSelect(index) } }
                        FloatingNavItem(
                            label = androidx.compose.ui.res.stringResource(tab.labelRes),
                            icon = tab.icon(),
                            selected = selectedIndex == index,
                            glassHovered = liquidGlass && glassHoverIndex.value == index,
                            onGlassBounds = { rect ->
                                if (glassNavBounds[index] != rect) glassNavBounds[index] = rect
                            },
                            onClick = onClick,
                            interactionSource = dockInteraction,
                        )
                    }
                }
            }

            // Satellite Companion Generator Button (only visible on Playlists tab)
            AnimatedVisibility(
                visible = selectedIndex == tabs.indexOf(MainTab.PLAYLISTS),
                enter = fadeIn(animationSpec = tween(180)) +
                    scaleIn(initialScale = 0.35f, animationSpec = navSpring()) +
                    expandHorizontally(animationSpec = navSpring(), expandFrom = Alignment.End),
                exit = fadeOut(animationSpec = tween(120)) +
                    scaleOut(targetScale = 0.35f, animationSpec = navSpring()) +
                    shrinkHorizontally(animationSpec = navSpring(), shrinkTowards = Alignment.End),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = SquircleShape(percent = 50),
                        color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = if (isGlass) 0.dp else 10.dp,
                        tonalElevation = if (isGlass) 0.dp else 4.dp,
                        modifier = Modifier
                            .size(56.dp)
                            .then(
                                if (isGlass) {
                                    Modifier.drawBackdrop(
                                        backdrop = backdrop,
                                        shape = { SquircleShape(percent = 50) },
                                        effects = {
                                            if (!size.isSpecified || size.width <= 0f || size.height <= 0f) return@drawBackdrop
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) vibrancy()
                                            blur(5.dp.toPx())
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                lens(
                                                    refractionHeight = 16.dp.toPx(),
                                                    refractionAmount = 32.dp.toPx(),
                                                    depthEffect = true,
                                                    chromaticAberration = true,
                                                )
                                            }
                                        },
                                        highlight = {
                                            Highlight(
                                                alpha = if (isDark) 0.42f else 0.32f,
                                                style = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) HighlightStyle.Default else HighlightStyle.Plain,
                                            )
                                        },
                                        shadow = {
                                            Shadow(
                                                radius = 10.dp,
                                                color = Color.Black.copy(alpha = if (isDark) 0.35f else 0.15f),
                                            )
                                        },
                                        onDrawSurface = {
                                            val baseAlpha = if (isDark) 0.18f else 0.40f
                                            drawRect(if (isDark) Color(0xFF0A0A0A).copy(alpha = baseAlpha) else Color.White.copy(alpha = baseAlpha))
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    0f to Color.White.copy(alpha = if (isDark) 0.28f else 0.50f),
                                                    0.25f to Color.White.copy(alpha = if (isDark) 0.08f else 0.18f),
                                                    0.60f to Color.Transparent,
                                                    1f to Color.Black.copy(alpha = if (isDark) 0.12f else 0.04f),
                                                    startY = 0f,
                                                    endY = size.height,
                                                ),
                                            )
                                        },
                                    )
                                } else Modifier
                            )
                            .clickable(interactionSource = fabInteraction, indication = null, onClick = onOpenGenerator),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = androidx.compose.ui.res.stringResource(com.lastwave.app.R.string.nav_create_playlist),
                                tint = if (isGlass && isDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    glassHovered: Boolean,
    onGlassBounds: (Rect) -> Unit,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource? = null,
) {
    val isDark = LocalIsDarkTheme.current
    val liquidGlass = LocalLiquidGlass.current

    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected && liquidGlass -> if (isDark) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        },
        animationSpec = navSpring(),
        label = "navItemBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            selected && liquidGlass -> if (isDark) Color.White else MaterialTheme.colorScheme.primary
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> if (isDark) Color.White.copy(alpha = 0.70f) else MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = navSpring(),
        label = "navItemContent",
    )
    val glassIconScale by animateFloatAsState(
        targetValue = if (glassHovered) 1.25f else 1f,
        animationSpec = navSpring(),
        label = "navGlassIconScale",
    )

    Surface(
        onClick = onClick,
        shape = PillShape,
        color = backgroundColor,
        interactionSource = interactionSource,
        modifier = Modifier
            .height(48.dp)
            .animateContentSize(animationSpec = navSpring())
            .onGloballyPositioned { onGlassBounds(it.boundsInParent()) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = if (selected) 18.dp else 12.dp)
                .height(48.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp).scale(glassIconScale),
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(animationSpec = navSpring()) + expandHorizontally(
                    animationSpec = navSpring(),
                    expandFrom = Alignment.Start,
                ),
                exit = fadeOut(animationSpec = tween(90)) + shrinkHorizontally(
                    animationSpec = navSpring(),
                    shrinkTowards = Alignment.Start,
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.FEED -> Icons.Filled.Home
    MainTab.STATS -> Icons.Filled.Leaderboard
    MainTab.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
}
