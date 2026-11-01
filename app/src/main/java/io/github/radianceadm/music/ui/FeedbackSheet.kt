package io.github.radianceadm.music.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.radianceadm.music.App
import io.github.radianceadm.music.feedback.Account
import io.github.radianceadm.music.feedback.Answers
import io.github.radianceadm.music.feedback.Area
import io.github.radianceadm.music.feedback.Feedback
import io.github.radianceadm.music.feedback.Kind
import io.github.radianceadm.music.feedback.Step
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.share.Share
import kotlinx.coroutines.launch

/**
 * The feedback pop-up (owner, 2026-11-01, #14), a bottom sheet at one height (as the
 * Mixer's). It comes by itself now and then at a free moment (the music paused, or a
 * song just ended: Feedback.moments, Timing), only while you're looking at the app,
 * and never over the start's splash or the first-time guide ([shown] false then).
 * Settings → Rate Radiance Music opens it any time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackPrompt(shown: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val open by Feedback.open.collectAsState()
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        Feedback.moments(App.instance.player.playing).collect {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) Feedback.maybeAsk(context)
        }
    }
    if (!shown || !open) return
    val step by Feedback.step.collectAsState()
    val answers by Feedback.draft.collectAsState()
    val me by Account.me.collectAsState()
    val online by Connectivity.online.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // a button that closes it: the sheet slides down first
    val closing = { then: () -> Unit -> scope.launch { sheet.hide() }.invokeOnCompletion { then() } }
    ModalBottomSheet(
        // swiped down, a tap above it, or Back: as "Not now" (after Thanks: just closed; sending goes on)
        onDismissRequest = { if (step is Step.Thanks || Feedback.busy) Feedback.close() else Feedback.notNow(context) },
        sheetState = sheet,
        containerColor = Brand.Sheet,
    ) {
        FeedbackForm(
            answers = answers,
            step = step,
            me = me,
            online = online,
            onAnswers = Feedback::answer,
            onSend = { Feedback.send(context) },
            onNotNow = { closing { Feedback.notNow(context) } },
            onNever = { closing { Feedback.never(context) } },
            onSignOut = { Feedback.signOut(context) },
            onShare = { runCatching { Share.app(context) } },
            onDone = { closing { Feedback.close() } },
        )
    }
}

/** The pop-up's insides for each step (drawn alone in the screenshots). */
@Composable
fun FeedbackForm(
    answers: Answers,
    step: Step,
    me: Account.Me?,
    online: Boolean,
    onAnswers: (Answers) -> Unit,
    onSend: () -> Unit,
    onNotNow: () -> Unit,
    onNever: () -> Unit,
    onSignOut: () -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 22.dp, end = 22.dp, bottom = 24.dp).animateContentSize()) {
        if (step is Step.Thanks) Thanks(step.share, onShare, onDone)
        else Questions(answers, step, me, online, onAnswers, onSend, onNotNow, onNever, onSignOut)
    }
}

@Composable
private fun Questions(
    answers: Answers,
    step: Step,
    me: Account.Me?,
    online: Boolean,
    onAnswers: (Answers) -> Unit,
    onSend: () -> Unit,
    onNotNow: () -> Unit,
    onNever: () -> Unit,
    onSignOut: () -> Unit,
) {
    val busy = step is Step.Sending || step is Step.SigningIn
    val problem = step as? Step.Problem
    // Send tapped with answers missing: what's left shows
    val showMissing = problem?.kind == Kind.MISSING
    Text("Are you enjoying Radiance Music?", style = MaterialTheme.typography.headlineSmall, color = Color.White)
    Gap(4.dp)
    Text("A minute of your time helps make the next version better.", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium)
    Gap(16.dp)
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Choice(Icons.Rounded.SentimentSatisfied, "Yes!", answers.enjoying == true) { onAnswers(answers.copy(enjoying = true)) }
        Choice(Icons.Rounded.SentimentDissatisfied, "Not really", answers.enjoying == false) { onAnswers(answers.copy(enjoying = false)) }
    }
    if (showMissing && answers.enjoying == null) Text("Pick one", color = Brand.Orange, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    Gap(10.dp)
    Area.entries.forEach { a ->
        StarRow(a.label, answers.ratings[a] ?: 0, missing = showMissing && a in answers.missing) { onAnswers(answers.rate(a, it)) }
    }
    Gap(12.dp)
    Text("Anything to tell us? (optional)", color = Color.White, style = MaterialTheme.typography.titleMedium)
    Gap(6.dp)
    // how much is left, near the admin's limit
    val counter: (@Composable () -> Unit)? = if (answers.comment.length > Answers.MAX_COMMENT - 200) {
        { Text("${answers.comment.length} of ${Answers.MAX_COMMENT}", color = Brand.Dim) }
    } else null
    TextField(
        value = answers.comment,
        onValueChange = { onAnswers(answers.write(it)) },
        placeholder = { Text("What you like, what bothers you, what you'd like next") },
        minLines = 3,
        maxLines = 6,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Brand.Card, unfocusedContainerColor = Brand.Card, cursorColor = Brand.Orange),
        supportingText = counter,
        modifier = Modifier.fillMaxWidth(),
    )
    Gap(14.dp)
    when {
        problem != null -> ProblemNote(problem)
        !online && !busy -> ProblemNote(Step.Problem(Kind.OFFLINE, Feedback.OFFLINE))
    }
    SendButton(step, signedIn = me != null, onSend)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Lock, null, tint = Brand.Dim, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                append("Your name and email are kept with your rating. ")
                withLink(LinkAnnotation.Url(Feedback.PRIVACY, TextLinkStyles(SpanStyle(color = Brand.Orange, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)))) {
                    append("Privacy")
                }
            },
            color = Brand.Dim,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (me != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sending as ${me.name.ifBlank { me.email }}", color = Brand.Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 24.dp))
            TextButton(onClick = onSignOut, enabled = !busy) { Labeled(Icons.AutoMirrored.Rounded.Logout, "Sign out", Brand.Dim) }
        }
    }
    Gap(10.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = onNotNow,
            enabled = !busy,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Brand.Card, contentColor = Color.White),
            modifier = Modifier.weight(1f).height(48.dp),
        ) { Labeled(Icons.Rounded.Schedule, "Not now") }
        TextButton(onClick = onNever, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) { Labeled(Icons.Rounded.NotificationsOff, "Don't ask again", Brand.Dim) }
    }
}

/** Yes! or Not really: a round pill each, orange when picked. */
@Composable
private fun RowScope.Choice(icon: ImageVector, text: String, on: Boolean, onClick: () -> Unit) {
    val fg = if (on) Color.Black else Color.White
    Row(
        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(50)).background(if (on) Brand.Orange else Brand.Card)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg, style = MaterialTheme.typography.titleMedium)
    }
}

/** An area and its five stars, each big enough to tap (44 dp). */
@Composable
private fun StarRow(label: String, stars: Int, missing: Boolean, onRate: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (missing) Text("Tap a star", color = Brand.Orange, style = MaterialTheme.typography.labelSmall)
        }
        for (i in 1..5) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .selectable(selected = stars == i, role = Role.RadioButton, onClick = { onRate(i) })
                    .semantics { contentDescription = "$label: $i of 5 stars" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Star, null, tint = if (i <= stars) Brand.Orange else Color.White.copy(alpha = 0.18f), modifier = Modifier.size(34.dp))
            }
        }
    }
}

/** Why it didn't go, on a soft card: the answers are still there. */
@Composable
private fun ProblemNote(p: Step.Problem) {
    val icon = when (p.kind) {
        Kind.MISSING -> Icons.Rounded.Star
        Kind.OFFLINE -> Icons.Rounded.CloudOff
        Kind.SIGN_IN -> Icons.Rounded.AccountCircle
        Kind.LATER -> Icons.Rounded.Schedule
        Kind.NOT_READY -> Icons.Rounded.Info
        Kind.CHECK, Kind.OTHER -> Icons.Rounded.ErrorOutline
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(Brand.Orange.copy(alpha = 0.12f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Brand.Orange, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(p.words, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Send (signed out: sign in with Google first; after a problem: Try again), with its progress while it goes. */
@Composable
private fun SendButton(step: Step, signedIn: Boolean, onClick: () -> Unit) {
    val busy = step is Step.Sending || step is Step.SigningIn
    val kind = (step as? Step.Problem)?.kind
    Button(
        onClick = onClick,
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange, contentColor = Color.Black, disabledContainerColor = Brand.Orange.copy(alpha = 0.75f), disabledContentColor = Color.Black),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        when {
            busy -> {
                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.5.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Text(if (step is Step.SigningIn) "Signing in…" else "Sending…")
            }
            !signedIn -> Labeled(Icons.AutoMirrored.Rounded.Login, "Sign in with Google to send")
            kind == Kind.OFFLINE || kind == Kind.OTHER || kind == Kind.LATER -> Labeled(Icons.Rounded.Refresh, "Try again")
            else -> Labeled(Icons.AutoMirrored.Rounded.Send, "Send")
        }
    }
}

/** Sent: thanks, and (4 or 5 stars overall) Share Radiance Music. */
@Composable
private fun Thanks(share: Boolean, onShare: () -> Unit, onDone: () -> Unit) {
    Gap(6.dp)
    Box(Modifier.size(56.dp).background(Brand.Orange.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Favorite, null, tint = Brand.Orange, modifier = Modifier.size(30.dp))
    }
    Gap(16.dp)
    Text(if (share) "Thanks!" else "Thanks for telling us", style = MaterialTheme.typography.headlineSmall, color = Color.White)
    Gap(6.dp)
    Text(
        if (share) "Glad you're enjoying it. Know someone who'd like it too?" else "We read every answer: it helps us make Radiance Music better.",
        color = Brand.Dim, style = MaterialTheme.typography.bodyMedium,
    )
    Gap(20.dp)
    if (share) {
        Button(onClick = onShare, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Labeled(Icons.Rounded.IosShare, "Share Radiance Music")
        }
        Gap(8.dp)
        FilledTonalButton(onClick = onDone, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Brand.Card, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Labeled(Icons.Rounded.Check, "Done")
        }
    } else {
        Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Labeled(Icons.Rounded.Check, "Done")
        }
    }
}
