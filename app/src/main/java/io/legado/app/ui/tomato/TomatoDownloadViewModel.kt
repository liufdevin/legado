package io.legado.app.ui.tomato

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.Book
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ag2s.epublib.domain.Author
import me.ag2s.epublib.domain.Date
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.Metadata
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.epub.EpubWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TomatoDownloadViewModel(application: Application) : BaseViewModel(application) {

    private val _state = sharedState
    val state: StateFlow<TomatoDownloadState> = _state.asStateFlow()

    private val charset: List<String> by lazy {
        context.assets.open("tomato_charset.json").bufferedReader().use { reader ->
            JsonParser.parseReader(reader).asJsonArray.map { it.asString }
        }
    }

    fun resolve(input: String) {
        if (input.isBlank()) return
        workJob?.cancel()
        workJob = workScope.launch {
            _state.value = TomatoDownloadState(phase = TomatoPhase.RESOLVING)
            try {
                val bookId = resolveBookId(input)
                val info = fetchBookInfo(bookId)
                _state.value = TomatoDownloadState(
                    phase = TomatoPhase.READY,
                    bookInfo = info,
                    total = info.chapters.size,
                )
            } catch (e: CancellationException) {
                _state.value = TomatoDownloadState()
                throw e
            } catch (e: Exception) {
                _state.value = TomatoDownloadState(
                    phase = TomatoPhase.ERROR,
                    error = e.message ?: "解析书籍失败",
                )
            }
        }
    }

    fun download() {
        val info = _state.value.bookInfo ?: return
        if (workJob?.isActive == true) return
        val stopWebUiWhenFinished = !TomatoWebUiService.isRunning
        TomatoDownloadService.start(context)
        TomatoWebUiService.start(context)
        workJob = workScope.launch {
            var nativeJobId: Long? = null
            var originalNativeConfig: JsonObject? = null
            _state.value = _state.value.copy(
                phase = TomatoPhase.DOWNLOADING,
                current = 0,
                total = info.chapters.size,
                currentChapter = "",
                failedCount = 0,
                error = null,
            )
            try {
                waitForNativeServer()
                originalNativeConfig = configureNativeDownloader()
                val result = runNativeDownload(info) { jobId ->
                    nativeJobId = jobId
                    originalNativeConfig?.let { original ->
                        runCatching { postNativeJson("api/config/full", original) }
                    }
                    originalNativeConfig = null
                }
                val failures = (result.totalCount - result.savedCount).coerceAtLeast(0)
                _state.value = _state.value.copy(
                    phase = TomatoPhase.GENERATING,
                    failedCount = failures,
                )
                val epubBytes = downloadNativeEpub(info, result.title)
                val localBook = saveAndImport(info, epubBytes)
                _state.value = _state.value.copy(
                    phase = TomatoPhase.COMPLETE,
                    current = result.savedCount,
                    total = result.totalCount,
                    localBook = localBook,
                    failedCount = failures,
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    nativeJobId?.let { runCatching { cancelNativeJob(it) } }
                }
                _state.value = _state.value.copy(
                    phase = TomatoPhase.READY,
                    current = 0,
                    currentChapter = "",
                    error = null,
                )
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    nativeJobId?.let { runCatching { cancelNativeJob(it) } }
                }
                _state.value = _state.value.copy(
                    phase = TomatoPhase.ERROR,
                    error = e.message ?: "下载失败",
                )
            } finally {
                withContext(NonCancellable) {
                    originalNativeConfig?.let { original ->
                        runCatching { postNativeJson("api/config/full", original) }
                    }
                }
                if (stopWebUiWhenFinished) TomatoWebUiService.stop(context)
            }
        }
    }

    fun cancel() {
        cancelActiveDownload()
    }

    fun reset() {
        cancelActiveDownload()
        _state.value = TomatoDownloadState()
    }

    private suspend fun resolveBookId(rawInput: String): String {
        val input = rawInput.trim()
        parseBookId(input)?.let { return it }

        val url = URL_REGEX.find(input)?.value
            ?.trimEnd('。', '，', ',', '；', ';', '）', ')', '】', ']')
            ?: throw IllegalArgumentException("未找到有效的番茄小说链接或书籍 ID")
        val host = url.toHttpUrlOrNull()?.host
            ?: throw IllegalArgumentException("分享链接格式不正确")
        if (ALLOWED_HOSTS.none { host == it || host.endsWith(".$it") }) {
            throw IllegalArgumentException("仅支持番茄小说官方分享链接")
        }

        val response = okHttpClient.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        ).await()
        response.use {
            if (!it.isSuccessful) throw IOException("分享链接访问失败：HTTP ${it.code}")
            parseBookId(it.request.url.toString())?.let { id -> return id }
            val html = it.body.string()
            parseBookId(html)?.let { id -> return id }
        }
        throw IllegalArgumentException("无法从分享链接中识别书籍 ID")
    }

    private fun parseBookId(input: String): String? {
        input.takeIf { it.matches(Regex("^\\d{10,}$")) }?.let { return it }
        QUERY_ID_REGEX.find(input)?.groupValues?.getOrNull(1)?.let { return it }
        PAGE_ID_REGEX.find(input)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private suspend fun fetchBookInfo(bookId: String): TomatoBookInfo {
        val pageUrl = "https://fanqienovel.com/page/$bookId"
        val html = getText(pageUrl)
        val document = Jsoup.parse(html, pageUrl)
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) throw IOException("未获取到书名，请确认书籍 ID 是否正确")

        var author = document.selectFirst(".author-name, .page-header-info .author")
            ?.text()?.trim().orEmpty()
        var coverUrl = ""
        var description = document.selectFirst(".page-abstract-content p")
            ?.text()?.trim().orEmpty()
        document.select("script[type=application/ld+json]").forEach { script ->
            runCatching { JsonParser.parseString(script.data()) }.getOrNull()?.let { json ->
                if (author.isBlank()) author = findString(json, setOf("author"), authorObject = true).orEmpty()
                if (coverUrl.isBlank()) coverUrl = findString(json, setOf("image", "images")).orEmpty()
                if (description.isBlank()) {
                    description = findString(json, setOf("description", "abstract")).orEmpty()
                }
            }
        }
        if (author.isBlank()) author = "未知作者"
        if (coverUrl.isBlank()) throw IOException("未获取到作品封面")

        val directoryJson = getText(
            "https://fanqienovel.com/api/reader/directory/detail?bookId=$bookId",
            referer = pageUrl,
            acceptJson = true,
        )
        val chapters = parseChapters(directoryJson)
        if (chapters.isEmpty()) throw IOException("未获取到章节目录")
        return TomatoBookInfo(bookId, title, author, description, coverUrl, chapters)
    }

    private fun parseChapters(json: String): List<TomatoChapter> {
        val root = JsonParser.parseString(json)
        val data = root.asJsonObject.get("data") ?: root
        val candidate = findLargestChapterArray(data) ?: return emptyList()
        return candidate.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val id = listOf("itemId", "item_id", "chapterId", "chapter_id", "id")
                .firstNotNullOfOrNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
                ?.takeIf { it.matches(Regex("\\d{10,}")) }
                ?: return@mapNotNull null
            val title = listOf("title", "chapterTitle", "chapter_title")
                .firstNotNullOfOrNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
                ?.trim().orEmpty()
            val volume = obj.get("volume_name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            TomatoChapter(id, title.ifBlank { "第${id}章" }, volume)
        }.distinctBy { it.id }
    }

    private fun findLargestChapterArray(root: JsonElement): List<JsonElement>? {
        var best: List<JsonElement>? = null
        fun walk(element: JsonElement) {
            when {
                element.isJsonArray -> {
                    val values = element.asJsonArray.toList()
                    val chapterLike = values.any { item ->
                        item.isJsonObject && item.asJsonObject.keySet().any { key ->
                            key in setOf("itemId", "item_id", "chapterId", "chapter_id")
                        }
                    }
                    if (chapterLike && values.size > (best?.size ?: 0)) best = values
                    values.forEach(::walk)
                }
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { walk(it.value) }
            }
        }
        walk(root)
        return best
    }

    private suspend fun waitForNativeServer() {
        var lastError: Exception? = null
        repeat(NATIVE_START_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                getNativeJson("api/status")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < NATIVE_START_ATTEMPTS - 1) delay(NATIVE_START_INTERVAL_MS)
        }
        throw IOException("番茄批量下载服务启动超时", lastError)
    }

    private suspend fun configureNativeDownloader(): JsonObject {
        val original = getNativeJson("api/config/full")
        val config = original.deepCopy()
        config.addProperty("max_workers", 1)
        config.addProperty("novel_format", "epub")
        config.addProperty("bulk_files", false)
        config.addProperty("ask_format_after_download", false)
        config.addProperty("enable_audiobook", false)
        config.addProperty("enable_segment_comments", false)
        config.addProperty("allow_overwrite_files", true)
        config.addProperty("preferred_book_name_field", "book_name")
        postNativeJson("api/config/full", config)
        return original
    }

    private suspend fun runNativeDownload(
        info: TomatoBookInfo,
        onJobCreated: suspend (Long) -> Unit,
    ): NativeDownloadResult {
        val request = JsonObject().apply {
            addProperty("book_id", info.bookId)
        }
        val created = postNativeJson("api/jobs", request)
        val jobId = created.get("id")
            ?.takeIf { it.isJsonPrimitive }
            ?.asLong
            ?: throw IOException("本地下载服务未返回任务 ID")
        onJobCreated(jobId)

        while (true) {
            currentCoroutineContext().ensureActive()
            val jobs = getNativeJson("api/jobs?id=$jobId").get("items")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
            val job = jobs?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IOException("本地下载任务不存在")

            submitPendingNativeChoices(jobId, job)

            val progress = job.get("progress")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
            val saved = progress?.get("saved_chapters")
                ?.takeIf { it.isJsonPrimitive }
                ?.asInt ?: 0
            val reportedTotal = progress?.get("chapter_total")
                ?.takeIf { it.isJsonPrimitive }
                ?.asInt ?: 0
            val total = reportedTotal.takeIf { it > 0 } ?: info.chapters.size
            val state = job.get("state")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.lowercase()
                .orEmpty()
            val title = job.get("title")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
            val phase = if (total > 0 && saved >= total) {
                TomatoPhase.GENERATING
            } else {
                TomatoPhase.DOWNLOADING
            }
            _state.value = _state.value.copy(
                phase = phase,
                current = saved,
                total = total,
                currentChapter = if (saved > 0) {
                    info.chapters.getOrNull(saved - 1)?.title.orEmpty()
                } else {
                    ""
                },
            )

            when (state) {
                "done" -> return NativeDownloadResult(title, saved, total)
                "failed" -> {
                    val message = job.get("message")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        .orEmpty()
                    throw IOException(message.ifBlank { "番茄批量下载失败" })
                }
                "canceled" -> throw CancellationException("番茄批量下载已取消")
            }
            delay(NATIVE_JOB_POLL_INTERVAL_MS)
        }
    }

    private suspend fun submitPendingNativeChoices(
        jobId: Long,
        job: JsonObject,
    ) {
        job.get("book_name_options")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("value")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.let { value ->
                postNativeJson(
                    "api/jobs/$jobId/book_name",
                    JsonObject().apply { addProperty("value", value) },
                )
            }

        job.get("format_options")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { option -> option.takeIf { it.isJsonObject }?.asJsonObject }
            ?.firstOrNull { option ->
                option.get("value")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString == "epub"
            }
            ?.get("value")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.let { value ->
                postNativeJson(
                    "api/jobs/$jobId/format",
                    JsonObject().apply { addProperty("value", value) },
                )
            }
    }

    private suspend fun downloadNativeEpub(
        info: TomatoBookInfo,
        nativeTitle: String?,
    ): ByteArray {
        var library = getNativeJson("api/library?start=true")
        repeat(NATIVE_LIBRARY_SCAN_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val scanning = library.get("running")
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean
                .orFalse()
            if (!scanning) {
                val items = library.get("items")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { item -> item.takeIf { it.isJsonObject }?.asJsonObject }
                    .orEmpty()
                val epubItems = items.filter { item ->
                    item.get("kind")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString == "file" &&
                        item.get("ext")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString.equals("epub", ignoreCase = true)
                }
                val expectedNames = mutableSetOf("${sanitizeFileName(info.title)}.epub")
                nativeTitle?.takeIf(String::isNotBlank)?.let { title ->
                    expectedNames.add("${sanitizeFileName(title)}.epub")
                }
                val selected = epubItems.firstOrNull { item ->
                    val name = item.get("name")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        .orEmpty()
                    expectedNames.any { expected -> name.equals(expected, ignoreCase = true) }
                } ?: epubItems.maxByOrNull { item ->
                    item.get("modified_ms")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asLong ?: 0L
                } ?: throw IOException("批量下载完成，但未找到生成的 EPUB 文件")
                val relPath = selected.get("rel_path")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?: throw IOException("本地下载服务未返回 EPUB 路径")
                return getNativeFile(relPath)
            }
            if (attempt < NATIVE_LIBRARY_SCAN_ATTEMPTS - 1) {
                delay(NATIVE_LIBRARY_SCAN_INTERVAL_MS)
                library = getNativeJson("api/library?start=false")
            }
        }
        throw IOException("等待 EPUB 文件生成超时")
    }

    private suspend fun cancelNativeJob(jobId: Long) {
        postNativeJson("api/jobs/$jobId/cancel", JsonObject())
    }

    private suspend fun getNativeJson(path: String): JsonObject =
        executeNativeJson(Request.Builder().url(nativeUrl(path)).get().build())

    private suspend fun postNativeJson(
        path: String,
        body: JsonObject,
    ): JsonObject = executeNativeJson(
        Request.Builder()
            .url(nativeUrl(path))
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build(),
    )

    private suspend fun executeNativeJson(request: Request): JsonObject {
        val response = okHttpClient.newCall(request).await()
        return response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                val hint = if (it.code == 429) "，已有下载任务正在运行" else ""
                throw IOException("本地下载服务请求失败：HTTP ${it.code}$hint")
            }
            if (text.isBlank()) return@use JsonObject()
            val json = JsonParser.parseString(text)
            if (!json.isJsonObject) throw IOException("本地下载服务返回格式错误")
            json.asJsonObject
        }
    }

    private suspend fun getNativeFile(relPath: String): ByteArray {
        val url = TomatoWebUiService.WEB_URL.toHttpUrl().newBuilder()
            .addPathSegment("download")
            .apply {
                relPath.replace('\\', '/').split('/')
                    .filter(String::isNotBlank)
                    .forEach { segment -> addPathSegment(segment) }
            }
            .build()
        val response = okHttpClient.newCall(Request.Builder().url(url).get().build()).await()
        return response.use {
            if (!it.isSuccessful) throw IOException("读取生成的 EPUB 失败：HTTP ${it.code}")
            it.body.bytes().takeIf(ByteArray::isNotEmpty)
                ?: throw IOException("生成的 EPUB 文件为空")
        }
    }

    private fun nativeUrl(path: String) = TomatoWebUiService.WEB_URL.toHttpUrl()
        .resolve(path.removePrefix("/"))
        ?: throw IllegalArgumentException("无效的本地下载服务路径：$path")

    private fun Boolean?.orFalse(): Boolean = this == true

    private suspend fun downloadChapters(
        info: TomatoBookInfo,
    ): ChapterDownloadResult {
        val results = MutableList<String?>(info.chapters.size) { null }
        var failedCount = 0
        var firstError: Exception? = null
        info.chapters.forEachIndexed { index, chapter ->
            currentCoroutineContext().ensureActive()
            try {
                results[index] = fetchChapter(chapter, info.bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedCount++
                if (firstError == null) firstError = e
                if (failedCount <= MAX_LOGGED_CHAPTER_FAILURES) {
                    Log.e(LOG_TAG, "章节下载失败 id=${chapter.id} title=${chapter.title}", e)
                }
            }
            _state.value = _state.value.copy(
                phase = TomatoPhase.DOWNLOADING,
                current = index + 1,
                total = info.chapters.size,
                currentChapter = chapter.title,
                failedCount = failedCount,
            )
            if (index < info.chapters.lastIndex) delay(CHAPTER_REQUEST_INTERVAL_MS)
        }
        return ChapterDownloadResult(results, failedCount, firstError)
    }

    private suspend fun fetchChapter(chapter: TomatoChapter, bookId: String): String {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val html = getText(
                    "https://fanqienovel.com/reader/${chapter.id}",
                    referer = "https://fanqienovel.com/page/$bookId",
                )
                val contentHtml = extractChapterContent(html)
                    ?: throw IOException(
                        "章节页面无正文（响应 ${html.length} 字符，" +
                            "initialState=${html.contains(INITIAL_STATE_MARKER)}），可能触发网站限频",
                    )
                val paragraphs = Jsoup.parseBodyFragment(contentHtml).select("p").mapNotNull { paragraph ->
                    decodeContent(paragraph.text()).trim().takeIf { it.isNotEmpty() }
                }
                if (paragraphs.isEmpty()) throw IOException("章节正文为空")
                return paragraphs.joinToString("\n")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(CHAPTER_RETRY_DELAYS_MS[attempt])
            }
        }
        throw lastError ?: IOException("章节下载失败")
    }

    private fun extractChapterContent(html: String): String? {
        val document = Jsoup.parse(html)
        document.selectFirst(".muye-reader-content.noselect, .muye-reader-content")
            ?.html()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        val initialState = extractJsonObject(html, INITIAL_STATE_MARKER) ?: return null
        return runCatching {
            JsonParser.parseString(initialState)
                .asJsonObject
                .getAsJsonObject("reader")
                .getAsJsonObject("chapterData")
                .get("content")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    /**
     * Extracts a JSON object assigned in an inline script without relying on a greedy regex.
     * Braces inside JSON strings are ignored while locating the matching closing brace.
     */
    private fun extractJsonObject(source: String, marker: String): String? {
        val markerIndex = source.indexOf(marker)
        if (markerIndex < 0) return null
        val start = source.indexOf('{', markerIndex + marker.length)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until source.length) {
            val char = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun decodeContent(content: String): String = buildString(content.length) {
        content.forEach { char ->
            val index = char.code - CHARSET_START
            val replacement = charset.getOrNull(index)
            if (!replacement.isNullOrEmpty() && replacement != "?") append(replacement) else append(char)
        }
    }

    private suspend fun fetchCoverAsJpeg(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        val response = okHttpClient.newCall(request).await()
        val bytes = response.use {
            if (!it.isSuccessful) throw IOException("封面下载失败：HTTP ${it.code}")
            it.body.bytes()
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("封面图片格式不受支持")
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw IOException("封面图片处理失败")
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun buildEpub(
        info: TomatoBookInfo,
        contents: List<String?>,
        coverJpeg: ByteArray,
    ): ByteArray = withContext(Dispatchers.Default) {
        val epub = EpubBook().apply { version = "2.0" }
        epub.metadata = Metadata().apply {
            titles.add(info.title)
            authors.add(Author(info.author))
            language = "zh-CN"
            dates.add(Date())
            publishers.add("Legado")
            descriptions.add(info.description)
        }

        val css = Resource(EPUB_CSS.toByteArray(Charsets.UTF_8), "Styles/main.css")
        epub.resources.add(css)
        val coverImage = Resource(coverJpeg, "Images/cover.jpg")
        epub.coverImage = coverImage
        val coverPage = Resource(
            coverPage(info).toByteArray(Charsets.UTF_8),
            "Text/cover.xhtml",
        )
        epub.addSection("封面", coverPage)
        epub.coverPage = coverPage

        if (info.description.isNotBlank()) {
            epub.addSection(
                "简介",
                Resource(introPage(info).toByteArray(Charsets.UTF_8), "Text/intro.xhtml"),
            )
        }

        info.chapters.forEachIndexed { index, chapter ->
            val content = contents[index]
            val page = if (content == null) {
                chapterPage(chapter.title, listOf("本章下载失败，可稍后重新下载本书。"))
            } else {
                chapterPage(chapter.title, content.lines())
            }
            epub.addSection(
                chapter.title,
                Resource(page.toByteArray(Charsets.UTF_8), "Text/chapter_${index + 1}.xhtml"),
            )
        }

        ByteArrayOutputStream().use { output ->
            EpubWriter().write(epub, output)
            output.toByteArray()
        }
    }

    private fun coverPage(info: TomatoBookInfo) = xhtmlPage(
        info.title,
        """<div class="cover"><img src="../Images/cover.jpg" alt="${escapeXml(info.title)}"/></div>""",
    )

    private fun introPage(info: TomatoBookInfo) = xhtmlPage(
        "简介",
        """<h1>${escapeXml(info.title)}</h1><p class="author">作者：${escapeXml(info.author)}</p><h2>简介</h2><p>${escapeXml(info.description)}</p>""",
    )

    private fun chapterPage(title: String, paragraphs: List<String>): String {
        val body = buildString {
            append("<h1>").append(escapeXml(title)).append("</h1>")
            paragraphs.filter { it.isNotBlank() }.forEach { line ->
                append("<p>").append(escapeXml(line.trim())).append("</p>")
            }
        }
        return xhtmlPage(title, body)
    }

    private fun xhtmlPage(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${escapeXml(title)}</title><link rel="stylesheet" type="text/css" href="../Styles/main.css"/></head><body>$body</body></html>"""

    private suspend fun saveAndImport(info: TomatoBookInfo, bytes: ByteArray): Book {
        val fileName = "${sanitizeFileName(info.title)}.epub"
        val uri = LocalBook.saveBookFile(ByteArrayInputStream(bytes), fileName)
        return LocalBook.importFile(uri).apply {
            name = info.title
            author = info.author
            intro = info.description
            tocUrl = "https://fanqienovel.com/page/${info.bookId}"
            canUpdate = false
            save()
        }
    }

    private suspend fun getText(
        url: String,
        referer: String? = null,
        acceptJson: Boolean = false,
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header(cookieJarHeader, "1")
            .header("Accept", if (acceptJson) "application/json, text/plain, */*" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        referer?.let { builder.header("Referer", it) }
        val response = okHttpClient.newCall(builder.get().build()).await()
        return response.use {
            if (!it.isSuccessful) throw IOException("网络请求失败：HTTP ${it.code}")
            it.body.string().takeIf(String::isNotBlank) ?: throw IOException("服务器返回空内容")
        }
    }

    private fun findString(
        element: JsonElement,
        keys: Set<String>,
        authorObject: Boolean = false,
    ): String? {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            obj.entrySet().forEach { (key, value) ->
                if (key in keys) {
                    if (value.isJsonPrimitive) return value.asString
                    if (authorObject && value.isJsonArray) {
                        value.asJsonArray.firstOrNull()?.takeIf { it.isJsonObject }
                            ?.asJsonObject?.get("name")?.takeIf { it.isJsonPrimitive }
                            ?.asString?.let { return it }
                    }
                    if (value.isJsonArray) {
                        value.asJsonArray.firstOrNull()?.takeIf { it.isJsonPrimitive }
                            ?.asString?.let { return it }
                    }
                }
            }
            obj.entrySet().forEach { findString(it.value, keys, authorObject)?.let { found -> return found } }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { findString(it, keys, authorObject)?.let { found -> return found } }
        }
        return null
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "番茄小说" }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        internal val sharedState = MutableStateFlow(TomatoDownloadState())
        private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var workJob: Job? = null

        internal fun cancelActiveDownload() {
            workJob?.cancel()
        }

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val INITIAL_STATE_MARKER = "window.__INITIAL_STATE__="
        private const val CHARSET_START = 58344
        private const val CHAPTER_REQUEST_INTERVAL_MS = 1_000L
        private val CHAPTER_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L)
        private const val MAX_LOGGED_CHAPTER_FAILURES = 5
        private const val NATIVE_START_ATTEMPTS = 80
        private const val NATIVE_START_INTERVAL_MS = 250L
        private const val NATIVE_JOB_POLL_INTERVAL_MS = 500L
        private const val NATIVE_LIBRARY_SCAN_ATTEMPTS = 40
        private const val NATIVE_LIBRARY_SCAN_INTERVAL_MS = 250L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val LOG_TAG = "TomatoDownload"
        private val URL_REGEX = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        private val QUERY_ID_REGEX = Regex("(?:book_id|bookId)[=\\\":]+(\\d{10,})", RegexOption.IGNORE_CASE)
        private val PAGE_ID_REGEX = Regex("/page/(\\d{10,})", RegexOption.IGNORE_CASE)
        private val ALLOWED_HOSTS = setOf("fanqienovel.com", "fqnovel.com", "changdunovel.com")
        private const val EPUB_CSS = """
body { font-family: serif; line-height: 1.8; margin: 5%; }
h1 { font-size: 1.35em; text-align: center; margin: 1.5em 0; }
h2 { font-size: 1.15em; margin-top: 1.5em; }
p { text-indent: 2em; margin: 0.65em 0; }
.author { text-align: center; text-indent: 0; color: #666; }
.cover { height: 95vh; display: flex; align-items: center; justify-content: center; text-align: center; }
.cover img { max-width: 100%; max-height: 95vh; }
"""
    }
}

data class TomatoBookInfo(
    val bookId: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String,
    val chapters: List<TomatoChapter>,
)

data class TomatoChapter(
    val id: String,
    val title: String,
    val volume: String,
)

private data class ChapterDownloadResult(
    val contents: List<String?>,
    val failedCount: Int,
    val firstError: Exception?,
)

private data class NativeDownloadResult(
    val title: String?,
    val savedCount: Int,
    val totalCount: Int,
)

data class TomatoDownloadState(
    val phase: TomatoPhase = TomatoPhase.IDLE,
    val bookInfo: TomatoBookInfo? = null,
    val current: Int = 0,
    val total: Int = 0,
    val currentChapter: String = "",
    val failedCount: Int = 0,
    val error: String? = null,
    val localBook: Book? = null,
)

enum class TomatoPhase {
    IDLE,
    RESOLVING,
    READY,
    DOWNLOADING,
    GENERATING,
    COMPLETE,
    ERROR,
}
