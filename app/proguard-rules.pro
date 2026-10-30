# NewPipe Extractor reads YouTube's player with Rhino (JavaScript), which loads
# classes by name: both are kept whole.
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
# our own tracks travel as JSON through the player's extras
-keep class io.github.radianceadm.music.model.** { *; }
# ONNX Runtime (the splitter) is reached from its native library by name
-keep class ai.onnxruntime.** { *; }
