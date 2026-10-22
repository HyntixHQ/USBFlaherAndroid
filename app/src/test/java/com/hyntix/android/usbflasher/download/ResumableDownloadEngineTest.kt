package com.hyntix.android.usbflasher.download

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class ResumableDownloadEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val client = OkHttpClient.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadsCompleteBodyAndReportsProgress() {
        val content = "hello world"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("ETag", "\"v1\"")
                .setBody(content)
        )
        val file = temporaryFolder.newFile("image.iso")
        val progress = mutableListOf<Long>()

        FileDownloadSink(file).use { sink ->
            val result = engine().run(
                request = EngineRequest(
                    url = server.url("/image.iso").toString(),
                    expectedTotalBytes = content.length.toLong()
                ),
                sink = sink,
                control = DownloadControl(),
                onProgress = { bytes, _ -> progress.add(bytes) }
            )

            assertTrue(result is EngineResult.Completed)
            result as EngineResult.Completed
            assertEquals(content.length.toLong(), result.totalBytes)
            assertEquals("\"v1\"", result.etag)
            assertTrue(result.rangeSupported)
        }

        assertEquals(content, file.readText())
        assertEquals(content.length.toLong(), progress.last())
    }

    @Test
    fun resumesWithRangeAndIfRange() {
        val content = "hello world"
        val prefix = "hello"
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-10/11")
                .setHeader("ETag", "\"v1\"")
                .setBody(" world")
        )
        val file = temporaryFolder.newFile("resume.iso").apply { writeText(prefix) }

        FileDownloadSink(file).use { sink ->
            val result = engine().run(
                request = EngineRequest(
                    url = server.url("/resume.iso").toString(),
                    offset = prefix.length.toLong(),
                    expectedTotalBytes = content.length.toLong(),
                    etag = "\"v1\""
                ),
                sink = sink,
                control = DownloadControl(),
                onProgress = { _, _ -> }
            )

            assertTrue(result is EngineResult.Completed)
        }

        assertEquals(content, file.readText())
        val request = server.takeRequest()
        assertEquals("bytes=5-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        val content = "hello world"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(content)
        )
        val file = temporaryFolder.newFile("ignored-range.iso").apply { writeText("hello") }

        FileDownloadSink(file).use { sink ->
            val result = engine().run(
                request = EngineRequest(
                    url = server.url("/ignored.iso").toString(),
                    offset = 5,
                    expectedTotalBytes = content.length.toLong()
                ),
                sink = sink,
                control = DownloadControl(),
                onProgress = { _, _ -> }
            )

            assertTrue(result is EngineResult.Completed)
            assertEquals(false, (result as EngineResult.Completed).rangeSupported)
        }

        assertEquals(content, file.readText())
    }

    @Test
    fun treats416WithCompleteFileAsComplete() {
        val content = "hello world"
        server.enqueue(MockResponse().setResponseCode(416))
        val file = temporaryFolder.newFile("complete.iso").apply { writeText(content) }

        FileDownloadSink(file).use { sink ->
            val result = engine().run(
                request = EngineRequest(
                    url = server.url("/complete.iso").toString(),
                    offset = content.length.toLong(),
                    expectedTotalBytes = content.length.toLong()
                ),
                sink = sink,
                control = DownloadControl(),
                onProgress = { _, _ -> }
            )

            assertTrue(result is EngineResult.Completed)
            assertEquals(content.length.toLong(), (result as EngineResult.Completed).totalBytes)
        }

        assertEquals(content, file.readText())
    }

    @Test
    fun rejectsHtmlResponse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html>captive portal</html>")
        )
        val file = temporaryFolder.newFile("portal.iso")

        FileDownloadSink(file).use { sink ->
            val result = engine().run(
                request = EngineRequest(
                    url = server.url("/portal.iso").toString(),
                    expectedTotalBytes = 100
                ),
                sink = sink,
                control = DownloadControl(),
                onProgress = { _, _ -> }
            )

            assertTrue(result is EngineResult.Failed)
            assertEquals(
                DownloadErrorCode.HTML_RESPONSE,
                (result as EngineResult.Failed).error.code
            )
        }
    }

    @Test
    fun retriesServerErrors() {
        val content = "hello world"
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        val file = temporaryFolder.newFile("retry.iso")

        FileDownloadSink(file).use { sink ->
            val result = engine().run(
                request = EngineRequest(
                    url = server.url("/retry.iso").toString(),
                    expectedTotalBytes = content.length.toLong()
                ),
                sink = sink,
                control = DownloadControl(),
                onProgress = { _, _ -> }
            )

            assertTrue(result is EngineResult.Completed)
        }

        assertEquals(content, file.readText())
    }

    private fun engine(): ResumableDownloadEngine {
        return ResumableDownloadEngine(
            client = client,
            finalUrlValidator = { true },
            sleeper = { }
        )
    }
}

private class FileDownloadSink(file: File) : DownloadSink {
    private val access = RandomAccessFile(file, "rw")

    override fun prepare(offset: Long) {
        if (offset < 0 || offset > access.length()) {
            throw java.io.IOException("Invalid offset")
        }
        access.setLength(offset)
        access.seek(offset)
    }

    override fun size(): Long = access.length()

    override fun write(buffer: ByteArray, length: Int) {
        access.write(buffer, 0, length)
    }

    override fun flush() {
        access.fd.sync()
    }

    override fun close() {
        access.close()
    }
}
