package io.github.radianceadm.music.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap

/**
 * Where a story goes, as Spotify's share screen shows them: the apps on this
 * phone, straight to their story when they have one (Instagram and Facebook),
 * then Save and More (Android's share menu).
 */
object Targets {
    enum class Kind { APP, SAVE, MORE }

    data class Target(val id: String, val label: String, val kind: Kind, val pkg: String? = null, val activity: String? = null, val icon: Bitmap? = null)

    /** The apps known for stories and messages: package, label, and how its story activity is named (if it has one). */
    private val known = listOf(
        Triple("com.instagram.android", "Instagram Stories", listOf("Story")),
        Triple("com.facebook.katana", "Facebook Stories", listOf("Inpiration", "Inspiration", "Story")),
        Triple("com.facebook.orca", "Messenger", emptyList()),
        Triple("com.whatsapp", "WhatsApp", emptyList()),
        Triple("com.zhiliaoapp.musically", "TikTok", emptyList()),
        Triple("com.ss.android.ugc.trill", "TikTok", emptyList()),
        Triple("com.twitter.android", "X", emptyList()),
        Triple("org.telegram.messenger", "Telegram", emptyList()),
    )

    /** What's on this phone that takes [mime] ("image/jpeg", "video/mp4"), then Save and More. */
    fun available(context: Context, mime: String): List<Target> {
        val pm = context.packageManager
        val out = mutableListOf<Target>()
        for ((pkg, label, story) in known) {
            if (out.any { it.label == label }) continue // TikTok's two names
            val takers = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg), 0) }.getOrDefault(emptyList())
            if (takers.isEmpty()) continue
            val activity = takers.firstOrNull { r -> story.any { r.activityInfo.name.contains(it, ignoreCase = true) } }?.activityInfo?.name
            if (story.isNotEmpty() && activity == null) {
                // no story here (an older app): its own share, named plainly
                out += Target(pkg, label.removeSuffix(" Stories"), Kind.APP, pkg, null, icon(context, pkg))
                continue
            }
            out += Target(pkg, label, Kind.APP, pkg, activity, icon(context, pkg))
        }
        out += Target("save", "Save", Kind.SAVE)
        out += Target("more", "More", Kind.MORE)
        return out
    }

    private fun icon(context: Context, pkg: String): Bitmap? =
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(144, 144) }.getOrNull()

    /** Hands [uri] ([mime]) to [target]: its story when it has one, else the app's own share; More opens Android's menu. */
    fun send(context: Context, target: Target, uri: Uri, mime: String, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(title, uri) // lets the app picked see the file
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val go = when {
            target.kind == Kind.MORE -> Intent.createChooser(send, "Share “$title”")
            target.activity != null -> send.setComponent(ComponentName(target.pkg!!, target.activity))
            else -> send.setPackage(target.pkg)
        }
        context.startActivity(go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
