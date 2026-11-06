package io.github.radianceadm.music.ui

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.radianceadm.music.App

/**
 * The music videos are in the app now (android#26), and Gallery still shows
 * old copies the app may not remove by itself (made before it was installed
 * again, or copied over from the computer). Asked once: "Remove the old
 * copies" opens Android's own "Allow Radiance Music to delete…" (it shows
 * them); "Keep them in Gallery" leaves them. Tapped away, it asks again the
 * next time the app opens. Not over the start's splash or the guide.
 */
@Composable
fun OldVideosPrompt(shown: Boolean) {
    val old by App.instance.oldVideos.collectAsState()
    var away by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val removed = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        asking = false
        App.instance.oldVideosAsked()
        if (r.resultCode == Activity.RESULT_OK) App.instance.tell(Notice("Done: Gallery doesn't show your music videos any more."))
    }
    // Android 10 can't ask for many at once: they stay
    if (!shown || away || asking || old.isEmpty() || Build.VERSION.SDK_INT < 30) return
    Dialog(onDismissRequest = { away = true }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        OldVideosCard(
            count = old.size,
            onRemove = {
                runCatching { MediaStore.createDeleteRequest(context.contentResolver, old).intentSender }
                    .onSuccess { asking = true; removed.launch(IntentSenderRequest.Builder(it).build()) }
                    .onFailure { Log.w("radiance-videos", "couldn't ask to remove the old copies", it); App.instance.oldVideosAsked() }
            },
            onKeep = { App.instance.oldVideosAsked() },
        )
    }
}

/** The pop-up's card (drawn alone in the screenshots). */
@Composable
fun OldVideosCard(count: Int, onRemove: () -> Unit, onKeep: () -> Unit) {
    val one = count == 1
    val copies = if (one) "1 old copy" else "$count old copies"
    Surface(
        color = Brand.Sheet, contentColor = Color.White, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 440.dp).fillMaxWidth(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
            Head(
                Icons.Rounded.VideoLibrary, "Your music videos are in the app now",
                "Watch one from the Video button on the song's cover. Gallery still shows $copies from before, and ${if (one) "it takes" else "they take"} up space. Your phone asks you before Radiance Music removes ${if (one) "it" else "them"}.",
            )
            Spacer(Modifier.height(20.dp))
            Main(Icons.Rounded.DeleteSweep, if (one) "Remove the old copy" else "Remove the old copies", onRemove)
            Spacer(Modifier.height(8.dp))
            Second(Icons.Rounded.PhotoLibrary, if (one) "Keep it in Gallery" else "Keep them in Gallery", onKeep)
        }
    }
}
