# App keep rules for R8 (04-platforms.md section 3). Room, kotlinx.serialization, Ktor, DataStore and Compose ship
# their own consumer rules; add a rule here only for a failure seen in the release build.
# AGP 9 keep rules are strict: "-keep class A" no longer keeps A's constructor.

# kotlinx.serialization: keep the serializers of our @Serializable classes (the rfab models and the digest JSON).
-keepclassmembers @kotlinx.serialization.Serializable class io.github.hugo_cacciatori.basedinformant.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor refers to JVM-only classes that Android does not have.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
