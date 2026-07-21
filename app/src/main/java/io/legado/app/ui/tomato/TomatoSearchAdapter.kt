package io.legado.app.ui.tomato

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.databinding.ItemTomatoSearchBookBinding

class TomatoSearchAdapter(
    private val onClick: (TomatoSearchBook) -> Unit,
) : RecyclerView.Adapter<TomatoSearchAdapter.ViewHolder>() {

    private val items = mutableListOf<TomatoSearchBook>()

    fun submitList(books: List<TomatoSearchBook>) {
        items.clear()
        items.addAll(books)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTomatoSearchBookBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemTomatoSearchBookBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(book: TomatoSearchBook) {
            val context = binding.root.context
            binding.tvBookName.text = book.title
            binding.tvAuthor.text = context.getString(R.string.author) + "：" +
                book.author.ifBlank { context.getString(R.string.tomato_unknown_author) }
            binding.tvMeta.text = listOfNotNull(
                book.category.takeIf(String::isNotBlank),
                book.score.takeIf(String::isNotBlank)?.let {
                    context.getString(R.string.tomato_search_score, it)
                },
            ).joinToString(" · ")
            binding.tvMeta.visibility = if (binding.tvMeta.text.isBlank()) View.GONE else View.VISIBLE
            binding.tvIntro.text = book.description
            binding.tvIntro.visibility = if (book.description.isBlank()) View.GONE else View.VISIBLE
            Glide.with(binding.ivCover)
                .load(book.coverUrl)
                .placeholder(R.drawable.bg_tomato_cover_placeholder)
                .error(R.drawable.bg_tomato_cover_placeholder)
                .into(binding.ivCover)
            binding.root.setOnClickListener { onClick(book) }
        }
    }
}
