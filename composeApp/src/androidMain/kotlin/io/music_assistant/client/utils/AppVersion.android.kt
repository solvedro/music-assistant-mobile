package io.music_assistant.client.utils

import android.content.pm.PackageManager
import android.os.Build
import io.music_assistant.client.player.PlatformContext

actual fun appVersion(platformContext: PlatformContext): AppVersion {
    val context = platformContext.applicationContext
    val packageManager = context.packageManager
    val info = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))

        else -> @Suppress("DEPRECATION") packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return AppVersion(name = info.versionName.orEmpty(), code = versionCode.toString())
}
