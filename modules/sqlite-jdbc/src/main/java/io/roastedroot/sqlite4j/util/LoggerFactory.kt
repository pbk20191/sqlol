package io.roastedroot.sqlite4j.util

import org.slf4j.LoggerFactory
import java.util.function.Supplier
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A factory for [Logger] instances that uses SLF4J if present, falling back on a
 * java.util.logging implementation otherwise.
 */
object LoggerFactory {
    val USE_SLF4J: Boolean

    init {
        var useSLF4J: Boolean
        try {
            Class.forName("org.slf4j.Logger")
            useSLF4J = true
        } catch (e: Exception) {
            useSLF4J = false
        }
        USE_SLF4J = useSLF4J
    }

    /**
     * Get a [Logger] instance for the given host class.
     * 
     * @param hostClass the host class from which log messages will be issued
     * @return a Logger
     */
    @JvmStatic
    fun getLogger(hostClass: Class<*>): io.roastedroot.sqlite4j.util.Logger {
        if (USE_SLF4J) {
            return SLF4JLogger(hostClass)
        }

        return JDKLogger(hostClass)
    }

    private class JDKLogger(hostClass: Class<*>) : io.roastedroot.sqlite4j.util.Logger {
        val logger: Logger

        init {
            logger = Logger.getLogger(hostClass.getCanonicalName())
        }

        override fun trace(message: Supplier<String>) {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST, message.get())
            }
        }

        override fun info(message: Supplier<String>) {
            if (logger.isLoggable(Level.INFO)) {
                logger.log(Level.INFO, message.get())
            }
        }

        override fun warn(message: Supplier<String>) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.log(Level.WARNING, message.get())
            }
        }

        override fun error(message: Supplier<String>, t: Throwable) {
            if (logger.isLoggable(Level.SEVERE)) {
                logger.log(Level.SEVERE, message.get(), t)
            }
        }
    }

    private class SLF4JLogger(hostClass: Class<*>) : io.roastedroot.sqlite4j.util.Logger {
        val logger: org.slf4j.Logger

        init {
            logger = LoggerFactory.getLogger(hostClass)
        }

        override fun trace(message: Supplier<String>) {
            if (logger.isTraceEnabled()) {
                logger.trace(message.get())
            }
        }

        override fun info(message: Supplier<String>) {
            if (logger.isInfoEnabled()) {
                logger.info(message.get())
            }
        }

        override fun warn(message: Supplier<String>) {
            if (logger.isWarnEnabled()) {
                logger.warn(message.get())
            }
        }

        override fun error(message: Supplier<String>, t: Throwable) {
            if (logger.isErrorEnabled()) {
                logger.error(message.get(), t)
            }
        }
    }
}
