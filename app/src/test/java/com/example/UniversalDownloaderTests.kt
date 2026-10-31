package com.example

import com.example.data.model.ResolutionResult
import com.example.data.model.SourceProtocol
import com.example.data.resolver.Html5MediaExtractor
import com.example.data.resolver.MediaSourceResolver
import com.example.data.resolver.PlayerJsExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalDownloaderTests {

    private val resolver = MediaSourceResolver()

    @Test
    fun test1_directMp4_detectionAndResolution() = runBlocking {
        val url = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"
        assertTrue(resolver.isDirectStreamUrl(url))

        val result = resolver.resolve(url)
        assertTrue(result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals(1, success.candidates.size)
        val candidate = success.candidates.first()
        assertEquals(SourceProtocol.DIRECT_HTTP, candidate.protocol)
        assertEquals(url, candidate.url)
        assertTrue(success.metadata.title.contains("Big Buck Bunny", ignoreCase = true))
    }

    @Test
    fun test2_w3schoolsMp4_detectionAndResolution() = runBlocking {
        val url = "https://www.w3schools.com/html/mov_bbb.mp4"
        assertTrue(resolver.isDirectStreamUrl(url))

        val result = resolver.resolve(url)
        assertTrue(result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals(1, success.candidates.size)
        val candidate = success.candidates.first()
        assertEquals(SourceProtocol.DIRECT_HTTP, candidate.protocol)
        assertEquals(url, candidate.url)
    }

    @Test
    fun test3_muxHls_detectionAndResolution() = runBlocking {
        val url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        assertTrue(resolver.isDirectStreamUrl(url))

        val result = resolver.resolve(url)
        assertTrue(result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        val candidate = success.candidates.first()
        assertEquals(SourceProtocol.HLS, candidate.protocol)
        assertEquals(url, candidate.url)
        assertTrue(candidate.isAdaptive)
    }

    @Test
    fun test4_appleHls_detectionAndResolution() = runBlocking {
        val url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"
        assertTrue(resolver.isDirectStreamUrl(url))

        val result = resolver.resolve(url)
        assertTrue(result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        val candidate = success.candidates.first()
        assertEquals(SourceProtocol.HLS, candidate.protocol)
        assertEquals(url, candidate.url)
    }

    @Test
    fun test5_akamaiDash_detectionAndResolution() = runBlocking {
        val url = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"
        assertTrue(resolver.isDirectStreamUrl(url))

        val result = resolver.resolve(url)
        assertTrue(result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        val candidate = success.candidates.first()
        assertEquals(SourceProtocol.DASH, candidate.protocol)
        assertEquals(url, candidate.url)
        assertTrue(candidate.isAdaptive)
    }

    @Test
    fun test6_castbuddyHtml5_embeddedSamplePageExtraction() {
        val html = """
            <html>
            <head><title>Castbuddy Test page</title></head>
            <body>
                <video width="320" height="240" title="Big Buck Bunny" controls>
                    <source src="https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4" type="video/mp4">
                </video>
                <video width="320" height="240" title="Elephant Dream" controls>
                    <source src="https://www.w3schools.com/html/mov_bbb.mp4" type="video/mp4">
                </video>
                <video width="320" height="240" title="Sintel" controls>
                    <source src="https://test-videos.co.uk/vids/sintel/mp4/h264/720/Sintel_720_10s_2MB.mp4" type="video/mp4">
                </video>
            </body>
            </html>
        """.trimIndent()

        val candidates = Html5MediaExtractor.extractFromHtml(html, "https://player.castbuddy.app/help/sample")
        assertEquals(3, candidates.size)

        val bunny = candidates.find { it.url.contains("Big_Buck_Bunny") }
        assertNotNull(bunny)
        assertEquals("Big Buck Bunny", bunny!!.label)
        assertEquals(SourceProtocol.DIRECT_HTTP, bunny.protocol)

        val elephant = candidates.find { it.url.contains("mov_bbb.mp4") }
        assertNotNull(elephant)
        assertEquals("Elephant Dream", elephant!!.label)

        val sintel = candidates.find { it.url.contains("Sintel") }
        assertNotNull(sintel)
        assertEquals("Sintel", sintel!!.label)
    }

    @Test
    fun test7_playerJs_liveDemoExtraction() {
        val html = """
            <div id="player"></div>
            <script src="https://cdn.jsdelivr.net/npm/cdnbye@latest"></script>
            <script src="./playerjs.js" type="text/javascript"></script>
            <script>
                var player = new Playerjs({
                    id:"player",
                    file:"https://test-streams.mux.dev/x36xhzz/url_2/193039199_mp4_h264_aac_ld_7.m3u8",
                    hlsconfig: {
                        p2pConfig: {
                            live: true
                        }
                    }
                });
            </script>
        """.trimIndent()

        val candidates = PlayerJsExtractor.extractFromHtmlOrScript(
            html,
            "https://raw.githubusercontent.com/cdnbye/hlsjs-p2p-engine/master/demo/playerjs/index.html"
        )

        assertEquals(1, candidates.size)
        val first = candidates.first()
        assertEquals("https://test-streams.mux.dev/x36xhzz/url_2/193039199_mp4_h264_aac_ld_7.m3u8", first.url)
        assertEquals(SourceProtocol.HLS, first.protocol)
    }

    @Test
    fun test8_playerJs_jsonPlaylistExtraction() {
        val content = """
            <script>
                var player = new Playerjs({
                    id: "player",
                    file: '[{"title":"1080p Full HD","file":"https://media.org/1080.mp4"},{"title":"720p HD","file":"https://media.org/720.mp4"}]'
                });
            </script>
        """.trimIndent()

        val candidates = PlayerJsExtractor.extractFromHtmlOrScript(content, "https://media.org/watch")
        assertEquals(2, candidates.size)

        val f1080 = candidates.find { it.label.contains("1080p") }
        assertNotNull(f1080)
        assertEquals("https://media.org/1080.mp4", f1080!!.url)

        val f720 = candidates.find { it.label.contains("720p") }
        assertNotNull(f720)
        assertEquals("https://media.org/720.mp4", f720!!.url)
    }
}
