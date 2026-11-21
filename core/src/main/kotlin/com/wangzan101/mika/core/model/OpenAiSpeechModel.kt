package com.wangzan101.mika.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * OpenAI Speech 兼容接口：`POST {base}/audio/speech`，返回二进制音频。
 * 也覆盖火山、硅基流动等按这个形状提供 TTS 的服务；音色和格式由能力表/服务商校验。
 */
class OpenAiSpeechModel(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val capabilities: TtsCapabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
) : StreamingTtsModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/audio/speech"
    private val json = "application/json".toMediaType()

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val format = request.format.lowercase()
        return executeCancellable(speechRequest(request, format)) { response ->
            if (!response.isSuccessful) {
                throw ModelHttp.openAiError(response.code, response.header("retry-after"), response.body?.string().orEmpty(), model)
            }
            val body = response.body ?: throw TtsError.BadAudio("empty response")
            val declared = body.contentLength()
            if (declared > MAX_TTS_BYTES) throw TtsError.TooLarge(declared)
            val bytes = body.bytes()
            checkTtsSize(bytes)
            if (format == "pcm") {
                TtsResponse(wrapPcm16Wav(bytes, 24_000), "audio/wav", 24_000)
            } else {
                TtsResponse(bytes, audioMimeOrBad(bytes, response.header("Content-Type")))
            }
        }
    }

    override fun streamPcm(request: TtsRequest): Flow<TtsPcmChunk> = channelFlow {
        if (request.speed != null && request.speed != 1f) {
            throw TtsError.StreamingUnsupported("PCM streaming requires speed 1.0")
        }
        executeCancellable(speechRequest(request, "pcm", streaming = true)) { response ->
            response.readPcm { send(it) }
        }
    }.buffer(Channel.RENDEZVOUS)

    private suspend fun <T> executeCancellable(req: Request, read: suspend (Response) -> T): T = coroutineScope {
        val call = http.newCall(req)
        val reader = async(Dispatchers.IO) {
            try {
                call.execute().use { read(it) }
            } catch (e: IOException) {
                ensureActive()
                throw HttpSupport.networkError(http, req, e)
            }
        }
        // 阻塞读取不会自行响应协程取消，需要关闭仍在读取的 HTTP 请求。
        try {
            reader.await()
        } finally {
            if (!reader.isCompleted) call.cancel()
        }
    }

    private fun speechRequest(request: TtsRequest, format: String, streaming: Boolean = false): Request {
        val text = request.text.trim()
        if (text.isEmpty()) throw TtsError.Unsupported("text is empty")
        if (text.length > capabilities.maxChars) throw TtsError.Unsupported("text is longer than ${capabilities.maxChars} characters")
        if (format !in capabilities.formats) {
            val message = "format $format is not supported"
            throw if (streaming) TtsError.StreamingUnsupported(message) else TtsError.Unsupported(message)
        }
        val voice = request.voice ?: capabilities.voices?.firstOrNull() ?: throw TtsError.Unsupported("voice is required")
        capabilities.voices?.let { if (voice !in it) throw TtsError.Unsupported("voice $voice is not supported") }
        val payload = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", format)
            if (streaming) put("stream_format", "audio")
            request.speed?.let { put("speed", it) }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .apply { if (!apiKey.isNullOrEmpty()) headerOrReject("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody(json))
            .build()
    }

    private suspend fun Response.readPcm(emit: suspend (TtsPcmChunk) -> Unit) {
        if (!isSuccessful) {
            val error = body?.let { peekBody(16 * 1024L).string() }.orEmpty()
            if (streamRejected(code, error)) {
                throw TtsError.StreamingUnsupported(ModelAccessErrors.detail(error).ifBlank { "PCM streaming is not supported" })
            }
            throw ModelHttp.openAiError(code, header("retry-after"), error, model)
        }

        val audio = body ?: throw TtsError.BadAudio("empty response")
        val mime = audio.contentType()
        val rateHeader = header("x-audio-sample-rate")
        val channelsHeader = header("x-audio-channels")
        val responseUrl = request.url
        // OpenAI 官方接口的无头 PCM 固定为 24 kHz 单声道，兼容接口提供自己的格式头。
        val useOpenAiDefaults = rateHeader == null && channelsHeader == null &&
            responseUrl.isHttps && responseUrl.host == "api.openai.com" && responseUrl.port == 443
        val sampleRate = if (useOpenAiDefaults) 24_000 else rateHeader?.toIntOrNull()
        val channels = if (useOpenAiDefaults) 1 else channelsHeader?.toIntOrNull()
        if (mime?.type != "audio" || mime.subtype != "pcm" || sampleRate == null ||
            sampleRate !in 4_000..192_000 || channels == null || channels !in 1..2
        ) {
            throw TtsError.StreamingUnsupported("speech endpoint did not return PCM with sample rate and channels")
        }
        val declared = audio.contentLength()
        if (declared > MAX_TTS_BYTES) throw TtsError.TooLarge(declared)

        val source = audio.byteStream()
        val buffer = ByteArray(8 * 1024)
        val frameSize = channels * 2
        var remaining = 0
        var total = 0L
        while (true) {
            val count = source.read(buffer, remaining, buffer.size - remaining)
            if (count == -1) break
            total += count
            if (total > MAX_TTS_BYTES) throw TtsError.TooLarge(total)

            // HTTP 数据块可以断在一个 PCM 帧内。把尾部的字节留给下一次读取。
            val available = remaining + count
            val aligned = available - available % frameSize
            if (aligned > 0) emit(TtsPcmChunk(buffer.copyOf(aligned), sampleRate, channels))
            buffer.copyInto(buffer, 0, aligned, available)
            remaining = available - aligned
        }
        if (total == 0L) throw TtsError.BadAudio("empty PCM stream")
        if (remaining > 0) throw TtsError.BadAudio("PCM stream ended in an incomplete frame")
    }

    private fun streamRejected(status: Int, body: String): Boolean {
        if (status == 501) return true
        if (status != 400 && status != 422) return false
        val detail = ModelAccessErrors.detail(body).lowercase()
        return "stream_format" in detail ||
            ("streaming" in detail && ("not supported" in detail || "unsupported" in detail)) ||
            ("pcm" in detail && ("response_format" in detail || "response format" in detail))
    }
}
