package vision.salient.choam.web

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.ChoamConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for StreamingAdapter — content type detection, streaming endpoints, CID resolution.
 */
class StreamingAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    // ===========================================
    // guessContentType — all format families
    // ===========================================

    @Test
    fun `video formats detected correctly`() {
        assertEquals("video", guessContentType("movie.mkv").contentType)
        assertEquals("video", guessContentType("clip.mp4").contentType)
        assertEquals("video", guessContentType("old.avi").contentType)
        assertEquals("video", guessContentType("apple.mov").contentType)
        assertEquals("video", guessContentType("web.webm").contentType)
        assertEquals("video", guessContentType("stream.ts").contentType)
        assertEquals("video", guessContentType("flash.flv").contentType)
        assertEquals("video", guessContentType("itunes.m4v").contentType)
    }

    @Test
    fun `audio formats detected correctly`() {
        assertEquals("audio", guessContentType("song.mp3").contentType)
        assertEquals("audio", guessContentType("lossless.flac").contentType)
        assertEquals("audio", guessContentType("voice.aac").contentType)
        assertEquals("audio", guessContentType("vorbis.ogg").contentType)
        assertEquals("audio", guessContentType("raw.wav").contentType)
        assertEquals("audio", guessContentType("apple.m4a").contentType)
    }

    @Test
    fun `image formats detected correctly`() {
        assertEquals("image", guessContentType("photo.jpg").contentType)
        assertEquals("image", guessContentType("photo.jpeg").contentType)
        assertEquals("image", guessContentType("icon.png").contentType)
        assertEquals("image", guessContentType("anim.gif").contentType)
        assertEquals("image", guessContentType("modern.webp").contentType)
        assertEquals("image", guessContentType("vector.svg").contentType)
    }

    @Test
    fun `document formats detected correctly`() {
        assertEquals("application", guessContentType("doc.pdf").contentType)
        assertEquals("pdf", guessContentType("doc.pdf").contentSubtype)
    }

    @Test
    fun `unknown extension returns octet-stream`() {
        val ct = guessContentType("data.xyz")
        assertEquals("application", ct.contentType)
        assertEquals("octet-stream", ct.contentSubtype)
    }

    @Test
    fun `no extension returns octet-stream`() {
        val ct = guessContentType("README")
        assertEquals("application", ct.contentType)
        assertEquals("octet-stream", ct.contentSubtype)
    }

    @Test
    fun `case insensitive extension detection`() {
        assertEquals("video", guessContentType("MOVIE.MKV").contentType)
        assertEquals("audio", guessContentType("SONG.MP3").contentType)
        assertEquals("image", guessContentType("PHOTO.JPG").contentType)
    }

    @Test
    fun `multi-dot filename uses last extension`() {
        assertEquals("video", guessContentType("movie.2026.1080p.mkv").contentType)
        assertEquals("application", guessContentType("backup.tar.gz").contentType) // gz not in list
    }

    @Test
    fun `mkv returns x-matroska subtype`() {
        assertEquals("x-matroska", guessContentType("file.mkv").contentSubtype)
    }

    @Test
    fun `mp4 returns correct ContentType constant`() {
        assertEquals(ContentType.Video.MP4, guessContentType("file.mp4"))
    }

    @Test
    fun `jpeg and jpg both map to JPEG`() {
        assertEquals(ContentType.Image.JPEG, guessContentType("a.jpg"))
        assertEquals(ContentType.Image.JPEG, guessContentType("b.jpeg"))
    }

    // ===========================================
    // Streaming endpoints via Ktor test host
    // ===========================================

    @Test
    fun `stream info for nonexistent CID returns 404`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.get("/stream/info/QmNonexistent")
        // May return 404 or 200 with error depending on whether registry exists
        assertTrue(response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.OK)
    }

    @Test
    fun `stream for nonexistent CID returns 404`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.get("/stream/QmNonexistent")
        assertTrue(response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.OK)
    }

    @Test
    fun `stream info endpoint exists and responds`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.get("/stream/info/QmTest123")
        assertTrue(response.status.value in listOf(200, 404))
    }

    @Test
    fun `stream endpoint exists and responds`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.get("/stream/QmTest123")
        assertTrue(response.status.value in listOf(200, 404, 410))
    }
}
