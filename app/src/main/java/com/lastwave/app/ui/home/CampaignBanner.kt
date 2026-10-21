package com.lastwave.app.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lastwave.app.campaign.CampaignCard
import com.lastwave.app.campaign.Campaigns
import kotlinx.coroutines.delay

/** How often a visible card re-reads its gathered stream total. */
private const val CAMPAIGN_TOTAL_REFRESH_MS = 15_000L

/** The gold the promoted label uses. */
private val PROMOTED_GOLD = Color(0xFFD4AF37)

/** The LIVE pill's red. */
private val LIVE_RED = Color(0xFFE53935)

/**
 * The home feed's promoted-campaign banner.
 *
 * Ported from Velune's `CampaignCardSection` (the reference implementation).
 * One live campaign at a time, rendered as a compact glass-panel card: the
 * campaign's own thumbnail, blurred and darkened, fills the card behind the
 * title, artist and the campaign's gathered stream total.
 *
 * Draws nothing at all when there is no live campaign, or when no campaign
 * backend is configured. That is deliberate rather than an oversight: an empty
 * promo slot is not something worth occupying space to announce, and there is
 * no skeleton state to flash in its place.
 *
 * The total is re-read on a slow timer while the card is on screen, because the
 * card's own copy is a snapshot taken when the feed loaded. The read is keyed on
 * the campaign id, so it restarts for the next campaign and stops for one that
 * has scrolled away.
 */
@Composable
fun CampaignBanner(
    campaign: CampaignCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var liveTotal by remember(campaign.id) { mutableLongStateOf(campaign.totalStreams) }
    LaunchedEffect(campaign.id) {
        while (true) {
            Campaigns.campaignTotalStreams(campaign.id)?.let { if (it > 0L) liveTotal = it }
            delay(CAMPAIGN_TOTAL_REFRESH_MS)
        }
    }

    val cardShape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(cardShape)
            .clickable(onClick = onClick),
    ) {
        // Ambient art: the campaign's own thumbnail, blurred to fill the card.
        if (campaign.thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = campaign.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(26.dp),
            )
        }
        // Scrim and glass edge, so light type stays legible over any art.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.78f),
                            Color.Black.copy(alpha = 0.45f),
                            Color.Black.copy(alpha = 0.62f),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.38f),
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                    ),
                    shape = cardShape,
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (campaign.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = campaign.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.28f),
                            shape = RoundedCornerShape(12.dp),
                        ),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Promoted \uD83D\uDD25",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    fontWeight = FontWeight.Bold,
                    color = PROMOTED_GOLD,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = campaign.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = campaign.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Gated on the gathered total, so a campaign that has genuinely
                // gathered nothing shows nothing rather than a "0" that reads as
                // a broken figure.
                if (liveTotal > 0L) {
                    Text(
                        text = formatCampaignTotal(liveTotal),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                }
            }
        }
        if (campaign.isLive) {
            LiveBadge(
                color = LIVE_RED,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
            )
        }
    }
}

/** A small pulsing "LIVE" pill — a UI animation, not a data claim. */
@Composable
private fun LiveBadge(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "liveDotPulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDotAlpha",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.5f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 0.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = "LIVE",
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** "341K" / "1.2M" — a compact count for a card with room for four characters. */
private fun formatCampaignTotal(total: Long): String = when {
    total >= 1_000_000_000L -> "%.1fB".format(total / 1_000_000_000.0)
    total >= 1_000_000L -> "%.1fM".format(total / 1_000_000.0)
    total >= 1_000L -> "%.0fK".format(total / 1_000.0)
    else -> total.toString()
}

/**
 * The playable row a banner tap starts. The campaign's own id rides along as
 * [PlayableTrack.campaignId] so the play reporter can attribute a qualified
 * play, and [PlayableTrack.isCampaign] marks the row as promoted.
 */
fun CampaignCard.toPlayableTrack(): com.lastwave.app.playback.PlayableTrack? {
    if (songId.isBlank()) return null
    return com.lastwave.app.playback.PlayableTrack(
        title = title,
        artist = artist,
        artworkUrl = thumbnailUrl.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$songId/hqdefault.jpg",
        videoId = songId,
        isCampaign = true,
        campaignId = id,
    )
}
