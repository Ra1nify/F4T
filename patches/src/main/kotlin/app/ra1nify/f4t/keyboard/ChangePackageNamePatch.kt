@file:Suppress("DEPRECATION")

package app.ra1nify.f4t.keyboard

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.ra1nify.f4t.shared.OPLUS_KEYBOARD
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val STOCK_PACKAGE = "com.oplus.keyboard"
private const val DEV_PACKAGE = "com.oplusdev.keyboard"
private const val RECEIVER_PERMISSION = "$STOCK_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
private const val IME_SERVICE = "$STOCK_PACKAGE.service.ImeService"
private const val MANIFEST = "AndroidManifest.xml"
private const val CONTENT_URI_PREFIX = "content://$STOCK_PACKAGE."

private val PACKAGE_NAME_PATTERN = Regex("""[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+""")

/**
 * Renames the host to a package of the caller's choice and repairs everything the rename breaks.
 *
 * The stock keyboard can be uninstalled, but the OPPO stack treats it as a privileged component, so a
 * patched rebuild has to be installed under a second package name. Renaming the manifest alone leaves
 * the app pointing at providers, authorities, a receiver permission and content:// URIs of the stock
 * package, so those are rewritten too. The IME service is exported because the input method manager
 * does not bind a service of a non-system package that is not exported.
 */
@Suppress("unused")
val changePackageNamePatch = resourcePatch(
    name = "Change package name",
    description = "Renames the package so the patched keyboard can be installed next to the stock " +
        "one, and keeps what the rename would otherwise break: provider authorities, the dynamic " +
        "receiver permission, the exported IME service and the hardcoded content:// authorities.",
    default = false,
) {
    compatibleWith(OPLUS_KEYBOARD)

    // The anti-resale notice is not optional: every keyboard patch pulls it in.
    dependsOn(keyboardAntiResalePatch)

    val targetPackage = stringOption(
        key = "packageName",
        default = "com.realme.keyboard",
        title = "Target package name",
        description = "Package name of the patched keyboard. Must differ from \"$STOCK_PACKAGE\" and must " +
            "not contain leading/trailing whitespace.",
        required = true,
        validator = { value ->
            value != null &&
                value == value.trim() &&
                !value.equals(STOCK_PACKAGE, ignoreCase = true) &&
                PACKAGE_NAME_PATTERN.matches(value)
        },
    )


    execute {
        val packageName = targetPackage.value ?: throw PatchException("the target package name is not set")
        // The option validator rejects these values and the patcher would silently fall back to the
        // default, so re-check here to fail closed instead of renaming to something unexpected.
        if (packageName != packageName.trim() || packageName.equals(STOCK_PACKAGE, ignoreCase = true)) {
            throw PatchException(
                "the target package name \"$packageName\" must differ from \"$STOCK_PACKAGE\" and must not " +
                    "contain leading/trailing whitespace"
            )
        }
        renameManifest(packageName)
        renameHardcodedAuthorities(packageName)
        enableInlineSuggestions()

        println("[keyboard] package renamed to $packageName")
    }
}

private fun renameManifest(targetPackage: String) {
    var providers = 0
    var permissions = 0
    var imeServices = 0

    resourceXml(MANIFEST).use { manifest ->
        val root = manifest.root
        if (root.tag != "manifest") {
            throw PatchException("$MANIFEST has root element \"${root.tag}\", expected \"manifest\"")
        }

        val declaredPackage = root.attribute("package")
        if (declaredPackage != STOCK_PACKAGE) {
            throw PatchException(
                "$MANIFEST declares package \"$declaredPackage\", expected \"$STOCK_PACKAGE\". " +
                    "The APK is not the stock keyboard or it was renamed already."
            )
        }
        root.setAttribute("package", targetPackage)

        // The cloud configuration sync reads the version code of the stock package. Package visibility
        // hides an installed package from a renamed and re-signed APK, so without this entry the sync
        // throws NameNotFoundException and the HMAC keys are never fetched (all X-Sign requests fail).
        val queries = manifest.elements("queries").firstOrNull() ?: root.appendChild("queries")
        if (queries.elements("package").none { element -> element.attribute("android:name") == STOCK_PACKAGE }) {
            queries.appendChild("package") { setAttribute("android:name", STOCK_PACKAGE) }
            println("[keyboard] manifest: <queries> visibility for $STOCK_PACKAGE")
        }

        manifest.elements().forEach { element ->
            when (element.tag) {
                "provider" -> element.attribute("android:authorities")?.let { authorities ->
                    val renamed = authorities.split(';').joinToString(";") { authority ->
                        if (authority.startsWith("$STOCK_PACKAGE.")) {
                            targetPackage + authority.removePrefix(STOCK_PACKAGE)
                        } else {
                            authority
                        }
                    }
                    if (renamed != authorities) {
                        element.setAttribute("android:authorities", renamed)
                        providers += 1
                    }
                }

                "permission", "uses-permission" -> element.attribute("android:name")?.let { name ->
                    if (name == RECEIVER_PERMISSION) {
                        element.setAttribute("android:name", receiverPermission(targetPackage))
                        permissions += 1
                    }
                }

                "service" -> element.attribute("android:name")?.let { name ->
                    if (name == IME_SERVICE || (name.startsWith("$STOCK_PACKAGE.") && name.endsWith(".service.ImeService"))) {
                        element.setAttribute("android:exported", "true")
                        imeServices += 1
                    }
                }
            }

            element.attribute("android:permission")?.let { permission ->
                if (permission == RECEIVER_PERMISSION) {
                    element.setAttribute("android:permission", receiverPermission(targetPackage))
                    permissions += 1
                }
            }
        }

        // Several internal pages are opened with an action built from the package name, e.g.
        // `"<own package name>" + ".action.selectkeybaord"`. The keyboard also has a development
        // build whose package name is `com.oplusdev.keyboard`; when the app itself runs under that
        // name, every internal page action switches to the dev prefix. The stock manifest only
        // declares the stock actions, so under the dev package every internal navigation throws
        // ActivityNotFoundException and the keyboard hides itself. Add the dev aliases so the
        // internal pages resolve again.
        if (targetPackage == DEV_PACKAGE) {
            val filters = manifest.elements("intent-filter").toList()
            var actionAliases = 0
            filters.forEach { filter ->
                val actions = filter.elements("action").toList()
                val existing = actions.mapNotNullTo(mutableSetOf()) { action -> action.attribute("android:name") }
                val aliases = actions.mapNotNull { action ->
                    val name = action.attribute("android:name") ?: return@mapNotNull null
                    if (!name.startsWith("$STOCK_PACKAGE.")) return@mapNotNull null
                    val alias = DEV_PACKAGE + name.removePrefix(STOCK_PACKAGE)
                    if (existing.add(alias)) alias else null
                }
                aliases.forEach { alias ->
                    filter.appendChild("action") { setAttribute("android:name", alias) }
                    actionAliases += 1
                }
            }
            if (actionAliases == 0) {
                throw PatchException(
                    "$MANIFEST declares no \"$STOCK_PACKAGE.\" action that could be aliased for " +
                        "$DEV_PACKAGE; the internal pages of the dev build would stay unreachable"
                )
            }
            println("[keyboard] manifest: $actionAliases dev action alias(es) for $DEV_PACKAGE")
        }
    }

    if (providers == 0) {
        throw PatchException(
            "$MANIFEST declares no provider with a \"$STOCK_PACKAGE.\" authority; " +
                "renaming the providers would have to be done by hand on this host version"
        )
    }
    if (permissions == 0) {
        throw PatchException("$MANIFEST does not reference \"$RECEIVER_PERMISSION\"")
    }
    if (imeServices == 0) {
        throw PatchException("$MANIFEST does not declare \"$IME_SERVICE\"")
    }

    println(
        "[keyboard] manifest: $providers provider authority prefix(es), $permissions receiver " +
            "permission reference(s), $imeServices IME service(s) exported"
    )
}

private fun renameHardcodedAuthorities(targetPackage: String) {
    val replacement = "content://$targetPackage."
    var patched = 0

    classDefForEach { classDef ->
        val usesStockAuthority = classDef.methods.any { method ->
            method.implementation?.instructions?.any { instruction ->
                instruction.stringConstant()?.startsWith(CONTENT_URI_PREFIX) == true
            } == true
        }
        if (!usesStockAuthority) return@classDefForEach

        mutableClassDefBy(classDef).methods.filterIsInstance<MutableMethod>().forEach { method ->
            method.instructions.forEachIndexed { index, instruction ->
                val value = instruction.stringConstant() ?: return@forEachIndexed
                if (!value.startsWith(CONTENT_URI_PREFIX)) return@forEachIndexed
                if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) {
                    return@forEachIndexed
                }

                val register = (instruction as OneRegisterInstruction).registerA
                method.replaceInstruction(index, "const-string v$register, \"${value.replace(CONTENT_URI_PREFIX, replacement)}\"")
                patched += 1
                println("[keyboard] content authority -> $replacement (${method.definingClass}->${method.name})")
            }
        }
    }

    if (patched == 0) {
        throw PatchException(
            "no hardcoded \"$CONTENT_URI_PREFIX\" reference was found in the bytecode. " +
                "Renaming the providers would leave the app querying the stock authorities."
        )
    }
}

private fun receiverPermission(targetPackage: String) = "$targetPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

/**
 * The stock IME declares that it supports inline suggestions, which is what the autofill framework
 * uses to show the inline "app fill" UI above the keyboard. The declaration lives in the input method
 * descriptor, not in the manifest, so it is added to `res/xml/ime_config.xml`.
 */
private fun enableInlineSuggestions() {
    val path = "res/xml/ime_config.xml"
    resourceXml(path).use { imeConfig ->
        val inputMethod = imeConfig.elements("input-method").firstOrNull()
            ?: throw PatchException("$path has no <input-method> element")
        if (inputMethod.attribute("android:supportsInlineSuggestions") == "true") {
            println("[keyboard] inline suggestions already enabled in $path")
            return
        }
        inputMethod.setAttribute("android:supportsInlineSuggestions", "true")
        println("[keyboard] inline suggestions enabled in $path")
    }
}

private fun Instruction.stringConstant(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
