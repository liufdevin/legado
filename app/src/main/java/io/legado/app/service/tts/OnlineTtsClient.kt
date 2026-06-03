package io.legado.app.service.tts

import io.legado.app.data.entities.HttpTTS
import java.io.InputStream
import kotlin.coroutines.CoroutineContext

interface OnlineTtsClient {

    fun canHandle(httpTts: HttpTTS): Boolean

    suspend fun getAudioStream(
        httpTts: HttpTTS,
        speakText: String,
        speakSpeed: Int,
        coroutineContext: CoroutineContext
    ): InputStream?

}

object OnlineTtsClients {

    private val clients: List<OnlineTtsClient> = listOf(
        VolcengineBidirectionalTtsClient
    )

    fun find(httpTts: HttpTTS): OnlineTtsClient? {
        return clients.firstOrNull { it.canHandle(httpTts) }
    }

}
