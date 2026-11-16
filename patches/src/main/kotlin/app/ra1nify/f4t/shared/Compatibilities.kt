package app.ra1nify.f4t.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

// Package name only: patched hosts are re-signed, so a signer hash would never match.
val BILIBILI = Compatibility(
    name = "Bilibili",
    packageName = "tv.danmaku.bili",
    apkFileType = ApkFileType.APK,
    appIconColor = 0xFF7299,
)

val VIRTUAL_TABLET = Compatibility(
    name = "VirtualTablet",
    packageName = "com.sunnysidesoft.VirtualTablet.lite",
    apkFileType = ApkFileType.APK,
    appIconColor = 0x2C6BED,
)
