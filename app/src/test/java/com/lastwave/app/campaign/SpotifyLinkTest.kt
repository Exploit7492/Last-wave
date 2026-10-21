package com.lastwave.app.campaign

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpotifyLinkTest {
    @Test
    fun `parses a track link and its intl and embed shapes`() {
        val expected = SpotifyLink.Target(SpotifyLink.Kind.TRACK, "4cOdK2wGLETKBW3PvgPWqT")
        assertThat(SpotifyLink.parse("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc")).isEqualTo(expected)
        assertThat(SpotifyLink.parse("https://open.spotify.com/intl-de/track/4cOdK2wGLETKBW3PvgPWqT")).isEqualTo(expected)
        assertThat(SpotifyLink.parse("https://open.spotify.com/embed/track/4cOdK2wGLETKBW3PvgPWqT")).isEqualTo(expected)
    }

    @Test
    fun `parses album, artist and spotify URI shapes`() {
        assertThat(SpotifyLink.parse("https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX")?.kind)
            .isEqualTo(SpotifyLink.Kind.ALBUM)
        assertThat(SpotifyLink.parse("https://open.spotify.com/artist/06HL4z0CvFAxyc27GXpf02")?.kind)
            .isEqualTo(SpotifyLink.Kind.ARTIST)
        assertThat(SpotifyLink.parse("spotify:track:4cOdK2wGLETKBW3PvgPWqT"))
            .isEqualTo(SpotifyLink.Target(SpotifyLink.Kind.TRACK, "4cOdK2wGLETKBW3PvgPWqT"))
    }

    @Test
    fun `rejects a non spotify link`() {
        assertThat(SpotifyLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isNull()
        assertThat(SpotifyLink.parse("https://open.spotify.com/track/too-short")).isNull()
    }
}
