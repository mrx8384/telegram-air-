package com.tcam.screen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** The official Telegram Android apps this module knows how to drive. */
object TelegramApps {
    val PACKAGES = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta")

    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { isInstalled(context.packageManager, it) }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun launchMain(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        return true
    }

    /** Opens a public channel by username through Telegram's own deep link. */
    fun openUsername(context: Context, pkg: String, username: String) =
        open(context, pkg, Uri.parse("tg://resolve?domain=$username"))

    /** Opens an invite link (t.me/+hash) through Telegram's own deep link. */
    fun openInvite(context: Context, pkg: String, link: String) {
        val hash = link.substringAfterLast('+').substringAfterLast('/')
        open(context, pkg, Uri.parse("tg://join?invite=$hash"))
    }

    private fun open(context: Context, pkg: String, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
