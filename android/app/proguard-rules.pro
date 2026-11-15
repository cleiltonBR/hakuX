# R8 for both build types: shrinking and optimizing the Kotlin and Compose
# code (unshrunk, the debug APK carried 28 MB of dex; interpreted, it made the
# library stutter on fast scrolls).

# JNI: native methods are found by their class's and their own names
# (Java_com_rfandango_haku_1x_NativeCoreActivity_nativeSetPad, ...); the
# classes that hold them stay whole, unmerged and unrenamed.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# SDL's C code calls its Java side by name (static methods, fields, the
# activity's overridable methods).
-keep class org.libsdl.app.** { *; }
-keep class * extends org.libsdl.app.SDLActivity { *; }

-keep class org.libtorrent4j.swig.libtorrent_jni { *; }
