# SQLCipher: JNI looks classes/methods up by name.
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }

# Tink uses protobuf-lite reflection for keyset parsing.
-keep class com.google.crypto.tink.proto.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
