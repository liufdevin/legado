package io.legado.app.service.tts

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.getProxyClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.min

object VolcengineBidirectionalTtsClient : OnlineTtsClient {

    private const val DEFAULT_ENDPOINT =
        "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
    private const val DEFAULT_RESOURCE_ID = "seed-tts-2.0"
    private const val DEFAULT_USER_UID = "legado"
    private const val DEFAULT_AUDIO_FORMAT = "mp3"
    private const val DEFAULT_SAMPLE_RATE = 24000
    private const val DEFAULT_TEXT_CHUNK_SIZE = 200
    private const val DEFAULT_STREAM_TIMEOUT_MILLIS = 120_000L

    private const val EVENT_START_CONNECTION = 1
    private const val EVENT_FINISH_CONNECTION = 2
    private const val EVENT_CONNECTION_STARTED = 50
    private const val EVENT_CONNECTION_FAILED = 51
    private const val EVENT_START_SESSION = 100
    private const val EVENT_FINISH_SESSION = 102
    private const val EVENT_SESSION_STARTED = 150
    private const val EVENT_SESSION_FINISHED = 152
    private const val EVENT_SESSION_FAILED = 153
    private const val EVENT_TASK_REQUEST = 200

    private const val MSG_FULL_CLIENT_REQUEST = 0x14
    private const val MSG_FULL_SERVER_RESPONSE = 0x94
    private const val MSG_AUDIO_ONLY_RESPONSE = 0xB4
    private const val MSG_ERROR = 0xF0
    private const val SERIAL_JSON = 0x10

    override fun canHandle(httpTts: HttpTTS): Boolean {
        val url = httpTts.url.trim().substringBefore(",").trim()
        return url.equals(DEFAULT_ENDPOINT, true) ||
                url.startsWith("volcengine://tts/bidirection", true) ||
                (url.startsWith("wss://", true) &&
                        url.contains("/api/v3/tts/bidirection", true))
    }

    override suspend fun getAudioStream(
        httpTts: HttpTTS,
        speakText: String,
        speakSpeed: Int,
        coroutineContext: CoroutineContext
    ): InputStream? {
        val config = VolcengineConfig.from(httpTts, speakSpeed)
        val audioStream = QueueAudioInputStream(config.streamTimeoutMillis)
        val request = buildRequest(httpTts, config)
        val webSocket = createWebSocketClient(config.proxy)
            .newWebSocket(request, VolcengineWebSocketListener(config, speakText, audioStream))
        audioStream.onClose = { webSocket.cancel() }
        return audioStream
    }

    private fun buildRequest(httpTts: HttpTTS, config: VolcengineConfig): Request {
        val builder = Request.Builder()
            .url(config.endpoint)

        httpTts.getHeaderMap(true).forEach { (key, value) ->
            when {
                key.equals("proxy", true) -> Unit
                key.equals("connection", true) -> Unit
                key.equals("upgrade", true) -> Unit
                else -> builder.addHeader(key, value)
            }
        }

        if (config.apiKey.isNotBlank()) {
            builder.header("X-Api-Key", config.apiKey)
        } else {
            builder.header("X-Api-App-Key", config.appKey)
            builder.header("X-Api-Access-Key", config.accessKey)
        }
        builder.header("X-Api-Resource-Id", config.resourceId)
        builder.header("X-Api-Connect-Id", UUID.randomUUID().toString())
        if (config.requireUsage) {
            builder.header("X-Control-Require-Usage-Tokens-Return", "*")
        }
        return builder.build()
    }

    private fun createWebSocketClient(proxy: String?): OkHttpClient {
        return getProxyClient(proxy).newBuilder().apply {
            interceptors().clear()
            networkInterceptors().clear()
            connectTimeout(15, TimeUnit.SECONDS)
            writeTimeout(15, TimeUnit.SECONDS)
            readTimeout(0, TimeUnit.MILLISECONDS)
            callTimeout(0, TimeUnit.MILLISECONDS)
            pingInterval(20, TimeUnit.SECONDS)
        }.build()
    }

    private class VolcengineWebSocketListener(
        private val config: VolcengineConfig,
        private val speakText: String,
        private val audioStream: QueueAudioInputStream
    ) : WebSocketListener() {

        private val sessionId = UUID.randomUUID().toString()
        @Volatile
        private var finished = false

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            sendPacket(webSocket, EVENT_START_CONNECTION, emptyMap(), "")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            runCatching {
                handlePacket(webSocket, parseServerPacket(bytes))
            }.onFailure {
                fail(webSocket, it)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!finished) {
                audioStream.fail(IOException("火山引擎 TTS 连接已关闭: $code $reason"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            if (!finished) {
                audioStream.fail(t)
            }
        }

        private fun handlePacket(webSocket: WebSocket, packet: ServerPacket) {
            when {
                packet.msgType == MSG_ERROR -> {
                    error("服务端错误 code=${packet.errorCode}, payload=${packet.payload}")
                }

                packet.event == EVENT_CONNECTION_STARTED -> {
                    sendPacket(
                        webSocket = webSocket,
                        event = EVENT_START_SESSION,
                        payload = mapOf(
                            "event" to EVENT_START_SESSION,
                            "namespace" to "BidirectionalTTS",
                            "user" to mapOf("uid" to config.userUid),
                            "req_params" to config.reqParams
                        ),
                        sessionId = sessionId
                    )
                }

                packet.event == EVENT_CONNECTION_FAILED -> {
                    error("ConnectionFailed: ${packet.payload}")
                }

                packet.event == EVENT_SESSION_STARTED -> {
                    feedText(webSocket)
                    sendPacket(webSocket, EVENT_FINISH_SESSION, emptyMap(), sessionId)
                }

                packet.event == EVENT_SESSION_FAILED -> {
                    error("SessionFailed: ${packet.payload}")
                }

                packet.audio.isNotEmpty() -> {
                    if (packet.sessionId.isEmpty() || packet.sessionId == sessionId) {
                        audioStream.offer(packet.audio)
                    }
                }
            }

            if (packet.event == EVENT_SESSION_FINISHED) {
                finished = true
                sendPacket(webSocket, EVENT_FINISH_CONNECTION, emptyMap(), "")
                audioStream.finish()
                webSocket.close(1000, "finished")
            }
        }

        private fun feedText(webSocket: WebSocket) {
            splitText(speakText, config.textChunkSize).forEach { chunk ->
                sendPacket(
                    webSocket = webSocket,
                    event = EVENT_TASK_REQUEST,
                    payload = mapOf(
                        "event" to EVENT_TASK_REQUEST,
                        "namespace" to "BidirectionalTTS",
                        "req_params" to mapOf("text" to chunk)
                    ),
                    sessionId = sessionId
                )
            }
        }

        private fun sendPacket(
            webSocket: WebSocket,
            event: Int,
            payload: Map<String, Any?>,
            sessionId: String
        ) {
            val sent = webSocket.send(makeClientPacket(event, payload, sessionId))
            if (!sent) {
                error("发送 WebSocket 包失败, event=$event")
            }
        }

        private fun fail(webSocket: WebSocket, throwable: Throwable) {
            if (!finished) {
                AppLog.put("火山引擎 TTS 错误\n${throwable.localizedMessage}", throwable)
                audioStream.fail(throwable)
                webSocket.cancel()
            }
        }

    }

    private data class VolcengineConfig(
        val endpoint: String,
        val apiKey: String,
        val appKey: String,
        val accessKey: String,
        val resourceId: String,
        val userUid: String,
        val reqParams: Map<String, Any?>,
        val requireUsage: Boolean,
        val proxy: String?,
        val textChunkSize: Int,
        val streamTimeoutMillis: Long
    ) {

        companion object {

            fun from(httpTts: HttpTTS, speakSpeed: Int): VolcengineConfig {
                val loginInfo = httpTts.getLoginInfoMap()
                val headerMap = httpTts.getHeaderMap(true)
                val endpoint = loginInfo.firstNotBlank("Endpoint", "endpoint", "接口地址")
                    ?: httpTts.url.trim().substringBefore(",").trim()
                        .takeUnless { it.startsWith("volcengine://", true) }
                    ?: DEFAULT_ENDPOINT
                val apiKey = loginInfo.firstNotBlank(
                    "API Key",
                    "ApiKey",
                    "api_key",
                    "Access Token",
                    "AccessToken",
                    "access_token",
                    "访问令牌",
                    "令牌",
                    "X-Api-Key"
                ) ?: headerMap.firstNotBlank("X-Api-Key") ?: ""
                val appKey = loginInfo.firstNotBlank(
                    "Access Key ID"
                ) ?: ""
                val accessKey = loginInfo.firstNotBlank(
                    "Secret Access Key"
                ) ?: ""
                if (apiKey.isBlank() && (appKey.isBlank() || accessKey.isBlank())) {
                    throw NoStackTraceException("请先配置火山引擎 API Key，或 Access Key ID + Secret Access Key")
                }
                return VolcengineConfig(
                    endpoint = endpoint.ifBlank { DEFAULT_ENDPOINT },
                    apiKey = apiKey,
                    appKey = appKey,
                    accessKey = accessKey,
                    resourceId = loginInfo.firstNotBlank(
                        "Resource ID",
                        "ResourceId",
                        "resource_id",
                        "资源 ID",
                        "X-Api-Resource-Id"
                    ) ?: headerMap.firstNotBlank(
                        "X-Api-Resource-Id"
                    ) ?: DEFAULT_RESOURCE_ID,
                    userUid = loginInfo.firstNotBlank("User UID", "UserUid", "uid", "用户 ID")
                        ?: DEFAULT_USER_UID,
                    reqParams = buildReqParams(loginInfo, speakSpeed),
                    requireUsage = loginInfo.booleanValue(
                        "返回用量",
                        "Require Usage",
                        "require_usage"
                    ),
                    proxy = headerMap.firstNotBlank("proxy"),
                    textChunkSize = loginInfo.intValue(
                        "Text Chunk Size",
                        "text_chunk_size",
                        "文本分片长度"
                    )?.coerceAtLeast(0) ?: DEFAULT_TEXT_CHUNK_SIZE,
                    streamTimeoutMillis = loginInfo.longValue(
                        "Stream Timeout",
                        "stream_timeout",
                        "流超时毫秒"
                    )?.coerceAtLeast(1_000L) ?: DEFAULT_STREAM_TIMEOUT_MILLIS
                )
            }

            private fun buildReqParams(
                loginInfo: Map<String, String>,
                speakSpeed: Int
            ): Map<String, Any?> {
                loginInfo.firstNotBlank(
                    "Raw Req Params",
                    "raw_req_params",
                    "原始请求参数 JSON"
                )?.let {
                    parseJsonMap(it)?.let { rawReqParams ->
                        return rawReqParams
                    }
                }

                val audioParams = mutableMapOf<String, Any?>()
                val additions = mutableMapOf<String, Any?>()
                audioParams["format"] = loginInfo.firstNotBlank("Format", "format", "音频格式")
                    ?: DEFAULT_AUDIO_FORMAT
                audioParams["sample_rate"] = loginInfo.intValue(
                    "Sample Rate",
                    "sample_rate",
                    "采样率"
                ) ?: DEFAULT_SAMPLE_RATE
                audioParams["speech_rate"] = loginInfo.intValue(
                    "Speech Rate",
                    "speech_rate",
                    "语速"
                ) ?: ((speakSpeed - 10) * 10).coerceIn(-50, 100)
                putInt(audioParams, loginInfo, "bit_rate", "Bit Rate", "码率")
                putInt(audioParams, loginInfo, "loudness_rate", "Loudness Rate", "loudness_rate", "音量")
                putString(audioParams, loginInfo, "emotion", "Emotion", "emotion", "情绪")
                putInt(audioParams, loginInfo, "emotion_scale", "Emotion Scale", "emotion_scale", "情绪强度")
                putBoolean(audioParams, loginInfo, "enable_timestamp", "Enable Timestamp", "enable_timestamp")
                putBoolean(audioParams, loginInfo, "enable_subtitle", "Enable Subtitle", "enable_subtitle")

                loginInfo.firstNotBlank(
                    "Audio Params Extra",
                    "audio_params_extra",
                    "音频参数 JSON"
                )?.let {
                    parseJsonMap(it)?.let { extra -> audioParams.putAll(extra) }
                }

                putString(additions, loginInfo, "explicit_language", "Explicit Language", "explicit_language", "语言")
                putString(additions, loginInfo, "explicit_dialect", "Explicit Dialect", "explicit_dialect", "方言")
                putInt(additions, loginInfo, "silence_duration", "Silence Duration", "silence_duration", "句尾静音")
                putBoolean(additions, loginInfo, "disable_markdown_filter", "Disable Markdown Filter", "disable_markdown_filter")
                putBoolean(additions, loginInfo, "disable_emoji_filter", "Disable Emoji Filter", "disable_emoji_filter")
                loginInfo.firstNotBlank("Additions Extra", "additions_extra", "附加参数 JSON")?.let {
                    parseJsonMap(it)?.let { extra -> additions.putAll(extra) }
                }

                val reqParams = mutableMapOf<String, Any?>(
                    "speaker" to (
                            loginInfo.firstNotBlank("Speaker", "Voice", "voice_type", "音色")
                                ?: "zh_male_dayi_uranus_bigtts"
                            ).toSpeakerId(),
                    "audio_params" to audioParams
                )
                loginInfo.firstNotBlank("Model", "model", "模型")?.let {
                    reqParams["model"] = it
                }
                if (additions.isNotEmpty()) {
                    reqParams["additions"] = GSON.toJson(additions)
                }
                return reqParams
            }

            private fun putString(
                target: MutableMap<String, Any?>,
                source: Map<String, String>,
                targetKey: String,
                vararg sourceKeys: String
            ) {
                source.firstNotBlank(*sourceKeys)?.let {
                    target[targetKey] = it
                }
            }

            private fun putInt(
                target: MutableMap<String, Any?>,
                source: Map<String, String>,
                targetKey: String,
                vararg sourceKeys: String
            ) {
                source.intValue(*sourceKeys)?.let {
                    target[targetKey] = it
                }
            }

            private fun putBoolean(
                target: MutableMap<String, Any?>,
                source: Map<String, String>,
                targetKey: String,
                vararg sourceKeys: String
            ) {
                source.firstNotBlank(*sourceKeys)?.let {
                    target[targetKey] = it.toBooleanValue()
                }
            }

            private fun parseJsonMap(json: String): MutableMap<String, Any?>? {
                return GSON.fromJsonObject<MutableMap<String, Any?>>(json).getOrNull()
            }

        }

    }

    private data class ServerPacket(
        val msgType: Int = 0,
        val event: Int = 0,
        val sessionId: String = "",
        val payload: Any? = null,
        val audio: ByteArray = ByteArray(0),
        val errorCode: Int = 0
    )

    private class QueueAudioInputStream(
        private val timeoutMillis: Long
    ) : InputStream() {

        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile
        private var finished = false
        @Volatile
        private var closed = false
        @Volatile
        private var error: IOException? = null
        private var current = ByteArray(0)
        private var currentOffset = 0
        var onClose: (() -> Unit)? = null

        fun offer(bytes: ByteArray) {
            if (!closed && bytes.isNotEmpty()) {
                queue.offer(bytes)
            }
        }

        fun finish() {
            finished = true
            queue.offer(ByteArray(0))
        }

        fun fail(throwable: Throwable) {
            error = if (throwable is IOException) {
                throwable
            } else {
                IOException(throwable)
            }
            finished = true
            queue.offer(ByteArray(0))
        }

        override fun read(): Int {
            val buffer = ByteArray(1)
            val read = read(buffer, 0, 1)
            return if (read == -1) -1 else buffer[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (closed) return -1
            while (currentOffset >= current.size) {
                error?.let { throw it }
                if (finished && queue.isEmpty()) {
                    return -1
                }
                current = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                    ?: throw SocketTimeoutException("火山引擎 TTS 音频流等待超时")
                currentOffset = 0
                if (current.isEmpty()) {
                    error?.let { throw it }
                    if (finished) return -1
                }
            }
            val readLength = min(length, current.size - currentOffset)
            System.arraycopy(current, currentOffset, buffer, offset, readLength)
            currentOffset += readLength
            return readLength
        }

        override fun close() {
            if (closed) return
            closed = true
            finished = true
            queue.offer(ByteArray(0))
            onClose?.invoke()
        }

    }

    private fun makeClientPacket(
        event: Int,
        payload: Map<String, Any?>,
        sessionId: String
    ): ByteString {
        val sessionBytes = sessionId.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        val payloadBytes = GSON.toJson(payload).toByteArray(Charsets.UTF_8)
        val size = 4 + 4 +
                (sessionBytes?.let { 4 + it.size } ?: 0) +
                4 + payloadBytes.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x11.toByte())
        buffer.put(MSG_FULL_CLIENT_REQUEST.toByte())
        buffer.put(SERIAL_JSON.toByte())
        buffer.put(0x00.toByte())
        buffer.putInt(event)
        sessionBytes?.let {
            buffer.putInt(it.size)
            buffer.put(it)
        }
        buffer.putInt(payloadBytes.size)
        buffer.put(payloadBytes)
        return buffer.array().toByteString()
    }

    private fun parseServerPacket(bytes: ByteString): ServerPacket {
        val data = bytes.toByteArray()
        if (data.size < 4) return ServerPacket()
        val msgType = data[1].toInt() and 0xFF
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        if (msgType == MSG_ERROR) {
            val errorCode = buffer.readIntSafely()
            return ServerPacket(
                msgType = msgType,
                event = errorCode,
                errorCode = errorCode,
                payload = decodePayload(buffer.readLengthPrefixedBytes())
            )
        }
        val event = buffer.readIntSafely()
        if (event == EVENT_CONNECTION_STARTED || event == EVENT_CONNECTION_FAILED) {
            buffer.readLengthPrefixedBytes()
            return ServerPacket(
                msgType = msgType,
                event = event,
                payload = decodePayload(buffer.readLengthPrefixedBytes())
            )
        }
        val sessionId = buffer.readLengthPrefixedBytes().toString(Charsets.UTF_8)
        if (msgType == MSG_AUDIO_ONLY_RESPONSE) {
            return ServerPacket(
                msgType = msgType,
                event = event,
                sessionId = sessionId,
                audio = buffer.readLengthPrefixedBytes()
            )
        }
        if (msgType == MSG_FULL_SERVER_RESPONSE) {
            return ServerPacket(
                msgType = msgType,
                event = event,
                sessionId = sessionId,
                payload = decodePayload(buffer.readLengthPrefixedBytes())
            )
        }
        return ServerPacket(msgType = msgType, event = event, sessionId = sessionId)
    }

    private fun ByteBuffer.readIntSafely(): Int {
        return if (remaining() >= 4) int else 0
    }

    private fun ByteBuffer.readLengthPrefixedBytes(): ByteArray {
        if (remaining() < 4) return ByteArray(0)
        val length = int
        if (length < 0 || remaining() < length) return ByteArray(0)
        return ByteArray(length).also { get(it) }
    }

    private fun decodePayload(bytes: ByteArray): Any? {
        if (bytes.isEmpty()) return null
        val text = bytes.toString(Charsets.UTF_8)
        return GSON.fromJsonObject<MutableMap<String, Any?>>(text).getOrNull() ?: text
    }

    private fun splitText(text: String, chunkSize: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        if (chunkSize <= 0 || text.length <= chunkSize) return listOf(text)
        return text.chunked(chunkSize)
    }

    private fun Map<String, String>.firstNotBlank(vararg keys: String): String? {
        keys.forEach { key ->
            get(key)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return it
            }
            entries.firstOrNull { it.key.equals(key, true) }
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return null
    }

    private fun Map<String, String>.intValue(vararg keys: String): Int? {
        return firstNotBlank(*keys)?.toIntOrNull()
    }

    private fun Map<String, String>.longValue(vararg keys: String): Long? {
        return firstNotBlank(*keys)?.toLongOrNull()
    }

    private fun Map<String, String>.booleanValue(vararg keys: String): Boolean {
        return firstNotBlank(*keys)?.toBooleanValue() ?: false
    }

    private fun String.toBooleanValue(): Boolean {
        return equals("true", true) ||
                this == "1" ||
                equals("yes", true) ||
                equals("on", true) ||
                this == "是" ||
                this == "启用"
    }

    private fun String.toSpeakerId(): String {
        return substringAfterLast(" | ").trim()
    }

}
