package io.legado.app.ui.rss.source.manage

import android.app.Application
import android.text.TextUtils
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.DefaultData
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 订阅源管理数据修改
 * 修改数据要copy,直接修改会导致界面不刷新
 */
class RssSourceViewModel(application: Application) : BaseViewModel(application) {

    fun topSource(vararg sources: RssSource) {
        execute {
            sources.sortBy { it.customOrder }
            val minOrder = appDb.rssSourceDao.minOrder - 1
            val array = Array(sources.size) {
                sources[it].copy(customOrder = minOrder - it)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun bottomSource(vararg sources: RssSource) {
        execute {
            sources.sortBy { it.customOrder }
            val maxOrder = appDb.rssSourceDao.maxOrder + 1
            val array = Array(sources.size) {
                sources[it].copy(customOrder = maxOrder + it)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun del(vararg rssSource: RssSource) {
        execute {
            SourceHelp.deleteRssSources(rssSource.toList())
        }
    }

    fun update(vararg rssSource: RssSource) {
        execute { appDb.rssSourceDao.update(*rssSource) }
    }

    fun upOrder() {
        execute {
            val sources = appDb.rssSourceDao.all
            for ((index: Int, source: RssSource) in sources.withIndex()) {
                source.customOrder = index + 1
            }
            appDb.rssSourceDao.update(*sources.toTypedArray())
        }
    }

    fun enableSelection(sources: List<RssSource>) {
        execute {
            val array = Array(sources.size) {
                sources[it].copy(enabled = true)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun disableSelection(sources: List<RssSource>) {
        execute {
            val array = Array(sources.size) {
                sources[it].copy(enabled = false)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun saveToFile(sources: List<RssSource>, success: (file: File, name: String) -> Unit) {
        execute {
            val name = if (sources.size == 1) {
                "rssSource_${sources.first().sourceName.normalizeFileName()}.json"
            } else {
                val timestamp = java.text.SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date())
                "rssSource_$timestamp.json"
            }
            val path = "${context.filesDir}/shareRssSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            file.writeText(GSON.toJson(sources))
            Pair(file, name)
        }.onSuccess {
            success.invoke(it.first, it.second)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun selectionAddToGroups(sources: List<RssSource>, groups: String) {
        execute {
            val array = Array(sources.size) {
                sources[it].copy().addGroup(groups)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun selectionRemoveFromGroups(sources: List<RssSource>, groups: String) {
        execute {
            val array = Array(sources.size) {
                sources[it].copy().removeGroup(groups)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun checkSelection(sources: List<RssSource>) = execute(start = CoroutineStart.LAZY) {
        if (!NetworkUtils.isAvailable()) {
            throw NoStackTraceException(context.getString(R.string.network_connection_unavailable))
        }
        val client = okHttpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        val semaphore = Semaphore(5)
        coroutineScope {
            sources.map { source ->
                async {
                    semaphore.withPermit {
                        checkSourceUrl(client, source)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun checkSourceUrl(
        client: OkHttpClient,
        source: RssSource
    ): RssSourceCheckResult {
        return runCatching {
            val analyzeUrl = AnalyzeUrl(
                source.sourceUrl,
                source = source,
                coroutineContext = currentCoroutineContext(),
                hasLoginHeader = false
            )
            val url = analyzeUrl.url.trim()
            if (!url.isAbsUrl()) {
                return@runCatching RssSourceCheckResult(
                    source = source,
                    success = false,
                    message = context.getString(R.string.invalid_url)
                )
            }
            val headResult = requestUrl(client, url, analyzeUrl.headerMap, head = true)
            if (headResult.success) {
                headResult.toCheckResult(source)
            } else {
                requestUrl(client, url, analyzeUrl.headerMap, head = false).toCheckResult(source)
            }
        }.getOrElse {
            RssSourceCheckResult(
                source = source,
                success = false,
                message = it.localizedMessage ?: it.javaClass.simpleName
            )
        }
    }

    private suspend fun requestUrl(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        head: Boolean
    ): UrlCheckResult {
        return runCatching {
            client.newCallResponse {
                url(url)
                addHeaders(headers)
                if (head) {
                    head()
                } else {
                    header("Range", "bytes=0-0")
                }
            }.use {
                UrlCheckResult(
                    success = it.isSuccessful,
                    message = "HTTP ${it.code}"
                )
            }
        }.getOrElse {
            UrlCheckResult(
                success = false,
                message = it.localizedMessage ?: it.javaClass.simpleName
            )
        }
    }

    fun addGroup(group: String) {
        execute {
            val sources = appDb.rssSourceDao.noGroup
            sources.forEach { source ->
                source.sourceGroup = group
            }
            appDb.rssSourceDao.update(*sources.toTypedArray())
        }
    }

    fun upGroup(oldGroup: String, newGroup: String?) {
        execute {
            val sources = appDb.rssSourceDao.getByGroup(oldGroup)
            sources.forEach { source ->
                source.sourceGroup?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(oldGroup)
                    if (!newGroup.isNullOrEmpty())
                        it.add(newGroup)
                    source.sourceGroup = TextUtils.join(",", it)
                }
            }
            appDb.rssSourceDao.update(*sources.toTypedArray())
        }
    }

    fun delGroup(group: String) {
        execute {
            execute {
                val sources = appDb.rssSourceDao.getByGroup(group)
                sources.forEach { source ->
                    source.sourceGroup?.splitNotBlank(",")?.toHashSet()?.let {
                        it.remove(group)
                        source.sourceGroup = TextUtils.join(",", it)
                    }
                }
                appDb.rssSourceDao.update(*sources.toTypedArray())
            }
        }
    }

    fun importDefault() {
        execute {
            DefaultData.importDefaultRssSources()
        }
    }

    fun disable(rssSource: RssSource) {
        execute {
            rssSource.enabled = false
            appDb.rssSourceDao.update(rssSource)
        }
    }

}

data class RssSourceCheckResult(
    val source: RssSource,
    val success: Boolean,
    val message: String
)

private data class UrlCheckResult(
    val success: Boolean,
    val message: String
) {
    fun toCheckResult(source: RssSource): RssSourceCheckResult {
        return RssSourceCheckResult(source, success, message)
    }
}
