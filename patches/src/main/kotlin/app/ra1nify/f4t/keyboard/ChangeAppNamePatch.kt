@file:Suppress("DEPRECATION")

package app.ra1nify.f4t.keyboard

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.listDecodableFiles
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.stringOption
import app.ra1nify.f4t.shared.OPLUS_KEYBOARD

/**
 * Renames the keyboard in the launcher and in the input method picker.
 *
 * The name is independent of the package name: a patched build that coexists with the stock keyboard
 * needs its own label, otherwise the two entries are indistinguishable in the settings. The label is
 * a resource of every locale, so it is changed where it is declared instead of hiding the stock
 * keyboard from the picker.
 */
@Suppress("unused")
val changeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Changes the display name of the patched keyboard in every locale, so the patched " +
        "build can be told apart from the stock keyboard. Defaults to the F4T release name.",
    default = false,
) {
    compatibleWith(OPLUS_KEYBOARD)

    val appNameEn = stringOption(
        key = "appNameEn",
        default = "真奇喵输入法",
        title = "App name (en-US)",
        description = "Name of the patched keyboard in the default and English locales.",
        required = true,
        validator = { !it.isNullOrBlank() },
    )

    val appNameZhCn = stringOption(
        key = "appNameZhCn",
        default = "真奇喵输入法",
        title = "App name (zh-CN)",
        description = "Name of the patched keyboard in the simplified Chinese locales.",
        required = true,
        validator = { !it.isNullOrBlank() },
    )

    val appNameZhHk = stringOption(
        key = "appNameZhHk",
        default = "真奇喵输入法",
        title = "App name (zh-HK)",
        description = "Name of the patched keyboard in the traditional Chinese locales (zh-HK, zh-TW).",
        required = true,
        validator = { !it.isNullOrBlank() },
    )

    execute {
        val nameEn = appNameEn.value ?: throw PatchException("the en-US app name is not set")
        val nameZhCn = appNameZhCn.value ?: throw PatchException("the zh-CN app name is not set")
        val nameZhHk = appNameZhHk.value ?: throw PatchException("the zh-HK app name is not set")

        renameAppNames(nameEn, nameZhCn, nameZhHk)

        println("[keyboard] app name updated (en=\"$nameEn\", zh-CN=\"$nameZhCn\", zh-HK=\"$nameZhHk\")")
    }
}

private fun renameAppNames(nameEn: String, nameZhCn: String, nameZhHk: String) {
    val valuesFiles = listDecodableFiles("res/values*/strings.xml")
    if (valuesFiles.isEmpty()) {
        throw PatchException("no res/values*/strings.xml file could be decoded")
    }

    var renamed = 0
    valuesFiles.forEach { path ->
        val configuration = path.removePrefix("res/values").removeSuffix("/strings.xml")
        val name = when {
            configuration.startsWith("-zh-rHK") ||
                configuration.startsWith("-zh-rTW") ||
                configuration.startsWith("-zh-rMO") -> nameZhHk

            configuration.startsWith("-zh") -> nameZhCn

            else -> nameEn
        }

        resourceXml(path).use { strings ->
            val resource = strings.resourceOrNull("app_name") ?: return@use
            if (resource.text != name) {
                resource.text = name
                renamed += 1
                println("[keyboard] app_name in $path -> $name")
            }
        }
    }

    if (renamed == 0) {
        throw PatchException("no app_name resource could be renamed")
    }
}
