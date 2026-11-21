package com.wangzan101.mika.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenAiSpeechModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun caps() = TtsCapabilities(voices = setOf("alloy", "nova"), maxChars = 100, formats = setOf("wav", "mp3", "pcm"))
    private fun model(http: OkHttpClient = OkHttpClient()) =
        OpenAiSpeechModel(server.url("/").toString().trimEnd('/'), "sk", "gpt-4o-mini-tts", caps(), http)

    @Test fun sendsSpeechRequestAndReturnsAudio() = runTest {
        val wav = wrapPcm16Wav(byteArrayOf(0, 0, 0, 0), 24_000)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)))
        val r = model().synthesize(TtsRequest("hello", "alloy", 1.1f, "wav"))
        assertEquals("audio/wav", r.mime)
        assertTrue(r.bytes.contentEquals(wav))
        val request = server.takeRequest()
        assertEquals("/audio/speech", request.path)
        assertEquals("Bearer sk", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-4o-mini-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals("hello", body["input"]!!.jsonPrimitive.content)
        assertEquals("alloy", body["voice"]!!.jsonPrimitive.content)
        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("1.1", body["speed"]!!.jsonPrimitive.content)
    }

    @Test fun wrapsRawPcm() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(byteArrayOf(1, 2, 3, 4))))
        val r = model().synthesize(TtsRequest("hi", "nova", format = "pcm"))
        assertEquals("audio/wav", r.mime)
        assertEquals("RIFF", String(r.bytes, 0, 4, Charsets.ISO_8859_1))
        assertEquals(24_000, r.sampleRate)
        assertEquals(4, r.bytes.size - 44)
    }

    @Test fun streamsBoundedPcmChunksAcrossFragmentedStereoFrames() = runTest {
        val cancelled = AtomicBoolean(false)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.set(true) }
        }).build()
        val pcm = ByteArray(20_000) { it.toByte() }
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "audio/pcm")
            .setHeader("X-Audio-Sample-Rate", "44100")
            .setHeader("X-Audio-Channels", "2")
            .setChunkedBody(Buffer().write(pcm), 3_073))

        val chunks = model(http).streamPcm(TtsRequest(" hi ", "nova", 1f)).toList()
        assertFalse(cancelled.get())
        val joined = Buffer()
        for (chunk in chunks) {
            assertEquals(44_100, chunk.sampleRate)
            assertEquals(2, chunk.channels)
            assertTrue(chunk.bytes.size in 4..8192)
            assertEquals(0, chunk.bytes.size % 4)
            joined.write(chunk.bytes)
        }
        assertArrayEquals(pcm, joined.readByteArray())

        val request = server.takeRequest()
        assertEquals("/audio/speech", request.path)
        assertEquals("Bearer sk", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-4o-mini-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals("hi", body["input"]!!.jsonPrimitive.content)
        assertEquals("nova", body["voice"]!!.jsonPrimitive.content)
        assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("audio", body["stream_format"]!!.jsonPrimitive.content)
        assertEquals("1.0", body["speed"]!!.jsonPrimitive.content)
    }

    @Test fun usesHeaderlessPcmDefaultsOnlyForTheFinalOfficialOpenAiEndpoint() = runTest {
        val officialUrl = "https://api.openai.com/v1"
        val pcm = byteArrayOf(1, 2, 3, 4)
        fun speechModel(finalUrl: String) = OpenAiSpeechModel(
            officialUrl, "sk", "gpt-4o-mini-tts", caps(),
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request().newBuilder().url(finalUrl).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(pcm.toResponseBody("audio/pcm".toMediaType()))
                    .build()
            }.build(),
        )
        val request = TtsRequest("hello", "alloy")
        val chunk = speechModel("$officialUrl/audio/speech").streamPcm(request).toList().single()
        assertEquals(24_000, chunk.sampleRate)
        assertEquals(1, chunk.channels)
        assertArrayEquals(pcm, chunk.bytes)

        val result = runCatching { speechModel("https://speech.example/v1/audio/speech").streamPcm(request).toList() }
        assertTrue(result.exceptionOrNull() is TtsError.StreamingUnsupported)
    }

    @Test fun detectsStreamingRejectionWithoutMaskingAuthErrors() = runTest {
        val request = TtsRequest("hello", "alloy")
        server.enqueue(MockResponse().setResponseCode(422).setBody(
            """{"detail":[{"loc":["body","stream_format"],"msg":"Extra inputs are not permitted"}]}""",
        ))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav")
            .setBody(Buffer().write(wrapPcm16Wav(byteArrayOf(1, 2), 24_000))))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val tts = model()
        repeat(2) {
            val result = runCatching { tts.streamPcm(request).collect { fail("unexpected PCM") } }
            assertTrue(result.exceptionOrNull() is TtsError.StreamingUnsupported)
        }
        val result = runCatching { tts.streamPcm(request).collect { fail("unexpected PCM") } }
        assertTrue(result.exceptionOrNull() is ModelError.Auth)
    }

    @Test fun cancellingStreamClosesTheHttpCallWhileWaitingForAudio() = assertCancelsRequest { tts, request ->
        tts.streamPcm(request).collect {}
    }

    @Test fun cancellingBufferedSynthesisClosesTheHttpCallWhileWaitingForAudio() = assertCancelsRequest { tts, request ->
        tts.synthesize(request)
    }

    private fun assertCancelsRequest(consume: suspend (OpenAiSpeechModel, TtsRequest) -> Unit) = runBlocking {
        val cancelled = AtomicBoolean(false)
        val http = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelled.set(true)
                }
            })
            .build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val synthesis = launch { consume(model(http), TtsRequest("hello", "alloy")) }
        val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertNotNull(request)
        withTimeout(5_000) { synthesis.cancelAndJoin() }
        assertTrue(cancelled.get())
    }

    @Test fun mapsAuthError() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            model().synthesize(TtsRequest("hello", "alloy"))
            fail("expected auth error")
        } catch (e: ModelError.Auth) {
            Unit
        }
    }

    @Test fun rejectsBeforeSending() = runTest {
        try { model().synthesize(TtsRequest("hello", "unknown")); fail("expected voice error") } catch (e: TtsError.Unsupported) { Unit }
        try { model().synthesize(TtsRequest("x".repeat(101), "alloy")); fail("expected length error") } catch (e: TtsError.Unsupported) { Unit }
        try { model().synthesize(TtsRequest("hello", "alloy", format = "flac")); fail("expected format error") } catch (e: TtsError.Unsupported) { Unit }
        assertEquals(0, server.requestCount)
    }
}
