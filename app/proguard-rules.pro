# kotlinx.serialization keeps its serializers in companion objects and synthetic
# @Serializer classes that R8 cannot see being used, so a shrunk build deserializes
# into empty lists without throwing. These rules are the ones the library documents.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The data classes the scrapers feed: field names are the wire contract, so they must
# survive renaming for scraper/CONTRACT.md to keep holding.
-keep,includedescriptorclasses class com.adminstack.rybnik.**$$serializer { *; }
-keepclassmembers class com.adminstack.rybnik.** {
    *** Companion;
}
-keepclasseswithmembers class com.adminstack.rybnik.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional hooks for platforms we do not target; R8 warns without this.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
