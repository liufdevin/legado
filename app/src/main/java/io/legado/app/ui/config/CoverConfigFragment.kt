package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.AssetsImageHelp
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.BookCover
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

class CoverConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val builtInImage = "内置图片"
    private val requestCodeCover = 111
    private val requestCodeCoverDark = 112
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestCodeCover -> setCoverFromUri(PreferKey.defaultCover, uri)
                requestCodeCoverDark -> setCoverFromUri(PreferKey.defaultCoverDark, uri)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_cover)
        upPreferenceSummary(PreferKey.defaultCover, getPrefString(PreferKey.defaultCover))
        upPreferenceSummary(PreferKey.defaultCoverDark, getPrefString(PreferKey.defaultCoverDark))
        findPreference<SwitchPreference>(PreferKey.coverShowAuthor)
            ?.isEnabled = getPrefBoolean(PreferKey.coverShowName)
        findPreference<SwitchPreference>(PreferKey.coverShowAuthorN)
            ?.isEnabled = getPrefBoolean(PreferKey.coverShowNameN)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.cover_config)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> {
                upPreferenceSummary(key, getPrefString(key))
            }

            PreferKey.coverShowName -> {
                findPreference<SwitchPreference>(PreferKey.coverShowAuthor)
                    ?.isEnabled = getPrefBoolean(key)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowNameN -> {
                findPreference<SwitchPreference>(PreferKey.coverShowAuthorN)
                    ?.isEnabled = getPrefBoolean(key)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowAuthor,
            PreferKey.coverShowAuthorN -> {
                BookCover.upDefaultCover()
            }
        }
    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "coverRule" -> showDialogFragment(CoverRuleConfigDialog())
            PreferKey.defaultCover -> selectCoverAction(preference.key, requestCodeCover)
            PreferKey.defaultCoverDark -> selectCoverAction(preference.key, requestCodeCoverDark)
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun selectCoverAction(preferenceKey: String, requestCode: Int) {
        val actions = arrayListOf<String>()
        if (!getPrefString(preferenceKey).isNullOrEmpty()) {
            actions.add(getString(R.string.delete))
        }
        if (AssetsImageHelp.hasImages(AssetsImageHelp.COVER_BG)) {
            actions.add(builtInImage)
        }
        actions.add(getString(R.string.select_image))
        context?.selector(actions) { _, action, _ ->
            when (action) {
                getString(R.string.delete) -> {
                    removePref(preferenceKey)
                    BookCover.upDefaultCover()
                }

                builtInImage -> selectAssetCover(preferenceKey)

                getString(R.string.select_image) -> {
                    selectImage.launch {
                        this.requestCode = requestCode
                        mode = HandleFileContract.IMAGE
                    }
                }
            }
        }
    }

    private fun selectAssetCover(preferenceKey: String) {
        showAssetImageSelector(AssetsImageHelp.COVER_BG, builtInImage) { assetName ->
            val file = AssetsImageHelp.copyToExternal(
                AssetsImageHelp.COVER_BG,
                assetName,
                "covers"
            )
            putPrefString(preferenceKey, file.absolutePath)
            upPreferenceSummary(preferenceKey, file.absolutePath)
            BookCover.upDefaultCover()
            toastOnUi("设定成功")
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> preference.summary = if (value.isNullOrBlank()) {
                getString(R.string.select_image)
            } else {
                value
            }

            else -> preference.summary = value
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                putPrefString(preferenceKey, file.absolutePath)
                BookCover.upDefaultCover()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}
