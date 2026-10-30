package io.github.radianceadm.music

import io.github.radianceadm.music.ui.changelog
import io.github.radianceadm.music.update.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdatesTest {
    @Test fun versionsCompareAsNumbers() {
        assertTrue(Updates.newer("0.5.10", "0.5.9"))
        assertTrue(Updates.newer("0.6.0", "0.5.9"))
        assertTrue(Updates.newer("1.0", "0.9.9"))
        assertFalse(Updates.newer("0.5.9", "0.5.9"))
        assertFalse(Updates.newer("0.5.8", "0.5.9"))
        assertFalse(Updates.newer("0.5", "0.5.0"))
    }

    @Test fun theChangelogReadsAsVersionsWithTheirItems() {
        val text = File("../CHANGELOG.md").readText()
        val versions = changelog(text)
        assertTrue(versions.size > 5)
        val (newest, items) = versions[0]
        assertEquals(newest, Regex("""(?m)^## (\S+)""").find(text)!!.groupValues[1])
        assertTrue(items.isNotEmpty())
        // a wrapped line joins its item: no item starts lower-case mid-sentence
        assertTrue(items.all { it.first().isUpperCase() || it.startsWith("**") })
    }
}
