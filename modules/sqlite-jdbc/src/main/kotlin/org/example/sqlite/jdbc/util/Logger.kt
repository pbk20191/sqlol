package org.example.sqlite.jdbc.util

import java.util.function.Supplier

/** A simple internal Logger interface.  */
interface Logger {
    fun trace(message: Supplier<String>)

    fun info(message: Supplier<String>)

    fun warn(message: Supplier<String>)

    fun error(message: Supplier<String>, t: Throwable)
}
