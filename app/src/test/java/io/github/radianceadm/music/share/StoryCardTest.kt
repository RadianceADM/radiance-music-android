package io.github.radianceadm.music.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws the story cards (cover only, with lyrics, with long lyrics) on the build server, to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class StoryCardTest {
    private val dir = File("/srv/build/android/splittest")

    @Test fun draws() {
        assumeTrue(File(dir, "cover.jpg").exists())
        val context = RuntimeEnvironment.getApplication()
        val art = BitmapFactory.decodeFile(File(dir, "cover.jpg").path)
        val t = Track(Source.YOUTUBE, "x", "Blow My Mind (feat. Louie Grammz)", "BLAZINBONE")
        val cases = mapOf(
            "cover" to emptyList(),
            "lyrics" to listOf("Kapag katabi ka, you make me higher", "Alam mong 'di rin 'to tatanggi", "Pinagpapawisan 'cause girl, you're fire"),
            "long" to listOf("Kapag katabi ka, you make me higher", "Alam mong 'di rin 'to tatanggi", "Pinagpapawisan 'cause girl, you're fire", "Pagpaapoy tayo buong gabi", "Suit inside sa ka-vest, bagay sa dress"),
            "noart" to listOf("Kapag katabi ka, you make me higher"),
        )
        // the launcher icon's layers, as a phone shows them (full tile, a circle, themed)
        val icon = Bitmap.createBitmap(3 * 432, 432, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(icon)
        c.drawColor(android.graphics.Color.rgb(240, 238, 234))
        val back = androidx.core.content.ContextCompat.getDrawable(context, io.github.radianceadm.music.R.drawable.ic_launcher_background)!!
        val front = androidx.core.content.ContextCompat.getDrawable(context, io.github.radianceadm.music.R.drawable.ic_launcher_foreground)!!
        val mono = androidx.core.content.ContextCompat.getDrawable(context, io.github.radianceadm.music.R.drawable.ic_launcher_monochrome)!!
        for (i in 0 until 3) {
            val save = c.save()
            val path = android.graphics.Path()
            val l = i * 432f
            if (i == 1) path.addCircle(l + 216f, 216f, 180f, android.graphics.Path.Direction.CW) else path.addRoundRect(android.graphics.RectF(l + 36, 36f, l + 396, 396f), 84f, 84f, android.graphics.Path.Direction.CW)
            c.clipPath(path)
            if (i < 2) {
                for (d in listOf(back, front)) { d.setBounds((l - 54).toInt(), -54, (l + 486).toInt(), 486); d.draw(c) }
            } else {
                c.drawColor(android.graphics.Color.rgb(222, 232, 216))
                mono.setTint(android.graphics.Color.rgb(40, 70, 40)); mono.setBounds((l - 54).toInt(), -54, (l + 486).toInt(), 486); mono.draw(c)
            }
            c.restoreToCount(save)
        }
        File(dir, "app-icon.png").outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
        for ((name, lines) in cases) {
            val card = StoryCard.draw(context, t, if (name == "noart") null else art, lines)
            assertEquals(StoryCard.W, card.width)
            assertEquals(StoryCard.H, card.height)
            File(dir, "card-$name.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
