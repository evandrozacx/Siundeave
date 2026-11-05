package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun mediaItemModel_supportsSoundCloudSource() {
        val item = com.example.model.MediaItemModel(
            id = "sc_123456",
            title = "Lembranças",
            artist = "Hungria Hip Hop",
            streamUrl = "https://soundcloud.com/tracks/123456",
            source = "soundcloud",
            durationMs = 240000L
        )
        assertEquals("soundcloud", item.source)
        assertEquals(240000L, item.durationMs)
        assertTrue(item.streamUrl.contains("soundcloud.com"))
    }
}
