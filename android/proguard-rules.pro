-dontobfuscate

#ignore warnings about sun.misc.Unsafe
#as well as other internal platform-specific or optional things
-dontwarn rx.internal.util.**

-dontwarn io.netty.util.internal.**
-dontwarn io.netty.handler.ssl.**
-dontwarn io.netty.channel.socket.nio.**
-dontwarn io.netty.handler.codec.**

-dontwarn com.fasterxml.jackson.databind.ext.**

-dontwarn org.joda.convert.**

-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

-keep class !kotlin.**,!io.netty.**,!com.fasterxml.jackson.**,!org.spongycastle.**,!com.google.protobuf.** { *; }