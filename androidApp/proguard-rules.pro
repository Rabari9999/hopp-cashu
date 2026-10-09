# Hopp — R8 rules beyond what the libraries ship themselves.

# Ktor finds its engine (OkHttp) through ServiceLoader: keep the container
# the service file names, or the client has no engine at run time.
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

# secp256k1 is called through JNI; the native side looks classes and
# methods up by name.
-keep class fr.acinq.secp256k1.** { *; }

# Typed navigation routes and API models: kotlinx.serialization looks up the
# generated serializer through the companion.
-keepclassmembers class space.sovereigncast.hopp.** {
    *** Companion;
}
-keepclasseswithmembers class space.sovereigncast.hopp.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class space.sovereigncast.hopp.**$$serializer { *; }

# Stack traces in crash reports stay readable against the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
