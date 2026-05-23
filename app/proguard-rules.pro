# WebRTC native bridge classes must stay reflectable.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }

# HiveMQ client uses Netty and reflection-heavy DI.
-keep class io.netty.** { *; }
-keep class com.hivemq.** { *; }

# kotlinx-serialization: keep generated serializer classes.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keep class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class org.crainiate.bluesteal.models.**$$serializer { *; }
-keepclassmembers class org.crainiate.bluesteal.models.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
