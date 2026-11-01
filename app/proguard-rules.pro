# Keep Kotlin metadata so kotlin-reflect (kotlin.reflect.full.primaryConstructor,
# callBy, @SerialName-based lookup) keeps working on kept classes.
-keep class kotlin.Metadata { *; }

# SettingSearchPage creates search service options via
# selectedType.primaryConstructor!!.callBy(mapOf()) — keep the sealed base
# class, all subclasses, and their constructors.
-keep class me.rerere.search.SearchServiceOptions { *; }
-keep class me.rerere.search.SearchServiceOptions$* { *; }

# libtermux.so attach bridge and the workspace PTY bind JNI by class/method name.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class me.rerere.rikkihub.terminal.RemotePtyNative { *; }
-keep class com.termux.terminal.JNI { *; }
-keep class me.rerere.workspace.WorkspacePtyJni { *; }

# Web listener (Netty): runtime reflection that R8 full mode otherwise strips
# (2.6.0–2.8.1 release could not start the web server).
# 1) ServerBootstrap.channel(Class) → ReflectiveChannelFactory → getConstructor().
-keep class io.netty.channel.socket.nio.NioServerSocketChannel { public <init>(); }
# 2) @Sharable (ChannelHandlerAdapter.isSharable), @Skip (ChannelHandlerMask) and generic
#    signatures (TypeParameterMatcher, e.g. Ktor's NettyDirectEncoder on WebSocket upgrade) are read
#    reflectively; full mode keeps these attributes only on kept classes and may merge base classes.
#    Pin the Netty / Ktor-Netty classes that survive shrinking; unused code is still removed.
-keep,allowshrinking,allowobfuscation class io.netty.** { }
-keep,allowshrinking,allowobfuscation class io.ktor.server.netty.** { }
# 3) Netty also looks members up by name (MethodHandles.findStatic, e.g.
#    ConcurrentSkipListIntObjMultimap.acquireFenceFallback in <clinit>, which R8 sees as unused).
#    Keep every member of the Netty classes that ship, unrenamed; unused Netty classes still go.
-keepclassmembers class io.netty.** { *; }

# Web listener (Netty, optional TLS): optional OpenSSL/tcnative, JFR, log4j, LDAP and BlockHound
# integrations are never loaded on Android (TLS runs on the JDK/Conscrypt engine).
-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.ResultCallback
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSession
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn jdk.jfr.Category
-dontwarn jdk.jfr.DataAmount
-dontwarn jdk.jfr.Description
-dontwarn jdk.jfr.Enabled
-dontwarn jdk.jfr.Event
-dontwarn jdk.jfr.FlightRecorder
-dontwarn jdk.jfr.Label
-dontwarn jdk.jfr.MemoryAddress
-dontwarn jdk.jfr.Name
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn reactor.blockhound.BlockHound$Builder
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
