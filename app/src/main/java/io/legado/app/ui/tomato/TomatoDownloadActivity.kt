package io.legado.app.ui.tomato

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityTomatoDownloadBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class TomatoDownloadActivity :
    VMBaseActivity<ActivityTomatoDownloadBinding, TomatoDownloadViewModel>() {

    override val binding by viewBinding(ActivityTomatoDownloadBinding::inflate)
    override val viewModel by viewModels<TomatoDownloadViewModel>()

    private var currentCoverUrl: String? = null
    private var pendingDownload = false

    private val selectSearchBook = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        result.data?.getStringExtra(TomatoSearchActivity.EXTRA_BOOK_ID)?.let { bookId ->
            binding.etBookInput.setText(bookId)
            binding.inputLayout.error = null
            viewModel.resolve(bookId)
        }
    }

    private val selectBookFolder = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            AppConfig.defaultBookTreeUri = uri.toString()
            updateSavePath()
            if (pendingDownload) viewModel.download()
        }
        pendingDownload = false
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.btnOpenWebui.setOnClickListener {
            TomatoWebUiService.startAndOpen(this)
        }
        binding.btnParse.setOnClickListener {
            val input = binding.etBookInput.text?.toString().orEmpty().trim()
            if (input.isBlank()) {
                binding.inputLayout.error = getString(R.string.tomato_input_required)
                return@setOnClickListener
            }
            binding.inputLayout.error = null
            if (isBookReference(input)) {
                viewModel.resolve(input)
            } else {
                selectSearchBook.launch(TomatoSearchActivity.createIntent(this, input))
            }
        }
        binding.btnSelectFolder.setOnClickListener {
            pendingDownload = false
            launchFolderPicker()
        }
        binding.btnDownload.setOnClickListener {
            val state = viewModel.state.value
            when (state.phase) {
                TomatoPhase.COMPLETE -> state.localBook?.let(::startActivityForBook)
                TomatoPhase.ERROR, TomatoPhase.READY -> startDownload()
                else -> Unit
            }
        }
        binding.btnCancel.setOnClickListener {
            viewModel.cancel()
            toastOnUi(R.string.tomato_cancelled)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::renderState)
            }
        }
        updateSavePath()
    }

    private fun startDownload() {
        if (AppConfig.defaultBookTreeUri.isNullOrBlank()) {
            pendingDownload = true
            toastOnUi(R.string.tomato_storage_required)
            launchFolderPicker()
        } else {
            viewModel.download()
        }
    }

    private fun launchFolderPicker() {
        selectBookFolder.launch {
            title = getString(R.string.select_book_folder)
            mode = HandleFileContract.DIR_SYS
        }
    }

    private fun isBookReference(input: String): Boolean {
        return input.all(Char::isDigit) || URL_REGEX.containsMatchIn(input)
    }

    private fun updateSavePath() {
        val uriString = AppConfig.defaultBookTreeUri
        binding.tvSavePath.text = if (uriString.isNullOrBlank()) {
            getString(R.string.tomato_folder_not_selected)
        } else {
            runCatching { RealPathUtil.getTreePath(Uri.parse(uriString)) }
                .getOrNull()
                .orEmpty()
                .ifBlank { uriString }
        }
    }

    private fun renderState(state: TomatoDownloadState) {
        val active = state.phase in setOf(
            TomatoPhase.RESOLVING,
            TomatoPhase.DOWNLOADING,
            TomatoPhase.GENERATING,
        )
        binding.etBookInput.isEnabled = !active
        binding.btnParse.isEnabled = !active
        binding.btnSelectFolder.isEnabled = !active
        binding.btnCancel.visibility = if (active) View.VISIBLE else View.GONE

        val info = state.bookInfo
        binding.bookCard.visibility = if (info == null) View.GONE else View.VISIBLE
        if (info != null) {
            binding.tvBookName.text = info.title
            binding.tvAuthor.text = getString(R.string.author) + "：" + info.author
            binding.tvChapterCount.text = getString(R.string.tomato_chapter_count, info.chapters.size)
            binding.tvIntro.text = info.description
            if (currentCoverUrl != info.coverUrl) {
                currentCoverUrl = info.coverUrl
                Glide.with(this)
                    .load(info.coverUrl)
                    .placeholder(R.drawable.bg_tomato_cover_placeholder)
                    .error(R.drawable.bg_tomato_cover_placeholder)
                    .into(binding.ivCover)
            }
        }

        binding.progressBar.visibility = if (active) View.VISIBLE else View.GONE
        when (state.phase) {
            TomatoPhase.RESOLVING, TomatoPhase.GENERATING -> {
                binding.progressBar.isIndeterminate = true
            }
            TomatoPhase.DOWNLOADING -> {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.max = state.total.coerceAtLeast(1)
                binding.progressBar.progress = state.current
            }
            else -> Unit
        }

        binding.tvStatus.text = when (state.phase) {
            TomatoPhase.IDLE, TomatoPhase.READY -> ""
            TomatoPhase.RESOLVING -> getString(R.string.tomato_resolving)
            TomatoPhase.DOWNLOADING -> getString(
                R.string.tomato_downloading,
                state.current,
                state.total,
                state.currentChapter,
            )
            TomatoPhase.GENERATING -> getString(R.string.tomato_generating_epub)
            TomatoPhase.COMPLETE -> if (state.failedCount == 0) {
                getString(R.string.tomato_download_complete)
            } else {
                getString(R.string.tomato_download_complete_with_failures, state.failedCount)
            }
            TomatoPhase.ERROR -> state.error.orEmpty()
        }

        val showDownload = info != null && state.phase in setOf(
            TomatoPhase.READY,
            TomatoPhase.COMPLETE,
            TomatoPhase.ERROR,
        )
        binding.btnDownload.visibility = if (showDownload) View.VISIBLE else View.GONE
        binding.btnDownload.text = when (state.phase) {
            TomatoPhase.COMPLETE -> getString(R.string.start_read)
            TomatoPhase.ERROR -> getString(R.string.retry)
            else -> getString(R.string.tomato_download_epub)
        }
    }

    companion object {
        private val URL_REGEX = Regex("https?://", RegexOption.IGNORE_CASE)
    }
}
