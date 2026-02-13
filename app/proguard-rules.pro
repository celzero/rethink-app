# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
# commenting below, webview removed from version v053i
#-keepclassmembers class com.celzero.bravedns.ui.DnsConfigureWebViewActivity$JSInterface {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

#Dont obfuscate
-dontobfuscate
-printmapping obfuscation/mapping.txt
-printmapping build/outputs/mapping/release/mapping.txt

# https://github.com/celzero/rethink-app/issues/875
# Keep generic signature of Call, Response (R8 full mode strips signatures from non-kept items).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# With R8 full mode generic signatures are stripped for classes that are not
# kept. Suspend functions are wrapped in continuations where the type argument
# is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Retain generic signatures of TypeToken and its subclasses with R8 version 3.0 and higher.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**

# A resource is loaded with a relative path so the package of this class must be preserved.
# ref: github.com/square/okhttp/issues/8154#issuecomment-1868462895
# issue: https://github.com/celzero/rethink-app/issues/1495
# square.github.io/okhttp/features/r8_proguard/
-keeppackagenames okhttp3.internal.publicsuffix.*
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

# Animal Sniffer compileOnly dependency to ensure APIs are compatible with older versions of Java.
-dontwarn org.codehaus.mojo.animal_sniffer.*

# OkHttp platform used only on JVM and when Conscrypt and other security providers are available.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep Gson classes and attributes for JSON serialization/deserialization
# FileTag class uses Gson with custom deserializer (FileTagDeserializer) to handle
# dynamic JSON formats where "url" field can be either a string or JsonArray
# Without these rules, obfuscation would break the reflection-based JSON parsing
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.celzero.bravedns.data.FileTag { *; }

# Note: Non-fatal TimeoutException in android.content.res.ResourcesImpl$ThemeImpl.finalize()
# This is a known Android framework issue where theme objects cannot be garbage collected
# fast enough when many activities/dialogs apply custom themes. This is not directly
# fixable at the application level. The exception is non-fatal and does not affect app
# functionality. For reference: https://github.com/celzero/rethink-app/issues/[issue-number]

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
