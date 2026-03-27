# AppProxy ProGuard Rules
# 添加 missing_rules.txt 中要求的规则

# Netty native TLS classes
-dontwarn io.netty.**
-keep class io.netty.** { *; }
-keepattributes Signature,InnerClasses

-dontwarn reactor.**
-keep class reactor.** { *; }

# Java management classes
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# JFR (Java Flight Recorder) classes
-dontwarn jdk.jfr.Category
-dontwarn jdk.jfr.DataAmount
-dontwarn jdk.jfr.Description
-dontwarn jdk.jfr.Enabled
-dontwarn jdk.jfr.Event
-dontwarn jdk.jfr.FlightRecorder
-dontwarn jdk.jfr.Label
-dontwarn jdk.jfr.MemoryAddress
-dontwarn jdk.jfr.Name

# Log4j classes
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper

# Bouncy Castle classes
-dontwarn org.bouncycastle.asn1.pkcs.PrivateKeyInfo
-dontwarn org.bouncycastle.openssl.PEMDecryptorProvider
-dontwarn org.bouncycastle.openssl.PEMEncryptedKeyPair
-dontwarn org.bouncycastle.openssl.PEMKeyPair
-dontwarn org.bouncycastle.openssl.PEMParser
-dontwarn org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
-dontwarn org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
-dontwarn org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
-dontwarn org.bouncycastle.operator.InputDecryptorProvider
-dontwarn org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo

# Conscrypt classes
-dontwarn org.conscrypt.BufferAllocator
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.HandshakeListener

# 保留 tun2socks 相关类（如果需要）
#-keep class com.github.xvfish.tun2socks.** { *; }
-dontwarn reactor.blockhound.integration.BlockHoundIntegration