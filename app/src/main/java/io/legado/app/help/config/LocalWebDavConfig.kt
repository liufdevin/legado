package io.legado.app.help.config

import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

object LocalWebDavConfig {

    private const val configFileName = "local_webdav_config.json"

    fun applyIfBlank(): Boolean {
        val config = readConfig()
        var changed = false

        fun putIfBlank(key: String, value: String?) {
            val trimmedValue = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (appCtx.getPrefString(key).isNullOrBlank()) {
                appCtx.putPrefString(key, trimmedValue)
                changed = true
            }
        }

        putIfBlank(PreferKey.webDavUrl, config["url"])
        putIfBlank(PreferKey.webDavAccount, config["account"])
        putIfBlank(PreferKey.webDavPassword, config["password"])
        putIfBlank(PreferKey.webDavDir, config["dir"])
        putIfBlank(PreferKey.webDavDeviceName, config["deviceName"])

        return changed
    }

    private fun readConfig(): Map<String, String?> {
        return runCatching {
            appCtx.assets.open(configFileName).use {
                GSON.fromJsonObject<Map<String, String?>>(it).getOrNull()
            }
        }.getOrNull() ?: emptyMap()
    }

}
