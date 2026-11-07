package io.github.radianceadm.music.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * No music videos on the phone (owner, 2026-11-08: "final na walang music video sa mobile",
 * android#32). Phones from 1.0.2 to 1.0.4 kept liked songs' videos in the app's own folder
 * (no_backup/videos); the update deletes them by itself, with nothing to ask or tap (the
 * owner's "Delete them by themselves"). Story videos (the share clips) aren't music videos
 * and stay.
 */
object Videos {
    /**
     * The kept videos in [dir] and the half-made ones in [cache] ("video-KEY") deleted.
     * How many bytes that freed (0: nothing was kept).
     */
    fun forget(dir: File, cache: File): Long {
        val gone = listOf(dir) + (cache.listFiles { f -> f.isDirectory && f.name.startsWith("video-") }?.toList() ?: emptyList())
        var freed = 0L
        for (d in gone.filter { it.exists() }) {
            freed += d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively()
        }
        return freed
    }

    /** At the app's start: the kept videos deleted, and the old "ask about these" list with them. */
    fun forget(context: Context) {
        val freed = forget(File(context.noBackupFilesDir, "videos"), context.cacheDir)
        context.getSharedPreferences("videos", Context.MODE_PRIVATE).edit().clear().apply()
        if (freed > 0) Log.i("radiance-videos", "no music videos on the phone: ${freed / 1_000_000} MB of kept videos deleted")
    }
}
