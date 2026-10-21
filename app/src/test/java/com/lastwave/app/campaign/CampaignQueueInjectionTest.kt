package com.lastwave.app.campaign

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CampaignQueueInjectionTest {
    private fun track(id: String) =
        PlayableTrack(title = "Song $id", artist = "Artist", videoId = id, durationMs = 200_000L)

    private fun campaign(id: String) = track(id).copy(isCampaign = true, campaignId = "campaign-$id")

    @Test
    fun `a campaign follows every run of four base items`() =
        runTest {
            val base = (0 until 10).map { track("s$it") }
            var counter = 0
            val result = CampaignQueueInjection.inject(base) { campaign("c${counter++}") }

            // 10 base items -> slots after indices 3 and 7 -> 12 items, campaigns at 4 and 9.
            assertThat(result.items.map { it.videoId })
                .containsExactly("s0", "s1", "s2", "s3", "c0", "s4", "s5", "s6", "s7", "c1", "s8", "s9")
                .inOrder()
            assertThat(result.items.filter { it.isCampaign }).hasSize(2)
        }

    @Test
    fun `a null campaign skips the slot and the pattern resumes at the next boundary`() =
        runTest {
            val base = (0 until 10).map { track("s$it") }
            var slot = 0
            val result =
                CampaignQueueInjection.inject(base) {
                    // Only the second slot yields a campaign.
                    if (slot++ == 1) campaign("c0") else null
                }

            assertThat(result.items.map { it.videoId })
                .containsExactly("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "c0", "s8", "s9")
                .inOrder()
        }

    @Test
    fun `the tracked target index follows its item past an inserted campaign`() =
        runTest {
            val base = (0 until 8).map { track("s$it") }
            // Target original index 5 sits after the first slot at 4, so it lands one later.
            val result = CampaignQueueInjection.inject(base, targetOriginalIndex = 5) { campaign("c0") }

            assertThat(result.adjustedTargetIndex).isEqualTo(6)
            assertThat(result.items[result.adjustedTargetIndex].videoId).isEqualTo("s5")
        }

    @Test
    fun `an offset carries the run-of-four across chunk boundaries`() =
        runTest {
            // A chunk starting at offset 4 begins mid-pattern: its own index 3 is global 7, a slot.
            val chunk = (4 until 8).map { track("s$it") }
            val result = CampaignQueueInjection.inject(chunk, offset = 4) { campaign("c0") }

            assertThat(result.items.map { it.videoId })
                .containsExactly("s4", "s5", "s6", "s7", "c0")
                .inOrder()
        }

    @Test
    fun `a campaign is tagged so the play reporter can attribute it`() =
        runTest {
            val result =
                CampaignQueueInjection.inject(listOf(track("s0"), track("s1"), track("s2"), track("s3"))) { campaign("c0") }
            val injected = result.items.last()

            assertThat(injected.isCampaign).isTrue()
            assertThat(injected.campaignId).isEqualTo("campaign-c0")
        }
}
