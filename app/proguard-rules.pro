-dontwarn org.jetbrains.annotations.**

-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * { native <methods>; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.jvm.internal.**
-keep class com.google.gson.** { *; }
