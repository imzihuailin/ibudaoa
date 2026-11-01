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
# Netty is pinned to the JDK logging backend. Optional desktop backends and
# BlockHound tooling are deliberately not bundled in this Android application.
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.slf4j.ILoggerFactory
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
-dontwarn org.slf4j.Marker
-dontwarn org.slf4j.helpers.FormattingTuple
-dontwarn org.slf4j.helpers.MessageFormatter
-dontwarn org.slf4j.helpers.NOPLoggerFactory
-dontwarn org.slf4j.spi.LocationAwareLogger
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# Netty's runtime reflection contract: exclusion method names, atomic updater
# fields, handler callback names, and generic codec type parameters.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepclassmembers class io.netty.buffer.AbstractByteBufAllocator {
    protected static *** toLeakAwareBuffer(...);
}
-keepclassmembers class io.netty.buffer.AdvancedLeakAwareByteBuf {
    *** touch(...);
    static void recordLeakNonRefCountingOperation(...);
}
-keepclassmembers class io.netty.util.ReferenceCountUtil {
    public static *** touch(...);
}
-keepclassmembers class io.netty.** {
    volatile <fields>;
}
-keepclassmembers class * implements io.netty.channel.ChannelHandler {
    public <methods>;
}
-keep class io.netty.handler.codec.MessageTo* { *; }
-keep class io.netty.handler.codec.MessageAggregator { *; }
-keep class io.netty.handler.codec.ByteToMessageCodec { *; }
-keep class io.netty.handler.codec.http.HttpObjectAggregator { *; }
-keep class io.netty.channel.SimpleChannelInboundHandler { *; }
