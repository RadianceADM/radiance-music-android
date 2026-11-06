package io.github.radianceadm.music.ui

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.radianceadm.music.App

/**
 * The music videos are in the app now (android#26), and Gallery still shows
 * old copies the app may not remove by itself (made before it was installed
 * again, or copied over from the computer). Android asks once, with its own
 * "Allow Radiance Music to delete…" (it shows them): no pop-up of ours before
 * it (owner, 2026-11-06: "ayaw ko na ipagawa yang mga ganyang bagay sa mga
 * user"). The app's own copies leave without asking (Videos.moveIn). Not over
 * the start's splash or the guide.
 */
@Composable
fun OldVideosPrompt(shown: Boolean) {
    val old by App.instance.oldVideos.collectAsState()
    var asking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // only while the app is on the screen (Android blocks it from behind; a video playing, say)
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val front = state.isAtLeast(Lifecycle.State.RESUMED)
    val removed = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        asking = false
        App.instance.oldVideosAsked()
        if (r.resultCode == Activity.RESULT_OK) App.instance.tell(Notice("Done: Gallery doesn't show your music videos any more."))
    }
    // Android 10 can't ask for many at once: they stay
    LaunchedEffect(shown, old, front) {
        if (!shown || !front || asking || old.isEmpty() || Build.VERSION.SDK_INT < 30) return@LaunchedEffect
        runCatching { MediaStore.createDeleteRequest(context.contentResolver, old).intentSender }
            .onSuccess { asking = true; removed.launch(IntentSenderRequest.Builder(it).build()) }
            .onFailure { Log.w("radiance-videos", "couldn't ask to remove the old copies", it); App.instance.oldVideosAsked() }
    }
}
