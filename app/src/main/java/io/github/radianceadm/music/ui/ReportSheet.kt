package io.github.radianceadm.music.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.radianceadm.music.feedback.Api
import io.github.radianceadm.music.net.Plain
import io.github.radianceadm.music.report.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → About → Report a problem (see Report): what will be sent, shown in
 * full before anything goes; an optional note; Send. Then the report's code to
 * quote, with Copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(onDone: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var what by remember { mutableStateOf<Report.What?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var showSent by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { what = withContext(Dispatchers.IO) { Report.gather(context) } }
    val close = { scope.launch { sheet.hide() }.invokeOnCompletion { onDone() } }
    ModalBottomSheet(onDismissRequest = { if (!sending) onDone() }, sheetState = sheet, containerColor = Brand.Sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
        ) {
            val sentCode = code
            if (sentCode != null) {
                Box(Modifier.size(56.dp).background(Brand.Orange.copy(alpha = 0.18f), CircleShape).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = Brand.Orange, modifier = Modifier.size(32.dp))
                }
                Gap(12.dp)
                Text("Sent. Thank you!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                Gap(6.dp)
                Text("Your report is $sentCode. If you write to us about it (hello@radianceph.com), quote that.", color = Brand.Dim, modifier = Modifier.align(Alignment.CenterHorizontally))
                Gap(18.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Radiance Music report", sentCode))
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Brand.Card, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Labeled(Icons.Rounded.ContentCopy, "Copy the code") }
                    Button(onClick = { close() }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange, contentColor = Color.Black), modifier = Modifier.weight(1f).height(48.dp)) {
                        Labeled(Icons.Rounded.Close, "Close")
                    }
                }
                return@Column
            }
            Text("Report a problem", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Gap(6.dp)
            Text("Tell us what went wrong. Nothing is sent until you press Send, and you can see below exactly what goes.", color = Brand.Dim)
            Gap(14.dp)
            TextField(
                value = note,
                onValueChange = { note = it.take(Report.MOST_NOTE) },
                placeholder = { Text("What you did, and what happened (you can leave it empty)") },
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Brand.Card, unfocusedContainerColor = Brand.Card, cursorColor = Brand.Orange),
                supportingText = if (note.isNotEmpty()) ({ Text("${note.length} of ${Report.MOST_NOTE}", color = Brand.Dim) }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { showSent = !showSent }) {
                Labeled(if (showSent) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "What will be sent", Color.White)
            }
            if (showSent) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 280.dp).background(Brand.Card, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState()).padding(12.dp),
                ) {
                    Text(what?.shown() ?: "Reading the app's log…", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                }
                Gap(10.dp)
            }
            problem?.let {
                Text(it, color = Brand.Orange, style = MaterialTheme.typography.bodyMedium)
                Gap(10.dp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { close() },
                    enabled = !sending,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Brand.Card, contentColor = Color.White),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Labeled(Icons.Rounded.Close, "Cancel") }
                Button(
                    onClick = {
                        val w = what ?: return@Button
                        scope.launch {
                            sending = true
                            problem = null
                            val sent = withContext(Dispatchers.IO) { runCatching { Api().report(w.body(note)) } }
                            sending = false
                            sent.onSuccess { code = it }.onFailure { e ->
                                problem = (e as? Plain)?.words ?: "Couldn't send it. Check the internet and try again."
                            }
                        }
                    },
                    enabled = what != null && !sending,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                    else Labeled(Icons.AutoMirrored.Rounded.Send, "Send")
                }
            }
        }
    }
}
