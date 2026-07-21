package io.legado.app.ui.tomato

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityTomatoSearchBinding
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

class TomatoSearchActivity : BaseActivity<ActivityTomatoSearchBinding>() {

    override val binding by viewBinding(ActivityTomatoSearchBinding::inflate)

    private val adapter = TomatoSearchAdapter(::selectBook)
    private var searchJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnSearch.setOnClickListener { startSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch()
                true
            } else {
                false
            }
        }

        intent.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf(String::isNotBlank)?.let { query ->
            binding.etSearch.setText(query)
            binding.etSearch.setSelection(query.length)
            startSearch()
        }
    }

    private fun startSearch() {
        val query = binding.etSearch.text?.toString().orEmpty().trim()
        if (query.isBlank()) {
            binding.inputLayout.error = getString(R.string.tomato_search_input_required)
            return
        }
        binding.inputLayout.error = null
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            showLoading(true)
            adapter.submitList(emptyList())
            try {
                val books = searchBooks(query)
                adapter.submitList(books)
                binding.tvStatus.text = if (books.isEmpty()) {
                    getString(R.string.tomato_search_empty)
                } else {
                    getString(R.string.tomato_search_result_count, books.size)
                }
            } catch (e: Exception) {
                binding.tvStatus.text = getString(
                    R.string.tomato_search_failed,
                    e.message ?: getString(R.string.unknown_error),
                )
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSearch.isEnabled = !loading
        binding.etSearch.isEnabled = !loading
        if (loading) binding.tvStatus.setText(R.string.tomato_searching)
    }

    private suspend fun searchBooks(query: String): List<TomatoSearchBook> =
        withContext(Dispatchers.IO) {
            val pages = coroutineScope {
                SEARCH_OFFSETS.map { offset ->
                    async { runCatching { requestSearchPage(query, offset) } }
                }.awaitAll()
            }
            val books = pages.flatMap { it.getOrDefault(emptyList()) }
                .distinctBy(TomatoSearchBook::bookId)
            if (books.isEmpty() && pages.all { it.isFailure }) {
                throw pages.firstNotNullOf { it.exceptionOrNull() }
            }
            rankResults(books, query)
        }

    private suspend fun requestSearchPage(query: String, offset: Int): List<TomatoSearchBook> {
        val url = SEARCH_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("aid", "1967")
            .addQueryParameter("q", query)
            .addQueryParameter("offset", offset.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()
        val text = okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string().takeIf(String::isNotBlank)
                ?: throw IOException("服务器返回空内容")
        }
        val root = JsonParser.parseString(text).asJsonObject
        if (root.string("message") != "success") {
            throw IOException(root.string("message").ifBlank { "搜索接口返回错误" })
        }
        val items = root.getAsJsonObject("data")?.getAsJsonArray("ret_data")
            ?: return emptyList()
        return items.mapIndexedNotNull { index, element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val bookId = item.string("book_id")
            val title = item.string("title").ifBlank { item.string("book_name") }
            if (bookId.isBlank() || title.isBlank()) return@mapIndexedNotNull null
            TomatoSearchBook(
                bookId = bookId,
                title = title,
                author = item.string("author"),
                coverUrl = item.string("thumb_url").ifBlank { item.string("cover_url") },
                description = item.string("abstract"),
                category = item.string("category"),
                score = item.string("score"),
                sourceIndex = offset + index,
            )
        }
    }

    private fun rankResults(
        books: List<TomatoSearchBook>,
        query: String,
    ): List<TomatoSearchBook> {
        val normalizedQuery = normalize(query)
        return books.sortedWith(
            compareBy<TomatoSearchBook> { book ->
                val title = normalize(book.title)
                val author = normalize(book.author)
                when {
                    title == normalizedQuery -> 0
                    title.startsWith(normalizedQuery) -> 1
                    title.contains(normalizedQuery) -> 2
                    author == normalizedQuery -> 3
                    author.startsWith(normalizedQuery) -> 4
                    author.contains(normalizedQuery) -> 5
                    else -> 6
                }
            }.thenBy(TomatoSearchBook::sourceIndex),
        )
    }

    private fun normalize(text: String): String {
        val simplified = runCatching { ChineseUtils.t2s(text) }.getOrDefault(text)
        return simplified.lowercase().filter(Char::isLetterOrDigit)
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun selectBook(book: TomatoSearchBook) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_BOOK_ID, book.bookId))
        finish()
    }

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_QUERY = "query"
        private const val SEARCH_ENDPOINT =
            "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
        private val SEARCH_OFFSETS = listOf(0, 10, 20)

        fun createIntent(context: Context, query: String): Intent {
            return Intent(context, TomatoSearchActivity::class.java)
                .putExtra(EXTRA_QUERY, query)
        }
    }
}

data class TomatoSearchBook(
    val bookId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val description: String,
    val category: String,
    val score: String,
    val sourceIndex: Int,
)
