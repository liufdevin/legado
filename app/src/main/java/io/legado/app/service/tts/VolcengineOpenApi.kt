package io.legado.app.service.tts

import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object VolcengineOpenApi {

    private const val HOST = "open.volcengineapi.com"
    private const val REGION = "cn-beijing"
    private const val SERVICE = "speech_saas_prod"
    private const val LIST_SPEAKERS_ACTION = "ListSpeakers"
    private const val LIST_SPEAKERS_VERSION = "2025-05-20"
    private const val CONTENT_TYPE = "application/json; charset=UTF-8"
    private const val DEFAULT_LIMIT = 100

    fun listSpeakerLabels(
        accessKeyId: String,
        secretAccessKey: String,
        resourceId: String,
        limit: Int = DEFAULT_LIMIT
    ): List<String> {
        if (accessKeyId.isBlank() || secretAccessKey.isBlank()) {
            throw NoStackTraceException("请先填写 Access Key ID 和 Secret Access Key")
        }
        val body = GSON.toJson(
            mapOf(
                "resource_ids" to listOf(resourceId.ifBlank { "seed-tts-2.0" }),
                "page" to 1,
                "limit" to limit.coerceIn(1, 100)
            )
        )
        val query = "Action=$LIST_SPEAKERS_ACTION&Version=$LIST_SPEAKERS_VERSION"
        val payloadHash = sha256Hex(body)
        val xDate = utcNow("yyyyMMdd'T'HHmmss'Z'")
        val shortDate = utcNow("yyyyMMdd")
        val authorization = authorization(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            shortDate = shortDate,
            xDate = xDate,
            query = query,
            payloadHash = payloadHash
        )
        val debugInfo = listSpeakersDebugInfo(
            accessKeyId = accessKeyId,
            resourceId = resourceId.ifBlank { "seed-tts-2.0" },
            query = query,
            body = body,
            payloadHash = payloadHash,
            xDate = xDate,
            authorization = authorization
        )
        val request = Request.Builder()
            .url("https://$HOST/?$query")
            .header("Content-Type", CONTENT_TYPE)
            .header("X-Date", xDate)
            .header("X-Content-Sha256", payloadHash)
            .header("Authorization", authorization.header)
            .post(body.toRequestBody(CONTENT_TYPE.toMediaType()))
            .build()
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: Throwable) {
            AppLog.put("火山 ListSpeakers 请求异常\n$debugInfo", e)
            throw e
        }
        response.use {
            val responseBody = response.body.string()
            val safeResponseBody = responseBody.maskSecrets(accessKeyId)
            if (!response.isSuccessful) {
                val message = "拉取火山音色列表失败: ${response.code} ${response.message}\n" +
                        "$debugInfo\n" +
                        "responseHeaders=${response.headers.safeText()}\n" +
                        "responseBody=$safeResponseBody"
                AppLog.put("火山 ListSpeakers HTTP 失败\n$message")
                throw NoStackTraceException(message)
            }
            return runCatching {
                parseSpeakerLabels(responseBody)
            }.getOrElse {
                AppLog.put(
                    "火山 ListSpeakers 响应解析失败\n$debugInfo\nresponseBody=$safeResponseBody",
                    it
                )
                throw it
            }
        }
    }

    private data class SignedAuthorization(
        val header: String,
        val canonicalRequest: String,
        val stringToSign: String
    )

    private fun authorization(
        accessKeyId: String,
        secretAccessKey: String,
        shortDate: String,
        xDate: String,
        query: String,
        payloadHash: String
    ): SignedAuthorization {
        val signedHeaders = "host;x-content-sha256;x-date"
        val credentialScope = "$shortDate/$REGION/$SERVICE/request"
        val canonicalHeaders = "host:$HOST\n" +
                "x-content-sha256:$payloadHash\n" +
                "x-date:$xDate\n"
        val canonicalRequest = listOf(
            "POST",
            "/",
            query,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")
        val stringToSign = listOf(
            "HMAC-SHA256",
            xDate,
            credentialScope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")
        val signingKey = hmacSha256(
            hmacSha256(
                hmacSha256(
                    hmacSha256(secretAccessKey.toByteArray(Charsets.UTF_8), shortDate),
                    REGION
                ),
                SERVICE
            ),
            "request"
        )
        val signature = hmacSha256(signingKey, stringToSign).toHex()
        val header = "HMAC-SHA256 Credential=$accessKeyId/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"
        return SignedAuthorization(
            header = header,
            canonicalRequest = canonicalRequest,
            stringToSign = stringToSign
        )
    }

    private fun listSpeakersDebugInfo(
        accessKeyId: String,
        resourceId: String,
        query: String,
        body: String,
        payloadHash: String,
        xDate: String,
        authorization: SignedAuthorization
    ): String {
        return """
            url=https://$HOST/?$query
            service=$SERVICE
            region=$REGION
            accessKeyId=${accessKeyId.maskForLog()}
            resourceId=$resourceId
            xDate=$xDate
            payloadHash=$payloadHash
            requestBody=$body
            canonicalRequest:
            ${authorization.canonicalRequest}
            stringToSign:
            ${authorization.stringToSign}
        """.trimIndent()
    }

    private fun parseSpeakerLabels(responseBody: String): List<String> {
        val root = GSON.fromJsonObject<Map<String, Any?>>(responseBody).getOrNull()
            ?: throw NoStackTraceException("火山音色列表返回格式错误")
        val speakers = root.listValue("speakers", "Speakers") ?: run {
            val result = root.mapValue("Result", "result")
                ?: throw NoStackTraceException("火山音色列表缺少 Result/speakers")
            result.listValue("speakers", "Speakers")
                ?: throw NoStackTraceException("火山音色列表缺少 speakers")
        }
        return speakers.mapNotNull { item ->
            val speaker = item as? Map<*, *> ?: return@mapNotNull null
            val voiceType = speaker.stringValue("voice_type", "VoiceType")
                ?: return@mapNotNull null
            val name = speaker.stringValue("name", "Name")
            val gender = speaker.stringValue("gender", "Gender")
            val prefix = listOfNotNull(name, gender).joinToString(" ")
            if (prefix.isBlank()) voiceType else "$prefix | $voiceType"
        }.distinct()
    }

    private fun utcNow(format: String): String {
        return SimpleDateFormat(format, Locale.US).run {
            timeZone = SimpleTimeZone(0, "UTC")
            format(Date())
        }
    }

    private fun sha256Hex(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    private fun hmacSha256(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun okhttp3.Headers.safeText(): String {
        return names().joinToString("; ") { name -> "$name=${get(name)}" }
    }

    private fun Map<*, *>.mapValue(vararg keys: String): Map<*, *>? {
        keys.forEach { key ->
            (get(key) as? Map<*, *>)?.let { return it }
        }
        return null
    }

    private fun Map<*, *>.listValue(vararg keys: String): List<*>? {
        keys.forEach { key ->
            (get(key) as? List<*>)?.let { return it }
        }
        return null
    }

    private fun Map<*, *>.stringValue(vararg keys: String): String? {
        keys.forEach { key ->
            get(key)?.toString()?.takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        return null
    }

    private fun String.maskForLog(): String {
        if (isBlank()) return "(空)"
        if (length <= 8) return "${take(2)}***${takeLast(2)}"
        return "${take(4)}***${takeLast(4)}"
    }

    private fun String.maskSecrets(accessKeyId: String): String {
        var text = this
        accessKeyId.takeIf { it.isNotBlank() }?.let {
            text = text.replace(it, it.maskForLog())
        }
        return text
    }

}
