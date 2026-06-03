package io.legado.app.help

import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

object AssetsImageHelp {

    const val APP_BG = "app_bg"
    const val COVER_BG = "cover_bg"
    const val LAUNCHER_BG = "launcher_bg"
    const val READ_BG = "bg"

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun list(assetDir: String): List<String> {
        return appCtx.assets.list(assetDir)
            ?.filter { it.substringAfterLast(".", "").lowercase() in imageExtensions }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: emptyList()
    }

    fun hasImages(assetDir: String): Boolean {
        return list(assetDir).isNotEmpty()
    }

    fun displayName(fileName: String): String {
        return fileName.substringBeforeLast(".").replace('_', ' ')
    }

    fun copyToExternal(assetDir: String, assetName: String, outDir: String): File {
        val file = FileUtils.createFileIfNotExist(appCtx.externalFiles, outDir, assetName)
        appCtx.assets.open("$assetDir/$assetName").use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }
}
