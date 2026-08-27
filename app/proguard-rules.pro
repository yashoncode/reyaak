# R8 rules for the release build.
#
# Room, kotlinx.coroutines and OkHttp ship their own consumer rules, so nothing
# is repeated for them here. What is here is what R8 gets wrong on its own:
# reflection-adjacent serialization lookups, and Ktor's engine discovery.

# ── kotlinx.serialization ───────────────────────────────────────────────────
# Serializers are found through generated companions and static fields that no
# code path references directly, so R8 sees them as dead. Every persisted thing
# in this app goes through them: router config, tool config, persona, skills.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers public class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The app's own serializable models, named explicitly so a rule change upstream
# cannot quietly strip the classes every provider key is stored in.
-keep,includedescriptorclasses class io.reyaak.**$$serializer { *; }
-keepclassmembers class io.reyaak.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Ktor ────────────────────────────────────────────────────────────────────
# The client is built without naming an engine: it is discovered at runtime, so
# there is no static reference for R8 to follow.
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ── Compose / Kotlin metadata ───────────────────────────────────────────────
-dontwarn kotlinx.coroutines.debug.**
