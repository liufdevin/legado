package io.legado.app.ui.login

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.model.ReadAloud
import io.legado.app.service.tts.VolcengineOpenApi
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

@Suppress("unused")
class SourceLoginJsExtensions(
    activity: AppCompatActivity?,
    source: BaseSource?,
    bookType: Int = 0,
    callback: Callback? = null
) : RssJsExtensions(activity, source, bookType) {
    private val callbackRef: WeakReference<Callback> = WeakReference(callback)
    interface Callback {
        fun upUiData(data: Map<String, Any?>?)
        fun reUiView(deltaUp: Boolean = false)
    }

    fun upLoginData(data: Map<String, Any?>?) {
        callbackRef.get()?.upUiData(data)
    }

    @JvmOverloads
    fun reLoginView(deltaUp: Boolean = false) {
        callbackRef.get()?.reUiView(deltaUp)
    }

    fun refreshExplore() {
        callbackRef.get()?.reUiView()
    }

    fun refreshBookInfo() {
        postEvent(EventBus.REFRESH_BOOK_INFO, true)
    }

    fun refreshBookToc() {
        postEvent(EventBus.REFRESH_BOOK_TOC, true)
    }

    fun refreshContent() {
        postEvent(EventBus.REFRESH_BOOK_CONTENT, true)
    }

    fun copyText(text: String) {
        activityRef.get()?.sendToClip(text)
    }

    fun clearTtsCache() {
        if (getSource() !is HttpTTS) return
        val activity = activityRef.get() ?: return
        activity.lifecycleScope.launch(IO) {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath = "${activity.cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            activity.toastOnUi(R.string.clear_cache_success)
        }
    }

    fun refreshVolcengineSpeakers() {
        val source = getSource() as? HttpTTS ?: return
        val activity = activityRef.get() ?: return
        activity.lifecycleScope.launch(IO) {
            val loginInfo = source.getLoginInfoMap()
            val accessKeyId = loginInfo["Access Key ID"].orEmpty()
            val secretAccessKey = loginInfo["Secret Access Key"].orEmpty()
            val resourceId = loginInfo["Resource ID"].orEmpty().ifBlank { "seed-tts-2.0" }
            runCatching {
                VolcengineOpenApi.listSpeakerLabels(
                    accessKeyId = accessKeyId,
                    secretAccessKey = secretAccessKey,
                    resourceId = resourceId
                )
            }.onSuccess { speakers ->
                if (speakers.isEmpty()) {
                    withContext(Main) {
                        activity.toastOnUi("未获取到火山音色")
                    }
                    return@onSuccess
                }
                source.put("volcengineSpeakerOptions", GSON.toJson(speakers))
                val newLoginInfo = loginInfo.toMutableMap()
                if (newLoginInfo["Speaker"].isNullOrBlank()) {
                    newLoginInfo["Speaker"] = speakers.first()
                }
                withContext(Main) {
                    callbackRef.get()?.upUiData(newLoginInfo)
                    callbackRef.get()?.reUiView()
                    activity.toastOnUi("已获取 ${speakers.size} 个火山音色")
                }
            }.onFailure {
                val safeError = it.localizedMessage
                    ?.maskSecrets(accessKeyId, secretAccessKey)
                    ?: it.toString().maskSecrets(accessKeyId, secretAccessKey)
                AppLog.put(
                    "刷新火山公版音色失败\n" +
                            "Access Key ID=${accessKeyId.maskForLog()}\n" +
                            "Resource ID=$resourceId\n" +
                            "error=$safeError",
                    it
                )
                withContext(Main) {
                    activity.toastOnUi("获取火山音色失败，错误已写入日志\n$safeError")
                }
            }
        }
    }

    @JvmOverloads
    fun showBrowser(url: String, html: String? = null, preloadJs: String? = null, config: String? = null) {
        val activity = activityRef.get() ?: return
        val source = getSource() ?: return
        activity.showDialogFragment(
            BottomWebViewDialog(
                source.getKey(),
                bookType,
                url,
                html,
                preloadJs,
                config
            )
        )
    }

}

private fun String.maskForLog(): String {
    if (isBlank()) return "(空)"
    if (length <= 8) return "${take(2)}***${takeLast(2)}"
    return "${take(4)}***${takeLast(4)}"
}

private fun String.maskSecrets(vararg secrets: String): String {
    var text = this
    secrets.filter { it.isNotBlank() }.forEach {
        text = text.replace(it, it.maskForLog())
    }
    return text
}
