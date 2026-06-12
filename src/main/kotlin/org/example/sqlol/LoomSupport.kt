package org.example.sqlol

import io.netty.channel.IoEventLoop
import io.netty.channel.IoHandlerFactory
import io.netty.channel.ManualIoEventLoop
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.util.internal.ThreadExecutorMap
import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import kotlin.reflect.KClass
import kotlin.reflect.KFunction1
import kotlin.reflect.jvm.isAccessible


object LoomSupport {

    val isSupported: Boolean = true
    private var failure: Throwable? = null






    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
     val __MH_OF_VIRTUAL: KFunction1<Executor, Thread.Builder.OfVirtual>? = runCatching {
//         java.lang.Thread.VirtualThreadScheduler
         "java.nio.channels.DefaultThreadPool.threadFactory"
        val t = Class.forName($$"java.lang.ThreadBuilders$VirtualThreadBuilder").kotlin as KClass<ThreadBuilders.VirtualThreadBuilder>
//        MethodHandles.lookup().findStatic()
//        KClass
//        Arena.ofShared().allocate()
//        t.
//        java.lang.Thread.VirtualThreadScheduler
//        val t = java.lang.ThreadBuilders.VirtualThreadBuilder::class
        ForkJoinPool::class
        val _constructor = t.constructors.firstOrNull {
            it.parameters.size == 1 && it.parameters[0].type.classifier == Executor::class
        }
        _constructor?.isAccessible = true
        _constructor as KFunction1<Executor, Thread.Builder.OfVirtual>?
    }.also { it.exceptionOrNull()?.printStackTrace() }.getOrNull()

//    val __

    fun checkSupported() {
        if (!isSupported) {
            MultiThreadIoEventLoopGroup(
                NioIoHandler.newFactory()
            )
            throw UnsupportedOperationException(
                "Virtual threads are not supported on this JVM, you may have to pass --enable-preview",
                failure
            )
        }
    }


    val parentSchedularScope = ScopedValue.newInstance<VirtualIoEventLoopGroup?>()
    val carrierThread = ScopedValue.newInstance<Thread>()


    class VirtualIoEventLoopGroup(

    ): MultiThreadIoEventLoopGroup(
        0,

        NioIoHandler.newFactory()
    ) {


        override fun newChild(
            executor: Executor?,
            ioHandlerFactory: IoHandlerFactory?,
            vararg args: Any?
        ): IoEventLoop {


            val delegate = ManualIoEventLoop(
                this,  null, ioHandlerFactory
            )
            val thread = __MH_OF_VIRTUAL!!(executor!!)
                .unstarted {
                    delegate.setOwningThread(Thread.currentThread())
                    ThreadExecutorMap.setCurrentExecutor(delegate)

                    while (!delegate.isShuttingDown) {

                    }
                    while (!delegate.isTerminated) {
                        delegate.runNow(-1)
                        Thread.yield()
                    }
                }
            thread.start()
//            delegate.setOwningThread(thread)
            return delegate
        }

    }

}

/*

//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package org.apache.commons.lang3;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.commons.lang3.function.Suppliers;

public final class SystemProperties {
    public static final String APPLE_AWT_ENABLE_TEMPLATE_IMAGES = "apple.awt.enableTemplateImages";
    public static final String AWT_TOOLKIT = "awt.toolkit";
    public static final String COM_SUN_JNDI_LDAP_OBJECT_TRUST_SERIAL_DATA = "com.sun.jndi.ldap.object.trustSerialData";
    public static final String COM_SUN_NET_HTTP_SERVER_HTTP_SERVER_PROVIDER = "com.sun.net.httpserver.HttpServerProvider";
    public static final String FILE_ENCODING = "file.encoding";
    public static final String FILE_SEPARATOR = "file.separator";
    public static final String FTP_NON_PROXY_HOST = "ftp.nonProxyHosts";
    public static final String FTP_PROXY_HOST = "ftp.proxyHost";
    public static final String FTP_PROXY_PORT = "ftp.proxyPort";
    public static final String HTTP_AGENT = "http.agent";
    public static final String HTTP_AUTH_DIGEST_CNONCE_REPEAT = "http.auth.digest.cnonceRepeat";
    public static final String HTTP_AUTH_DIGEST_RE_ENABLED_ALGORITHMS = "http.auth.digest.reEnabledAlgorithms";
    public static final String HTTP_AUTH_DIGEST_VALIDATE_PROXY = "http.auth.digest.validateProxy";
    public static final String HTTP_AUTH_DIGEST_VALIDATE_SERVER = "http.auth.digest.validateServer";
    public static final String HTTP_AUTH_NTLM_DOMAIN = "http.auth.ntlm.domain";
    public static final String HTTP_KEEP_ALIVE = "http.keepAlive";
    public static final String HTTP_KEEP_ALIVE_TIME_PROXY = "http.keepAlive.time.proxy";
    public static final String HTTP_KEEP_ALIVE_TIME_SERVER = "http.keepAlive.time.server";
    public static final String HTTP_MAX_CONNECTIONS = "http.maxConnections";
    public static final String HTTP_MAX_REDIRECTS = "http.maxRedirects";
    public static final String HTTP_NON_PROXY_HOSTS = "http.nonProxyHosts";
    public static final String HTTP_PROXY_HOST = "http.proxyHost";
    public static final String HTTP_PROXY_PORT = "http.proxyPort";
    public static final String HTTPS_PROXY_HOST = "https.proxyHost";
    public static final String HTTPS_PROXY_PORT = "https.proxyPort";
    public static final String JAVA_AWT_FONTS = "java.awt.fonts";
    public static final String JAVA_AWT_GRAPHICSENV = "java.awt.graphicsenv";
    public static final String JAVA_AWT_HEADLESS = "java.awt.headless";
    public static final String JAVA_AWT_PRINTERJOB = "java.awt.printerjob";
    public static final String JAVA_CLASS_PATH = "java.class.path";
    public static final String JAVA_CLASS_VERSION = "java.class.version";
    public static final String JAVA_COMPILER = "java.compiler";
    public static final String JAVA_CONTENT_HANDLER_PKGS = "java.content.handler.pkgs";
    public static final String JAVA_ENDORSED_DIRS = "java.endorsed.dirs";
    public static final String JAVA_EXT_DIRS = "java.ext.dirs";
    public static final String JAVA_HOME = "java.home";
    public static final String JAVA_IO_TMPDIR = "java.io.tmpdir";
    public static final String JAVA_LIBRARY_PATH = "java.library.path";
    public static final String JAVA_LOCALE_PROVIDERS = "java.locale.providers";
    public static final String JAVA_LOCALE_USE_OLD_ISO_CODES = "java.locale.useOldISOCodes";
    public static final String JAVA_NET_PREFER_IPV4_STACK = "java.net.preferIPv4Stack";
    public static final String JAVA_NET_PREFER_IPV6_ADDRESSES = "java.net.preferIPv6Addresses";
    public static final String JAVA_NET_SOCKS_PASSWORD = "java.net.socks.password";
    public static final String JAVA_NET_SOCKS_USER_NAME = "java.net.socks.username";
    public static final String JAVA_NET_USE_SYSTEM_PROXIES = "java.net.useSystemProxies";
    public static final String JAVA_NIO_CHANNELS_DEFAULT_THREAD_POOL_INITIAL_SIZE = "java.nio.channels.DefaultThreadPool.initialSize";
    public static final String JAVA_NIO_CHANNELS_DEFAULT_THREAD_POOL_THREAD_FACTORY = "java.nio.channels.DefaultThreadPool.threadFactory";
    public static final String JAVA_NIO_CHANNELS_SPI_ASYNCHRONOUS_CHANNEL_PROVIDER = "java.nio.channels.spi.AsynchronousChannelProvider";
    public static final String JAVA_NIO_CHANNELS_SPI_SELECTOR_PROVIDER = "java.nio.channels.spi.SelectorProvider";
    public static final String JAVA_NIO_FILE_SPI_DEFAULT_FILE_SYSTEM_PROVIDER = "java.nio.file.spi.DefaultFileSystemProvider";
    public static final String JAVA_PROPERTIES_DATE = "java.properties.date";
    public static final String JAVA_PROTOCOL_HANDLER_PKGS = "java.protocol.handler.pkgs";
    public static final String JAVA_RMI_SERVER_CODEBASE = "java.rmi.server.codebase";
    public static final String JAVA_RMI_SERVER_HOST_NAME = "java.rmi.server.hostname";
    public static final String JAVA_RMI_SERVER_RANDOM_IDS = "java.rmi.server.randomIDs";
    public static final String JAVA_RMI_SERVER_RMI_CLASS_LOADER_SPI = "java.rmi.server.RMIClassLoaderSpi";
    public static final String JAVA_RUNTIME_NAME = "java.runtime.name";
    public static final String JAVA_RUNTIME_VERSION = "java.runtime.version";
    public static final String JAVA_SECURITY_AUTH_LOGIN_CONFIG = "java.security.auth.login.config";
    public static final String JAVA_SECURITY_MANAGER = "java.security.manager";
    public static final String JAVA_SPECIFICATION_MAINTENANCE_VERSION = "java.specification.maintenance.version";
    public static final String JAVA_SPECIFICATION_NAME = "java.specification.name";
    public static final String JAVA_SPECIFICATION_VENDOR = "java.specification.vendor";
    public static final String JAVA_SPECIFICATION_VERSION = "java.specification.version";
    public static final String JAVA_SYSTEM_CLASS_LOADER = "java.system.class.loader";
    public static final String JAVA_TIME_ZONE_DEFAULT_ZONE_RULES_PROVIDER = "java.time.zone.DefaultZoneRulesProvider";
    public static final String JAVA_UTIL_CONCURRENT_FORK_JOIN_POOL_COMMON_EXCEPTION_HANDLER = "java.util.concurrent.ForkJoinPool.common.exceptionHandler";
    public static final String JAVA_UTIL_CONCURRENT_FORK_JOIN_POOL_COMMON_MAXIMUM_SPARES = "java.util.concurrent.ForkJoinPool.common.maximumSpares";
    public static final String JAVA_UTIL_CONCURRENT_FORK_JOIN_POOL_COMMON_PARALLELISM = "java.util.concurrent.ForkJoinPool.common.parallelism";
    public static final String JAVA_UTIL_CONCURRENT_FORK_JOIN_POOL_COMMON_THREAD_FACTORY = "java.util.concurrent.ForkJoinPool.common.threadFactory";
    public static final String JAVA_UTIL_CURRENCY_DATA = "java.util.currency.data";
    public static final String JAVA_UTIL_LOGGING_CONFIG_CLASS = "java.util.logging.config.class";
    public static final String JAVA_UTIL_LOGGING_CONFIG_FILE = "java.util.logging.config.file";
    public static final String JAVA_UTIL_LOGGING_SIMPLE_FORMATTER_FORMAT = "java.util.logging.simpleformatter.format";
    public static final String JAVA_UTIL_PREFS_PREFERENCES_FACTORY = "java.util.prefs.PreferencesFactory";
    public static final String JAVA_UTIL_PROPERTY_RESOURCE_BUNDLE_ENCODING = "java.util.PropertyResourceBundle.encoding";
    public static final String JAVA_VENDOR = "java.vendor";
    public static final String JAVA_VENDOR_URL = "java.vendor.url";
    public static final String JAVA_VENDOR_VERSION = "java.vendor.version";
    public static final String JAVA_VERSION = "java.version";
    public static final String JAVA_VERSION_DATE = "java.version.date";
    public static final String JAVA_VM_INFO = "java.vm.info";
    public static final String JAVA_VM_NAME = "java.vm.name";
    public static final String JAVA_VM_SPECIFICATION_NAME = "java.vm.specification.name";
    public static final String JAVA_VM_SPECIFICATION_VENDOR = "java.vm.specification.vendor";
    public static final String JAVA_VM_SPECIFICATION_VERSION = "java.vm.specification.version";
    public static final String JAVA_VM_VENDOR = "java.vm.vendor";
    public static final String JAVA_VM_VERSION = "java.vm.version";
    public static final String JAVA_XML_CONFIG_FILE = "java.xml.config.file";
    public static final String JAVAX_ACCESSIBILITY_ASSISTIVE_TECHNOLOGIES = "javax.accessibility.assistive_technologies";
    public static final String JAVAX_NET_SSL_SESSION_CACHE_SIZE = "javax.net.ssl.sessionCacheSize";
    public static final String JAVAX_RMI_SSL_CLIENT_ENABLED_CIPHER_SUITES = "javax.rmi.ssl.client.enabledCipherSuites";
    public static final String JAVAX_RMI_SSL_CLIENT_ENABLED_PROTOCOLS = "javax.rmi.ssl.client.enabledProtocols";
    public static final String JAVAX_SECURITY_AUTH_USE_SUBJECT_CREDS_ONLY = "javax.security.auth.useSubjectCredsOnly";
    public static final String JAVAX_SMART_CARD_IO_TERMINAL_FACTORY_DEFAULT_TYPE = "javax.smartcardio.TerminalFactory.DefaultType";
    public static final String JDBC_DRIVERS = "jdbc.drivers";
    public static final String JDK_HTTP_AUTH_PROXYING_DISABLED_SCHEMES = "jdk.http.auth.proxying.disabledSchemes";
    public static final String JDK_HTTP_AUTH_TUNNELING_DISABLED_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes";
    public static final String JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS = "jdk.httpclient.allowRestrictedHeaders";
    public static final String JDK_HTTP_CLIENT_AUTH_RETRY_LIMIT = "jdk.httpclient.auth.retrylimit";
    public static final String JDK_HTTP_CLIENT_BUF_SIZE = "jdk.httpclient.bufsize";
    public static final String JDK_HTTP_CLIENT_CONNECTION_POOL_SIZE = "jdk.httpclient.connectionPoolSize";
    public static final String JDK_HTTP_CLIENT_CONNECTION_WINDOW_SIZE = "jdk.httpclient.connectionWindowSize";
    public static final String JDK_HTTP_CLIENT_DISABLE_RETRY_CONNECT = "jdk.httpclient.disableRetryConnect";
    public static final String JDK_HTTP_CLIENT_ENABLE_ALL_METHOD_RETRY = "jdk.httpclient.enableAllMethodRetry";
    public static final String JDK_HTTP_CLIENT_ENABLE_PUSH = "jdk.httpclient.enablepush";
    public static final String JDK_HTTP_CLIENT_HPACK_MAX_HEADER_TABLE_SIZE = "jdk.httpclient.hpack.maxheadertablesize";
    public static final String JDK_HTTP_CLIENT_HTTP_CLIENT_LOG = "jdk.httpclient.HttpClient.log";
    public static final String JDK_HTTP_CLIENT_KEEP_ALIVE_TIMEOUT = "jdk.httpclient.keepalive.timeout";
    public static final String JDK_HTTP_CLIENT_KEEP_ALIVE_TIMEOUT_H2 = "jdk.httpclient.keepalive.timeout.h2";
    public static final String JDK_HTTP_CLIENT_MAX_FRAME_SIZE = "jdk.httpclient.maxframesize";
    public static final String JDK_HTTP_CLIENT_MAX_STREAMS = "jdk.httpclient.maxstreams";
    public static final String JDK_HTTP_CLIENT_RECEIVE_BUFFER_SIZE = "jdk.httpclient.receiveBufferSize";
    public static final String JDK_HTTP_CLIENT_REDIRECTS_RETRY_LIMIT = "jdk.httpclient.redirects.retrylimit";
    public static final String JDK_HTTP_CLIENT_SEND_BUFFER_SIZE = "jdk.httpclient.sendBufferSize";
    public static final String JDK_HTTP_CLIENT_WEB_SOCKET_WRITE_BUFFER_SIZE = "jdk.httpclient.websocket.writeBufferSize";
    public static final String JDK_HTTP_CLIENT_WINDOW_SIZE = "jdk.httpclient.windowsize";
    public static final String JDK_HTTP_SERVER_MAX_CONNECTIONS = "jdk.httpserver.maxConnections";
    public static final String JDK_HTTPS_NEGOTIATE_CBT = "jdk.https.negotiate.cbt";
    public static final String JDK_INCLUDE_IN_EXCEPTIONS = "jdk.includeInExceptions";
    public static final String JDK_INTERNAL_HTTP_CLIENT_DISABLE_HOST_NAME_VERIFICATION = "jdk.internal.httpclient.disableHostnameVerification";
    public static final String JDK_IO_PERMISSIONS_USE_CANONICAL_PATH = "jdk.io.permissionsUseCanonicalPath";
    public static final String JDK_JNDI_LDAP_OBJECT_FACTORIES_FILTER = "jdk.jndi.ldap.object.factoriesFilter";
    public static final String JDK_JNDI_OBJECT_FACTORIES_FILTER = "jdk.jndi.object.factoriesFilter";
    public static final String JDK_JNDI_RMI_OBJECT_FACTORIES_FILTER = "jdk.jndi.rmi.object.factoriesFilter";
    public static final String JDK_MODULE_MAIN = "jdk.module.main";
    public static final String JDK_MODULE_MAIN_CLASS = "jdk.module.main.class";
    public static final String JDK_MODULE_PATH = "jdk.module.path";
    public static final String JDK_MODULE_UPGRADE_PATH = "jdk.module.upgrade.path";
    public static final String JDK_NET_UNIX_DOMAIN_TMPDIR = "jdk.net.unixdomain.tmpdir";
    public static final String JDK_NET_URL_CLASS_PATH_SHOW_IGNORED_CLASS_PATH_ENTRIES = "jdk.net.URLClassPath.showIgnoredClassPathEntries";
    public static final String JDK_SERIAL_FILTER = "jdk.serialFilter";
    public static final String JDK_SERIAL_FILTER_FACTORY = "jdk.serialFilterFactory";
    public static final String JDK_TLS_CLIENT_SIGNATURE_SCHEMES = "jdk.tls.client.SignatureSchemes";
    public static final String JDK_TLS_NAMED_GROUPS = "jdk.tls.namedGroups";
    public static final String JDK_TLS_SERVER_SIGNATURE_SCHEMES = "jdk.tls.server.SignatureSchemes";
    public static final String JDK_VIRTUAL_THREAD_SCHEDULER_MAXPOOLSIZE = "jdk.virtualThreadScheduler.maxPoolSize";
    public static final String JDK_VIRTUAL_THREAD_SCHEDULER_PARALLELISM = "jdk.virtualThreadScheduler.parallelism";
    public static final String JDK_XML_CDATA_CHUNK_SIZE = "jdk.xml.cdataChunkSize";
    public static final String JDK_XML_DTD_SUPPORT = "jdk.xml.dtd.support";
    public static final String JDK_XML_ELEMENT_ATTRIBUTE_LIMIT = "jdk.xml.elementAttributeLimit";
    public static final String JDK_XML_ENABLE_EXTENSION_FUNCTIONS = "jdk.xml.enableExtensionFunctions";
    public static final String JDK_XML_ENTITY_EXPANSION_LIMIT = "jdk.xml.entityExpansionLimit";
    public static final String JDK_XML_ENTITY_REPLACEMENT_LIMIT = "jdk.xml.entityReplacementLimi_t";
    public static final String JDK_XML_IS_STANDALONE = "jdk.xml.isStandalone";
    public static final String JDK_XML_JDK_CATALOG_RESOLVE = "jdk.xml.jdkcatalog.resolve";
    public static final String JDK_XML_MAX_ELEMENT_DEPTH = "jdk.xml.maxElementDepth";
    public static final String JDK_XML_MAX_GENERAL_ENTITY_SIZE_LIMIT = "jdk.xml.maxGeneralEntitySizeLimit";
    public static final String JDK_XML_MAX_OCCUR_LIMIT = "jdk.xml.maxOccurLimit";
    public static final String JDK_XML_MAX_PARAMETER_ENTITY_SIZE_LIMIT = "jdk.xml.maxParameterEntitySizeLimit";
    public static final String JDK_XML_MAX_XML_NAME_LIMIT = "jdk.xml.maxXMLNameLimit";
    public static final String JDK_XML_OVERRIDE_DEFAULT_PARSER = "jdk.xml.overrideDefaultParser";
    public static final String JDK_XML_RESET_SYMBOL_TABLE = "jdk.xml.resetSymbolTable";
    public static final String JDK_XML_TOTAL_ENTITY_SIZE_LIMIT = "jdk.xml.totalEntitySizeLimit";
    public static final String JDK_XML_XSLTC_IS_STANDALONE = "jdk.xml.xsltcIsStandalone";
    public static final String LINE_SEPARATOR = "line.separator";
    public static final String NATIVE_ENCODING = "native.encoding";
    public static final String NETWORK_ADDRESS_CACHE_NEGATIVE_TTL = "networkaddress.cache.negative.ttl";
    public static final String NETWORK_ADDRESS_CACHE_STALE_TTL = "networkaddress.cache.stale.ttl";
    public static final String NETWORK_ADDRESS_CACHE_TTL = "networkaddress.cache.ttl";
    public static final String ORG_JCP_XML_DSIG_SECURE_VALIDATION = "org.jcp.xml.dsig.securevalidation";
    public static final String ORG_OPENJDK_JAVA_UTIL_STREAM_TRIPWIRE = "org.openjdk.java.util.stream.tripwire";
    public static final String OS_ARCH = "os.arch";
    public static final String OS_NAME = "os.name";
    public static final String OS_VERSION = "os.version";
    public static final String PATH_SEPARATOR = "path.separator";
    public static final String SOCKS_PROXY_HOST = "socksProxyHost";
    public static final String SOCKS_PROXY_PORT = "socksProxyPort";
    public static final String SOCKS_PROXY_VERSION = "socksProxyVersion";
    public static final String STDERR_ENCODING = "stderr.encoding";
    public static final String STDOUT_ENCODING = "stdout.encoding";
    public static final String SUN_NET_HTTP_SERVER_DRAIN_AMOUNT = "sun.net.httpserver.drainAmount";
    public static final String SUN_NET_HTTP_SERVER_IDLE_INTERVAL = "sun.net.httpserver.idleInterval";
    public static final String SUN_NET_HTTP_SERVER_MAX_IDLE_CONNECTIONS = "sun.net.httpserver.maxIdleConnections";
    public static final String SUN_NET_HTTP_SERVER_MAX_REQ_HEADERS = "sun.net.httpserver.maxReqHeaders";
    public static final String SUN_NET_HTTP_SERVER_MAX_REQ_TIME = "sun.net.httpserver.maxReqTime";
    public static final String SUN_NET_HTTP_SERVER_MAX_RSP_TIME = "sun.net.httpserver.maxRspTime";
    public static final String SUN_NET_HTTP_SERVER_NO_DELAY = "sun.net.httpserver.nodelay";
    public static final String SUN_SECURITY_KRB5_PRINCIPAL = "sun.security.krb5.principal";
    public static final String USER_COUNTRY = "user.country";
    public static final String USER_DIR = "user.dir";
    public static final String USER_EXTENSIONS = "user.extensions";
    public static final String USER_HOME = "user.home";
    public static final String USER_LANGUAGE = "user.language";
    public static final String USER_NAME = "user.name";
    public static final String USER_REGION = "user.region";
    public static final String USER_SCRIPT = "user.script";
    public static final String USER_TIMEZONE = "user.timezone";
    public static final String USER_VARIANT = "user.variant";

    public static String getAppleAwtEnableTemplateImages() {
        return getProperty("apple.awt.enableTemplateImages");
    }

    public static String getAwtToolkit() {
        return getProperty("awt.toolkit");
    }

    public static boolean getBoolean(String key, BooleanSupplier defaultIfAbsent) {
        String str = getProperty(key);
        return str == null ? defaultIfAbsent != null && defaultIfAbsent.getAsBoolean() : Boolean.parseBoolean(str);
    }

    public static String getComSunJndiLdapObjectTrustSerialData() {
        return getProperty("com.sun.jndi.ldap.object.trustSerialData");
    }

    public static String getComSunNetHttpServerHttpServerProvider() {
        return getProperty("com.sun.net.httpserver.HttpServerProvider");
    }

    public static String getFileEncoding() {
        return getProperty("file.encoding");
    }

    public static String getFileSeparator() {
        return getProperty("file.separator");
    }

    public static String getFtpNonProxyHost() {
        return getProperty("ftp.nonProxyHosts");
    }

    public static String getFtpProxyHost() {
        return getProperty("ftp.proxyHost");
    }

    public static String getFtpProxyPort() {
        return getProperty("ftp.proxyPort");
    }

    public static String getHttpAgent() {
        return getProperty("http.agent");
    }

    public static String getHttpAuthDigestCnonceRepeat() {
        return getProperty("http.auth.digest.cnonceRepeat");
    }

    public static String getHttpAuthDigestReenabledAlgorithms() {
        return getProperty("http.auth.digest.reEnabledAlgorithms");
    }

    public static String getHttpAuthDigestValidateProxy() {
        return getProperty("http.auth.digest.validateProxy");
    }

    public static String getHttpAuthDigestValidateServer() {
        return getProperty("http.auth.digest.validateServer");
    }

    public static String getHttpAuthNtlmDomain() {
        return getProperty("http.auth.ntlm.domain");
    }

    public static String getHttpKeepAlive() {
        return getProperty("http.keepAlive");
    }

    public static String getHttpKeepAliveTimeProxy() {
        return getProperty("http.keepAlive.time.proxy");
    }

    public static String getHttpKeepAliveTimeServer() {
        return getProperty("http.keepAlive.time.server");
    }

    public static String getHttpMaxConnections() {
        return getProperty("http.maxConnections");
    }

    public static String getHttpMaxRedirects() {
        return getProperty("http.maxRedirects");
    }

    public static String getHttpNonProxyHosts() {
        return getProperty("http.nonProxyHosts");
    }

    public static String getHttpProxyHost() {
        return getProperty("http.proxyHost");
    }

    public static String getHttpProxyPort() {
        return getProperty("http.proxyPort");
    }

    public static String getHttpsProxyHost() {
        return getProperty("https.proxyHost");
    }

    public static String getHttpsProxyPort() {
        return getProperty("https.proxyPort");
    }

    public static int getInt(String key, IntSupplier defaultIfAbsent) {
        String str = getProperty(key);
        return str == null ? (defaultIfAbsent != null ? defaultIfAbsent.getAsInt() : 0) : Integer.parseInt(str);
    }

    public static String getJavaAwtFonts() {
        return getProperty("java.awt.fonts");
    }

    public static String getJavaAwtGraphicsenv() {
        return getProperty("java.awt.graphicsenv");
    }

    public static String getJavaAwtHeadless() {
        return getProperty("java.awt.headless");
    }

    public static String getJavaAwtPrinterjob() {
        return getProperty("java.awt.printerjob");
    }

    public static String getJavaClassPath() {
        return getProperty("java.class.path");
    }

    public static String getJavaClassVersion() {
        return getProperty("java.class.version");
    }

    public static String getJavaCompiler() {
        return getProperty("java.compiler");
    }

    public static String getJavaContentHandlerPkgs() {
        return getProperty("java.content.handler.pkgs");
    }

    public static String getJavaEndorsedDirs() {
        return getProperty("java.endorsed.dirs");
    }

    public static String getJavaExtDirs() {
        return getProperty("java.ext.dirs");
    }

    public static String getJavaHome() {
        return getProperty("java.home");
    }

    public static String getJavaIoTmpdir() {
        return getProperty("java.io.tmpdir");
    }

    public static String getJavaLibraryPath() {
        return getProperty("java.library.path");
    }

    public static String getJavaLocaleProviders() {
        return getProperty("java.locale.providers");
    }

    public static String getJavaLocaleUseOldIsoCodes() {
        return getProperty("java.locale.useOldISOCodes");
    }

    public static String getJavaNetPreferIpv4Stack() {
        return getProperty("java.net.preferIPv4Stack");
    }

    public static String getJavaNetPreferIpv6Addresses() {
        return getProperty("java.net.preferIPv6Addresses");
    }

    public static String getJavaNetSocksPassword() {
        return getProperty("java.net.socks.password");
    }

    public static String getJavaNetSocksUserName() {
        return getProperty("java.net.socks.username");
    }

    public static String getJavaNetUseSystemProxies() {
        return getProperty("java.net.useSystemProxies");
    }

    public static String getJavaNioChannelsDefaultThreadPoolInitialSize() {
        return getProperty("java.nio.channels.DefaultThreadPool.initialSize");
    }

    public static String getJavaNioChannelsDefaultThreadPoolThreadFactory() {
        return getProperty("java.nio.channels.DefaultThreadPool.threadFactory");
    }

    public static String getJavaNioChannelsSpiAsynchronousChannelProvider() {
        return getProperty("java.nio.channels.spi.AsynchronousChannelProvider");
    }

    public static String getJavaNioChannelsSpiSelectorProvider() {
        return getProperty("java.nio.channels.spi.SelectorProvider");
    }

    public static String getJavaNioFileSpiDefaultFileSystemProvider() {
        return getProperty("java.nio.file.spi.DefaultFileSystemProvider");
    }

    public static String getJavaPropertiesDate() {
        return getProperty("java.properties.date");
    }

    public static String getJavaProtocolHandlerPkgs() {
        return getProperty("java.protocol.handler.pkgs");
    }

    public static String getJavaRmiServerCodebase() {
        return getProperty("java.rmi.server.codebase");
    }

    public static String getJavaRmiServerHostName() {
        return getProperty("java.rmi.server.hostname");
    }

    public static String getJavaRmiServerRandomIds() {
        return getProperty("java.rmi.server.randomIDs");
    }

    public static String getJavaRmiServerRmiClassLoaderSpi() {
        return getProperty("java.rmi.server.RMIClassLoaderSpi");
    }

    public static String getJavaRuntimeName() {
        return getProperty("java.runtime.name");
    }

    public static String getJavaRuntimeVersion() {
        return getProperty("java.runtime.version");
    }

    public static String getJavaSecurityAuthLoginConfig() {
        return getProperty("java.security.auth.login.config");
    }

    public static String getJavaSecurityManager() {
        return getProperty("java.security.manager");
    }

    public static String getJavaSpecificationMaintenanceVersion() {
        return getProperty("java.specification.maintenance.version");
    }

    public static String getJavaSpecificationName() {
        return getProperty("java.specification.name");
    }

    public static String getJavaSpecificationVendor() {
        return getProperty("java.specification.vendor");
    }

    public static String getJavaSpecificationVersion() {
        return getProperty("java.specification.version");
    }

    public static String getJavaSpecificationVersion(String defaultValue) {
        return getProperty("java.specification.version", defaultValue);
    }

    public static String getJavaSystemClassLoader() {
        return getProperty("java.system.class.loader");
    }

    public static String getJavaTimeZoneDefaultZoneRulesProvider() {
        return getProperty("java.time.zone.DefaultZoneRulesProvider");
    }

    public static String getJavaUtilConcurrentForkJoinPoolCommonExceptionHandler() {
        return getProperty("java.util.concurrent.ForkJoinPool.common.exceptionHandler");
    }

    public static String getJavaUtilConcurrentForkJoinPoolCommonMaximumSpares() {
        return getProperty("java.util.concurrent.ForkJoinPool.common.maximumSpares");
    }

    public static String getJavaUtilConcurrentForkJoinPoolCommonParallelism() {
        return getProperty("java.util.concurrent.ForkJoinPool.common.parallelism");
    }

    public static String getJavaUtilConcurrentForkJoinPoolCommonThreadFactory() {
        return getProperty("java.util.concurrent.ForkJoinPool.common.threadFactory");
    }

    public static String getJavaUtilCurrencyData() {
        return getProperty("java.util.currency.data");
    }

    public static String getJavaUtilLoggingConfigClass() {
        return getProperty("java.util.logging.config.class");
    }

    public static String getJavaUtilLoggingConfigFile() {
        return getProperty("java.util.logging.config.file");
    }

    public static String getJavaUtilLoggingSimpleFormatterFormat() {
        return getProperty("java.util.logging.simpleformatter.format");
    }

    public static String getJavaUtilPrefsPreferencesFactory() {
        return getProperty("java.util.prefs.PreferencesFactory");
    }

    public static String getJavaUtilPropertyResourceBundleEncoding() {
        return getProperty("java.util.PropertyResourceBundle.encoding");
    }

    public static String getJavaVendor() {
        return getProperty("java.vendor");
    }

    public static String getJavaVendorUrl() {
        return getProperty("java.vendor.url");
    }

    public static String getJavaVendorVersion() {
        return getProperty("java.vendor.version");
    }

    public static String getJavaVersion() {
        return getProperty("java.version");
    }

    public static String getJavaVersionDate() {
        return getProperty("java.version.date");
    }

    public static String getJavaVmInfo() {
        return getProperty("java.vm.info");
    }

    public static String getJavaVmName() {
        return getProperty("java.vm.name");
    }

    public static String getJavaVmSpecificationName() {
        return getProperty("java.vm.specification.name");
    }

    public static String getJavaVmSpecificationVendor() {
        return getProperty("java.vm.specification.vendor");
    }

    public static String getJavaVmSpecificationVersion() {
        return getProperty("java.vm.specification.version");
    }

    public static String getJavaVmVendor() {
        return getProperty("java.vm.vendor");
    }

    public static String getJavaVmVersion() {
        return getProperty("java.vm.version");
    }

    public static String getJavaxAccessibilityAssistiveTechnologies() {
        return getProperty("javax.accessibility.assistive_technologies");
    }

    public static String getJavaXmlConfigFile() {
        return getProperty("java.xml.config.file");
    }

    public static String getJavaxNetSslSessionCacheSize() {
        return getProperty("javax.net.ssl.sessionCacheSize");
    }

    public static String getJavaxRmiSslClientEnabledCipherSuites() {
        return getProperty("javax.rmi.ssl.client.enabledCipherSuites");
    }

    public static String getJavaxRmiSslClientEnabledProtocols() {
        return getProperty("javax.rmi.ssl.client.enabledProtocols");
    }

    public static String getJavaxSecurityAuthUseSubjectCredsOnly() {
        return getProperty("javax.security.auth.useSubjectCredsOnly");
    }

    public static String getJavaxSmartCardIoTerminalFactoryDefaultType() {
        return getProperty("javax.smartcardio.TerminalFactory.DefaultType");
    }

    public static String getJdbcDrivers() {
        return getProperty("jdbc.drivers");
    }

    public static String getJdkHttpAuthProxyingDisabledSchemes() {
        return getProperty("jdk.http.auth.proxying.disabledSchemes");
    }

    public static String getJdkHttpAuthTunnelingDisabledSchemes() {
        return getProperty("jdk.http.auth.tunneling.disabledSchemes");
    }

    public static String getJdkHttpClientAllowRestrictedHeaders() {
        return getProperty("jdk.httpclient.allowRestrictedHeaders");
    }

    public static String getJdkHttpClientAuthRetryLimit() {
        return getProperty("jdk.httpclient.auth.retrylimit");
    }

    public static String getJdkHttpClientBufSize() {
        return getProperty("jdk.httpclient.bufsize");
    }

    public static String getJdkHttpClientConnectionPoolSize() {
        return getProperty("jdk.httpclient.connectionPoolSize");
    }

    public static String getJdkHttpClientConnectionWindowSize() {
        return getProperty("jdk.httpclient.connectionWindowSize");
    }

    public static String getJdkHttpClientDisableRetryConnect() {
        return getProperty("jdk.httpclient.disableRetryConnect");
    }

    public static String getJdkHttpClientEnableAllMethodRetry() {
        return getProperty("jdk.httpclient.enableAllMethodRetry");
    }

    public static String getJdkHttpClientEnablePush() {
        return getProperty("jdk.httpclient.enablepush");
    }

    public static String getJdkHttpClientHpackMaxHeaderTableSize() {
        return getProperty("jdk.httpclient.hpack.maxheadertablesize");
    }

    public static String getJdkHttpClientHttpClientLog() {
        return getProperty("jdk.httpclient.HttpClient.log");
    }

    public static String getJdkHttpClientKeepAliveTimeout() {
        return getProperty("jdk.httpclient.keepalive.timeout");
    }

    public static String getJdkHttpClientKeepAliveTimeoutH2() {
        return getProperty("jdk.httpclient.keepalive.timeout.h2");
    }

    public static String getJdkHttpClientMaxFrameSize() {
        return getProperty("jdk.httpclient.maxframesize");
    }

    public static String getJdkHttpClientMaxStreams() {
        return getProperty("jdk.httpclient.maxstreams");
    }

    public static String getJdkHttpClientReceiveBufferSize() {
        return getProperty("jdk.httpclient.receiveBufferSize");
    }

    public static String getJdkHttpClientRedirectsRetryLimit() {
        return getProperty("jdk.httpclient.redirects.retrylimit");
    }

    public static String getJdkHttpClientSendBufferSize() {
        return getProperty("jdk.httpclient.sendBufferSize");
    }

    public static String getJdkHttpClientWebSocketWriteBufferSize() {
        return getProperty("jdk.httpclient.websocket.writeBufferSize");
    }

    public static String getJdkHttpClientWindowSize() {
        return getProperty("jdk.httpclient.windowsize");
    }

    public static String getJdkHttpServerMaxConnections() {
        return getProperty("jdk.httpserver.maxConnections");
    }

    public static String getJdkHttpsNegotiateCbt() {
        return getProperty("jdk.https.negotiate.cbt");
    }

    public static String getJdkIncludeInExceptions() {
        return getProperty("jdk.includeInExceptions");
    }

    public static String getJdkInternalHttpClientDisableHostNameVerification() {
        return getProperty("jdk.internal.httpclient.disableHostnameVerification");
    }

    public static String getJdkIoPermissionsUseCanonicalPath() {
        return getProperty("jdk.io.permissionsUseCanonicalPath");
    }

    public static String getJdkJndiLdapObjectFactoriesFilter() {
        return getProperty("jdk.jndi.ldap.object.factoriesFilter");
    }

    public static String getJdkJndiObjectFactoriesFilter() {
        return getProperty("jdk.jndi.object.factoriesFilter");
    }

    public static String getJdkJndiRmiObjectFactoriesFilter() {
        return getProperty("jdk.jndi.rmi.object.factoriesFilter");
    }

    public static String getJdkModuleMain() {
        return getProperty("jdk.module.main");
    }

    public static String getJdkModuleMainClass() {
        return getProperty("jdk.module.main.class");
    }

    public static String getJdkModulePath() {
        return getProperty("jdk.module.path");
    }

    public static String getJdkModuleUpgradePath() {
        return getProperty("jdk.module.upgrade.path");
    }

    public static String getJdkNetUnixDomainTmpDir() {
        return getProperty("jdk.net.unixdomain.tmpdir");
    }

    public static String getJdkNetUrlClassPathShowIgnoredClassPathEntries() {
        return getProperty("jdk.net.URLClassPath.showIgnoredClassPathEntries");
    }

    public static String getJdkSerialFilter() {
        return getProperty("jdk.serialFilter");
    }

    public static String getJdkSerialFilterFactory() {
        return getProperty("jdk.serialFilterFactory");
    }

    public static String getJdkTlsClientSignatureSchemes() {
        return getProperty("jdk.tls.client.SignatureSchemes");
    }

    public static String getJdkTlsNamedGroups() {
        return getProperty("jdk.tls.namedGroups");
    }

    public static String getJdkTlsServerSignatureSchemes() {
        return getProperty("jdk.tls.server.SignatureSchemes");
    }

    public static String getJdkVirtualThreadSchedulerMaxPoolSize() {
        return getProperty("jdk.virtualThreadScheduler.maxPoolSize");
    }

    public static String getJdkVirtualThreadSchedulerParallelism() {
        return getProperty("jdk.virtualThreadScheduler.parallelism");
    }

    public static String getJdkXmlCdataChunkSize() {
        return getProperty("jdk.xml.cdataChunkSize");
    }

    public static String getJdkXmlDtdSupport() {
        return getProperty("jdk.xml.dtd.support");
    }

    public static String getJdkXmlElementAttributeLimit() {
        return getProperty("jdk.xml.elementAttributeLimit");
    }

    public static String getJdkXmlEnableExtensionFunctions() {
        return getProperty("jdk.xml.enableExtensionFunctions");
    }

    public static String getJdkXmlEntityExpansionLimit() {
        return getProperty("jdk.xml.entityExpansionLimit");
    }

    public static String getJdkXmlEntityReplacementLimit() {
        return getProperty("jdk.xml.entityReplacementLimi_t");
    }

    public static String getJdkXmlIsStandalone() {
        return getProperty("jdk.xml.isStandalone");
    }

    public static String getJdkXmlJdkCatalogResolve() {
        return getProperty("jdk.xml.jdkcatalog.resolve");
    }

    public static String getJdkXmlMaxElementDepth() {
        return getProperty("jdk.xml.maxElementDepth");
    }

    public static String getJdkXmlMaxGeneralEntitySizeLimit() {
        return getProperty("jdk.xml.maxGeneralEntitySizeLimit");
    }

    public static String getJdkXmlMaxOccurLimit() {
        return getProperty("jdk.xml.maxOccurLimit");
    }

    public static String getJdkXmlMaxParameterEntitySizeLimit() {
        return getProperty("jdk.xml.maxParameterEntitySizeLimit");
    }

    public static String getJdkXmlMaxXmlNameLimit() {
        return getProperty("jdk.xml.maxXMLNameLimit");
    }

    public static String getJdkXmlOverrideDefaultParser() {
        return getProperty("jdk.xml.overrideDefaultParser");
    }

    public static String getJdkXmlResetSymbolTable() {
        return getProperty("jdk.xml.resetSymbolTable");
    }

    public static String getJdkXmlTotalEntitySizeLimit() {
        return getProperty("jdk.xml.totalEntitySizeLimit");
    }

    public static String getJdkXmlXsltcIsStandalone() {
        return getProperty("jdk.xml.xsltcIsStandalone");
    }

    public static String getLineSeparator() {
        return getProperty("line.separator");
    }

    public static String getLineSeparator(Supplier<String> defaultIfAbsent) {
        return getProperty("line.separator", defaultIfAbsent);
    }

    public static long getLong(String key, LongSupplier defaultIfAbsent) {
        String str = getProperty(key);
        return str == null ? (defaultIfAbsent != null ? defaultIfAbsent.getAsLong() : 0L) : Long.parseLong(str);
    }

    public static String getNativeEncoding() {
        return getProperty("native.encoding");
    }

    public static String getNetworkAddressCacheNegativeTtl() {
        return getProperty("networkaddress.cache.negative.ttl");
    }

    public static String getNetworkAddressCacheStaleTtl() {
        return getProperty("networkaddress.cache.stale.ttl");
    }

    public static String getNetworkAddressCacheTtl() {
        return getProperty("networkaddress.cache.ttl");
    }

    public static String getOrgJcpXmlDsigSecureValidation() {
        return getProperty("org.jcp.xml.dsig.securevalidation");
    }

    public static String getOrgOpenJdkJavaUtilStreamTripwire() {
        return getProperty("org.openjdk.java.util.stream.tripwire");
    }

    public static String getOsArch() {
        return getProperty("os.arch");
    }

    public static String getOsName() {
        return getProperty("os.name");
    }

    public static String getOsVersion() {
        return getProperty("os.version");
    }

    public static String getPathSeparator() {
        return getProperty("path.separator");
    }

    public static String getProperty(String property) {
        return getProperty(property, Suppliers.nul());
    }

    static String getProperty(String property, String defaultIfAbsent) {
        return getProperty(property, (Supplier)(() -> defaultIfAbsent));
    }

    static String getProperty(String property, Supplier<String> defaultIfAbsent) {
        try {
            if (StringUtils.isEmpty(property)) {
                return (String)Suppliers.get(defaultIfAbsent);
            } else {
                String value = System.getProperty(property);
                return (String)StringUtils.getIfEmpty(value, defaultIfAbsent);
            }
        } catch (SecurityException var3) {
            return (String)defaultIfAbsent.get();
        }
    }

    public static String getSocksProxyHost() {
        return getProperty("socksProxyHost");
    }

    public static String getSocksProxyPort() {
        return getProperty("socksProxyPort");
    }

    public static String getSocksProxyVersion() {
        return getProperty("socksProxyVersion");
    }

    public static String getStdErrEncoding() {
        return getProperty("stderr.encoding");
    }

    public static String getStdOutEncoding() {
        return getProperty("stdout.encoding");
    }

    public static String getSunNetHttpServerDrainAmount() {
        return getProperty("sun.net.httpserver.drainAmount");
    }

    public static String getSunNetHttpServerIdleInterval() {
        return getProperty("sun.net.httpserver.idleInterval");
    }

    public static String getSunNetHttpServerMaxIdleConnections() {
        return getProperty("sun.net.httpserver.maxIdleConnections");
    }

    public static String getSunNetHttpServerMaxReqHeaders() {
        return getProperty("sun.net.httpserver.maxReqHeaders");
    }

    public static String getSunNetHttpServerMaxReqTime() {
        return getProperty("sun.net.httpserver.maxReqTime");
    }

    public static String getSunNetHttpServerMaxRspTime() {
        return getProperty("sun.net.httpserver.maxRspTime");
    }

    public static String getSunNetHttpServerNoDelay() {
        return getProperty("sun.net.httpserver.nodelay");
    }

    public static String getSunSecurityKrb5Principal() {
        return getProperty("sun.security.krb5.principal");
    }

    public static String getUserCountry() {
        return getProperty("user.country");
    }

    public static String getUserDir() {
        return getProperty("user.dir");
    }

    public static String getUserExtensions() {
        return getProperty("user.extensions");
    }

    public static String getUserHome() {
        return getProperty("user.home");
    }

    public static String getUserLanguage() {
        return getProperty("user.language");
    }

    public static String getUserName() {
        return getProperty("user.name");
    }

    public static String getUserName(String defaultValue) {
        return getProperty("user.name", defaultValue);
    }

    public static String getUserRegion() {
        return getProperty("user.region");
    }

    public static String getUserScript() {
        return getProperty("user.script");
    }

    public static String getUserTimezone() {
        return getProperty("user.timezone");
    }

    public static String getUserVariant() {
        return getProperty("user.variant");
    }

    /** @deprecated */
    @Deprecated
    public SystemProperties() {
    }
}


* */