package com.example

import com.example.data.model.SourceProtocol
import com.example.data.resolver.Html5MediaExtractor
import com.example.data.resolver.PlayerJsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourceResolverTest {

    @Test
    fun html5Extractor_extractsDirectVideoTag() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test Video</title></head>
            <body>
                <video src="https://media.example.com/movies/sample.mp4" width="1920" height="1080"></video>
            </body>
            </html>
        """.trimIndent()

        val candidates = Html5MediaExtractor.extractFromHtml(html, "https://example.com/watch")
        assertEquals(1, candidates.size)
        val first = candidates.first()
        assertEquals("https://media.example.com/movies/sample.mp4", first.url)
        assertEquals(SourceProtocol.DIRECT_HTTP, first.protocol)
        assertEquals("1920x1080", first.resolution)
        assertEquals("https://example.com/watch", first.requiredHeaders["Referer"])
    }

    @Test
    fun html5Extractor_resolvesRelativeSourceUrls() {
        val html = """
            <html>
            <body>
                <video>
                    <source src="../media/clip.mp4" type="video/mp4" res="720p">
                    <source src="/streams/master.m3u8" type="application/x-mpegURL">
                </video>
            </body>
            </html>
        """.trimIndent()

        val candidates = Html5MediaExtractor.extractFromHtml(html, "https://example.com/videos/sub/")
        assertEquals(2, candidates.size)

        val mp4 = candidates.first { it.url.endsWith(".mp4") }
        assertEquals("https://example.com/videos/media/clip.mp4", mp4.url)
        assertEquals(SourceProtocol.DIRECT_HTTP, mp4.protocol)

        val hls = candidates.first { it.url.endsWith(".m3u8") }
        assertEquals("https://example.com/streams/master.m3u8", hls.url)
        assertEquals(SourceProtocol.HLS, hls.protocol)
    }

    @Test
    fun html5Extractor_extractsLazyDataAttributes() {
        val html = """
            <div class="video-container" data-mp4="https://cdn.example.com/feature.mp4"></div>
        """.trimIndent()

        val candidates = Html5MediaExtractor.extractFromHtml(html, "https://example.com")
        assertEquals(1, candidates.size)
        assertEquals("https://cdn.example.com/feature.mp4", candidates.first().url)
    }

    @Test
    fun playerJsExtractor_extractsSingleHlsStream() {
        val script = """
            <script>
                var player = new Playerjs({id:"player", file:"https://stream.example.com/live/playlist.m3u8"});
            </script>
        """.trimIndent()

        val candidates = PlayerJsExtractor.extractFromHtmlOrScript(script, "https://example.com")
        assertEquals(1, candidates.size)
        val first = candidates.first()
        assertEquals("https://stream.example.com/live/playlist.m3u8", first.url)
        assertEquals(SourceProtocol.HLS, first.protocol)
        assertEquals("https://example.com", first.requiredHeaders["Referer"])
    }

    @Test
    fun playerJsExtractor_extractsMultiQualityPlaylists() {
        val script = """
            <script>
                var player = new Playerjs({
                    id: "player",
                    file: "[1080p]https://cdn.example.com/1080.mp4,[720p]https://cdn.example.com/720.mp4,[480p]/media/480.mp4"
                });
            </script>
        """.trimIndent()

        val candidates = PlayerJsExtractor.extractFromHtmlOrScript(script, "https://example.com/channel/")
        assertEquals(3, candidates.size)

        val candidate1080 = candidates.find { it.url.contains("1080.mp4") }
        assertNotNull(candidate1080)
        assertTrue(candidate1080!!.label.contains("1080p"))

        val candidate480 = candidates.find { it.url.contains("480.mp4") }
        assertNotNull(candidate480)
        assertEquals("https://example.com/media/480.mp4", candidate480!!.url)
    }

    @Test
    fun playerJsExtractor_extractsDataFileAttributes() {
        val html = """
            <div id="player" data-file="https://cdn.example.com/vod.mp4"></div>
        """.trimIndent()

        val candidates = PlayerJsExtractor.extractFromHtmlOrScript(html, "https://example.com")
        assertEquals(1, candidates.size)
        assertEquals("https://cdn.example.com/vod.mp4", candidates.first().url)
    }
}
