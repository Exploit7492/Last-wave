package com.lastwave.app.campaign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** How long one campaign card holds the slot before the next slides in. */
private const val CARD_DURATION_MS = 30_000L

/**
 * The home banner's single-card slideshow state.
 *
 * Three rules, the same ones the reference implementation in Velune's
 * `CampaignCarouselState` enforces:
 *
 * 1. One card at a time — never a `LazyRow` of every live campaign.
 * 2. A 30-second auto-advance, cycling a shuffled order.
 * 3. A reshuffle on app-background-then-resume, and only then. Compose fires
 *    `ON_RESUME` when a screen first appears, which is not "returning from the
 *    background"; [CampaignCarouselStateImpl.wasStopped] makes that distinction.
 *
 * [current] exposes one card, never the underlying list or its size, so a
 * consumer cannot leak the live-campaign count even by accident.
 */
interface CampaignCarouselState {
    /** The campaign to render right now, or `null` when nothing is live. */
    val current: CampaignCard?
}

private class CampaignCarouselStateImpl(
    initialCampaigns: List<CampaignCard>,
) : CampaignCarouselState {
    var order: List<CampaignCard> by mutableStateOf(initialCampaigns.shuffled())
    var index: Int by mutableIntStateOf(0)
    var wasStopped: Boolean = false

    override val current: CampaignCard?
        get() = order.getOrNull(index)

    fun advance() {
        if (order.isEmpty()) return
        index = (index + 1) % order.size
    }

    fun onStop() {
        wasStopped = true
    }

    fun onResume() {
        if (!wasStopped) return
        order = order.shuffled()
        index = 0
        wasStopped = false
    }

    /**
     * A data refresh keeps the cards already on screen in place and appends
     * the new ones, so a background poll never moves the user to a different
     * card mid-read. `index` stays in bounds when the list shrinks.
     */
    fun updateCampaigns(campaigns: List<CampaignCard>) {
        val byId = campaigns.associateBy { it.id }
        order = order.mapNotNull { byId[it.id] } + campaigns.filter { c -> order.none { it.id == c.id } }
        if (order.isNotEmpty()) index = index.coerceIn(0, order.size - 1)
    }
}

/**
 * Owns the 30-second timer and the lifecycle observer, so a caller only reads
 * [CampaignCarouselState.current] and manages neither.
 */
@Composable
fun rememberCampaignCarouselState(campaigns: List<CampaignCard>): CampaignCarouselState {
    val state = remember { CampaignCarouselStateImpl(campaigns) }
    val latestCampaigns by rememberUpdatedState(campaigns)

    LaunchedEffect(campaigns) {
        state.updateCampaigns(latestCampaigns)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(CARD_DURATION_MS)
            state.advance()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> state.onStop()
                    Lifecycle.Event.ON_RESUME -> state.onResume()
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return state
}
