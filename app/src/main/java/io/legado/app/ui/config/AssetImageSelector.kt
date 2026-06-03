package io.legado.app.ui.config

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemBgImageBinding
import io.legado.app.help.AssetsImageHelp
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.utils.dpToPx

fun PreferenceFragment.showAssetImageSelector(
    assetDir: String,
    title: String,
    onSelected: (assetName: String) -> Unit
) {
    val images = AssetsImageHelp.list(assetDir)
    if (images.isEmpty()) {
        return
    }
    lateinit var dialog: AlertDialog
    val recyclerView = RecyclerView(requireContext()).apply {
        layoutManager = GridLayoutManager(requireContext(), 3)
        setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
        clipToPadding = false
        adapter = AssetImageAdapter(requireContext(), assetDir) { assetName ->
            onSelected(assetName)
            dialog.dismiss()
        }.apply {
            setItems(images)
        }
    }
    val contentView = FrameLayout(requireContext()).apply {
        addView(
            recyclerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                360.dpToPx()
            )
        )
    }
    dialog = requireContext().alert(title = title) {
        customView { contentView }
        cancelButton()
    }
}

private class AssetImageAdapter(
    context: Context,
    private val assetDir: String,
    private val onSelected: (assetName: String) -> Unit
) : RecyclerAdapter<String, ItemBgImageBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBgImageBinding {
        return ItemBgImageBinding.inflate(inflater, parent, false).apply {
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                128.dpToPx()
            )
            root.setPadding(6.dpToPx(), 4.dpToPx(), 6.dpToPx(), 4.dpToPx())
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBgImageBinding,
        item: String,
        payloads: MutableList<Any>
    ) {
        binding.run {
            ImageLoader.load(
                context,
                context.assets.open("$assetDir/$item").readBytes()
            )
                .centerCrop()
                .into(ivBg)
            tvName.text = AssetsImageHelp.displayName(item)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBgImageBinding) {
        holder.itemView.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let(onSelected)
        }
    }
}
