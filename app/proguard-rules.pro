# No platform credential or raw response logging is permitted.
# Bundled AMap dynamically loads its SDK components and native entry points.
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }
-keep class org.bouncycastle.** { *; }
# Optional classes are already absent from the bundled SDK; preserve its runtime fallback.
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult