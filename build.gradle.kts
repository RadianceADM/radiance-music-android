plugins {
    id("com.android.application") version "9.5.0" apply false
    // Kotlin is built into AGP 9; these put this Kotlin version on the classpath for it and Compose
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
