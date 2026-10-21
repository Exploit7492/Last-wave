package com.lastwave.app.campaign

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CampaignUrlResolverTest {
    @Test
    fun `reads the id from a plain watch link`() {
        assertThat(CampaignUrlResolver.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            .isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun `reads the id when v is not the first query parameter`() {
        assertThat(CampaignUrlResolver.extractVideoId("https://music.youtube.com/watch?list=RDAMVM&v=dQw4w9WgXcQ"))
            .isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun `reads the id from shorts, live and youtu be shapes`() {
        assertThat(CampaignUrlResolver.extractVideoId("https://youtube.com/shorts/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(CampaignUrlResolver.extractVideoId("https://youtube.com/live/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(CampaignUrlResolver.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc")).isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun `accepts a bare id and rejects anything else`() {
        assertThat(CampaignUrlResolver.extractVideoId("  dQw4w9WgXcQ  ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(CampaignUrlResolver.extractVideoId("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")).isNull()
        assertThat(CampaignUrlResolver.extractVideoId("")).isNull()
        assertThat(CampaignUrlResolver.extractVideoId("not-a-link")).isNull()
    }
}
