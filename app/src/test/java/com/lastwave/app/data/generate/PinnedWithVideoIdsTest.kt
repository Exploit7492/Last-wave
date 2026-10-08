package com.lastwave.app.data.generate

import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedWithVideoIdsTest {
    private val api = mockk<InnerTubeMusicApi>()

    @Test
    fun keepsPinnedTracksWithoutNetwork() = runBlocking {
        val pinned = GeneratedTrack(
            name = "Song",
            artist = "Artist",
            url = "https://music.youtube.com/watch?v=abcdefghijk",
        )
        assertEquals(listOf(pinned), listOf(pinned).pinnedWithVideoIds(api))
        coVerify(exactly = 0) { api.findBestMatchOrNull(any(), any(), any()) }
    }

    @Test
    fun pinsResolvableAndDropsMisses() = runBlocking {
        coEvery { api.findBestMatchOrNull("Song", "Artist", any()) } returns
            YouTubeMusicTrack("12345678901", "Song", "Artist")
        coEvery { api.findBestMatchOrNull("Missing", "Artist", any()) } returns null
        val rows = listOf(
            GeneratedTrack(name = "Song", artist = "Artist"),
            GeneratedTrack(name = "Missing", artist = "Artist"),
        )
        val pinned = rows.pinnedWithVideoIds(api)
        assertEquals(1, pinned.size)
        assertEquals("https://music.youtube.com/watch?v=12345678901", pinned.single().url)
        assertEquals("Song", pinned.single().name)
    }

    @Test
    fun blankRowsDropWithoutNetwork() = runBlocking {
        assertEquals(emptyList<GeneratedTrack>(), listOf(GeneratedTrack(name = "", artist = "")).pinnedWithVideoIds(api))
        coVerify(exactly = 0) { api.findBestMatchOrNull(any(), any(), any()) }
    }
}
