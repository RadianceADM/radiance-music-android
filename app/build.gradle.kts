import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.radianceadm.music"
    compileSdk = 37 // the libraries are built against Android 17; the app still targets 16

    defaultConfig {
        applicationId = "io.github.radianceadm.music"
        minSdk = 29 // Android 10: saving into Music/ needs no storage permission
        targetSdk = 36
        versionCode = 20
        versionName = "0.5.11"
        // 64-bit Arm only: every phone that can split a song (the splitter needs a few GB of memory)
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives outside the repository (on the build server); see README.
    val signing = Properties().apply {
        val f = file(System.getenv("RADIANCE_SIGNING") ?: "/srv/build/android/keys/signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (signing.isNotEmpty()) {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes {
        // a test build: beside the real app (its own name), so a phone's device tests never touch it
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // shrunk (most of the size is unused icons); NewPipe and Rhino kept whole (proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signing.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // NewPipe Extractor uses java.time
    }
    buildFeatures {
        compose = true
    }
    // the splitter's model is stored as it is (the app maps it straight from the APK)
    androidResources { noCompress += "onnx" }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    val compose = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1") // the splitter (Demucs as ONNX)
    implementation("com.google.zxing:core:3.5.4") // QR codes: made and read
    implementation("androidx.camera:camera-camera2:1.6.2") // the camera, reading a share's QR
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17") // the story card drawn on the computer, to look at
    testImplementation("androidx.compose.ui:ui-test-junit4") // the screens drawn on the computer, to look at
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.7.0") // the story video made on a real Android (the emulator)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.1") // the splitter's test runs the real model on the computer
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}

// the unit tests run on a computer: ONNX Runtime's desktop build there, not Android's
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

// The splitter's model goes inside the app, so a phone never downloads it: fetched once by the
// build into Gradle's own folder, checked to be exactly the published file, then put among the
// app's assets.
abstract class SplitterModel : DefaultTask() {
    @get:OutputDirectory abstract val into: DirectoryProperty
    @get:Internal abstract val cache: RegularFileProperty

    @TaskAction fun put() {
        val cached = cache.get().asFile
        if (cached.length() != SIZE) {
            cached.parentFile.mkdirs()
            val part = File(cached.path + ".part")
            logger.lifecycle("Fetching the splitter's model (${SIZE / 1_000_000} MB, once)")
            URI(URL).toURL().openStream().use { s -> part.outputStream().use { s.copyTo(it, 1 shl 20) } }
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { s ->
                val b = ByteArray(1 shl 20)
                while (true) { val n = s.read(b); if (n < 0) break; digest.update(b, 0, n) }
            }
            val sum = digest.digest().joinToString("") { "%02x".format(it) }
            check(part.length() == SIZE && sum == SHA256) { "the splitter's model didn't come whole (${part.length()} bytes, $sum)" }
            part.renameTo(cached)
        }
        val out = into.get().asFile.resolve(ASSET)
        out.parentFile.mkdirs()
        if (out.length() != SIZE) cached.copyTo(out, overwrite = true)
    }

    companion object {
        /** Demucs (MIT) exported to ONNX by StemSplitio (MIT), weights stored as fp16. */
        const val URL = "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx"
        const val SIZE = 165_612_636L
        const val SHA256 = "d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a"
        /** Where the app finds it (Splitter.MODEL_ASSET). */
        const val ASSET = "splitter/htdemucs_fp16weights.onnx"
    }
}

// What's new, on the About page: the changelog goes in with the app.
abstract class ChangelogAsset : DefaultTask() {
    @get:InputFile abstract val from: RegularFileProperty
    @get:OutputDirectory abstract val into: DirectoryProperty

    @TaskAction fun put() {
        from.get().asFile.copyTo(into.get().asFile.resolve("CHANGELOG.md"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val changelog = tasks.register<ChangelogAsset>("${variant.name}ChangelogAsset") {
            from.set(rootProject.file("CHANGELOG.md"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(changelog, ChangelogAsset::into)
        if (variant.buildType == "debug") return@onVariants // a test build: small, without the splitter's model
        val model = tasks.register<SplitterModel>("${variant.name}SplitterModel") {
            cache.set(File(gradle.gradleUserHomeDir, "radiance/htdemucs_fp16weights.onnx"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(model, SplitterModel::into)
    }
}
