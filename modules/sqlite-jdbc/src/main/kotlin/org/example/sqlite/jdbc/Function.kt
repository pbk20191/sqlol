/*
 * Copyright (c) 2007 David Crawshaw <david@zentus.com>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.example.sqlite.jdbc

import org.example.sqlite.jdbc.core.Codes
import org.example.sqlite.jdbc.core.DB
import java.sql.Connection
import java.sql.SQLException

/**
 * Provides an interface for creating SQLite user-defined functions.
 *
 * A subclass of `Function` can be registered with `Function.create()` and called by the name it was
 * given. All functions must implement `xFunc()`, which is called when SQLite runs the custom
 * function.
 *
 * Arguments passed to a custom function can be accessed using the `protected` functions provided.
 * `args()` returns the number of arguments passed, while `value_<type>(int)` returns the value of
 * the specific argument. Similarly, a function can return a value using the `result(<type>)`
 * function.
 */
abstract class Function {
    private var conn: SQLiteConnection? = null
    private var db: DB? = null

    private var contextPtr: Long = 0 // pointer sqlite3_context*
    private var valuePtr: Long = 0 // pointer sqlite3_value**
    private var argCount: Int = 0

    fun setContext(context: Long) {
        this.contextPtr = context
    }

    fun setValue(value: Long) {
        this.valuePtr = value
    }

    fun getValue(): Long {
        return valuePtr
    }

    fun getValueArg(arg: Int): Long {
        // value is a pointer to the 0 element of an array of 'int'
        return valuePtr + (arg * 4)
    }

    fun setArgs(args: Int) {
        this.argCount = args
    }

    /**
     * Called by SQLite as a custom function. Should access arguments through `value_*(int)`, return
     * results with `result(*)` and throw errors with `error(String)`.
     */
    @Throws(SQLException::class)
    abstract fun xFunc()

    /**
     * Returns the number of arguments passed to the function. Can only be called from `xFunc()`.
     */
    @Synchronized
    @Throws(SQLException::class)
    protected fun args(): Int {
        checkContext()
        return argCount
    }

    /** Called by `xFunc` to return a value. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun result(value: ByteArray) {
        checkContext()
        db!!.result_blob(contextPtr, value)
    }

    /** Called by `xFunc` to return a value. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun result(value: Double) {
        checkContext()
        db!!.result_double(contextPtr, value)
    }

    /** Called by `xFunc` to return a value. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun result(value: Int) {
        checkContext()
        db!!.result_int(contextPtr, value)
    }

    /** Called by `xFunc` to return a value. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun result(value: Long) {
        checkContext()
        db!!.result_long(contextPtr, value)
    }

    /** Called by `xFunc` to return a value. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun result() {
        checkContext()
        db!!.result_null(contextPtr)
    }

    /** Called by `xFunc` to return a value. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun result(value: String) {
        checkContext()
        db!!.result_text(contextPtr, value)
    }

    /** Called by `xFunc` to throw an error. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun error(err: String) {
        checkContext()
        db!!.result_error(contextPtr, err)
    }

    /** Called by `xFunc` to access the value of an argument. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun value_text(arg: Int): String? {
        checkValue(arg)
        return db!!.value_text(this, arg)
    }

    /** Called by `xFunc` to access the value of an argument. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun value_blob(arg: Int): ByteArray? {
        checkValue(arg)
        return db!!.value_blob(this, arg)
    }

    /** Called by `xFunc` to access the value of an argument. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun value_double(arg: Int): Double {
        checkValue(arg)
        return db!!.value_double(this, arg)
    }

    /** Called by `xFunc` to access the value of an argument. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun value_int(arg: Int): Int {
        checkValue(arg)
        return db!!.value_int(this, arg)
    }

    /** Called by `xFunc` to access the value of an argument. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun value_long(arg: Int): Long {
        checkValue(arg)
        return db!!.value_long(this, arg)
    }

    /** Called by `xFunc` to access the value of an argument. */
    @Synchronized
    @Throws(SQLException::class)
    protected fun value_type(arg: Int): Int {
        checkValue(arg)
        return db!!.value_type(this, arg)
    }

    @Throws(SQLException::class)
    private fun checkContext() {
        if (conn == null || conn!!.database == null || contextPtr == 0L) {
            throw SQLException("no context, not allowed to read value")
        }
    }

    @Throws(SQLException::class)
    private fun checkValue(arg: Int) {
        if (conn == null || conn!!.database == null || valuePtr == 0L) {
            throw SQLException("not in value access state")
        }
        if (arg >= argCount) {
            throw SQLException("arg " + arg + " out bounds [0," + argCount + ")")
        }
    }

    /**
     * Provides an interface for creating SQLite user-defined aggregate functions.
     *
     * @see Function
     */
    abstract class Aggregate : Function(), Cloneable {
        override fun xFunc() {}

        /**
         * Defines the abstract aggregate callback function
         *
         * @see <a href="https://www.sqlite.org/c3ref/aggregate_context.html">aggregate_context</a>
         */
        @Throws(SQLException::class)
        abstract fun xStep()

        /**
         * Defines the abstract aggregate callback function
         *
         * @see <a href="https://www.sqlite.org/c3ref/aggregate_context.html">aggregate_context</a>
         */
        @Throws(SQLException::class)
        abstract fun xFinal()

        @Throws(CloneNotSupportedException::class)
        public override fun clone(): Any {
            return super.clone()
        }
    }

    /**
     * Provides an interface for creating SQLite user-defined window functions.
     *
     * @see Aggregate
     */
    abstract class Window : Aggregate() {
        /** Defines the abstract window callback function */
        @Throws(SQLException::class)
        abstract fun xInverse()

        /** Defines the abstract window callback function */
        @Throws(SQLException::class)
        abstract fun xValue()
    }

    companion object {
        /**
         * Flag to provide to [create] that marks this Function as deterministic, making it usable in
         * Indexes on Expressions.
         */
        const val FLAG_DETERMINISTIC: Int = 0x800

        /**
         * Registers a given function with the connection.
         *
         * @param conn The connection.
         * @param name The name of the function.
         * @param f The function to register.
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun create(conn: Connection, name: String, f: Function) {
            create(conn, name, f, 0)
        }

        /**
         * Registers a given function with the connection.
         *
         * @param conn The connection.
         * @param name The name of the function.
         * @param f The function to register.
         * @param flags Extra flags to pass, such as [FLAG_DETERMINISTIC]
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun create(conn: Connection, name: String, f: Function, flags: Int) {
            create(conn, name, f, -1, flags)
        }

        /**
         * Registers a given function with the connection.
         *
         * @param conn The connection.
         * @param name The name of the function.
         * @param f The function to register.
         * @param nArgs The number of arguments that the function takes.
         * @param flags Extra flags to pass, such as [FLAG_DETERMINISTIC]
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun create(conn: Connection, name: String, f: Function, nArgs: Int, flags: Int) {
            if (conn !is SQLiteConnection) {
                throw SQLException("connection must be to an SQLite db")
            }
            if (conn.isClosed) {
                throw SQLException("connection closed")
            }

            f.conn = conn
            val database = conn.database
            f.db = database

            if (nArgs < -1 || nArgs > 127) {
                throw SQLException("invalid args provided: " + nArgs)
            }

            if (database.create_function(name, f, nArgs, flags) != Codes.SQLITE_OK) {
                throw SQLException("error creating function")
            }
        }

        /**
         * Removes a named function from the given connection.
         *
         * @param conn The connection to remove the function from.
         * @param name The name of the function.
         * @param nArgs Ignored.
         * @throws SQLException
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun destroy(conn: Connection, name: String, nArgs: Int) {
            if (conn !is SQLiteConnection) {
                throw SQLException("connection must be to an SQLite db")
            }
            conn.database.destroy_function(name)
        }

        /**
         * Removes a named function from the given connection.
         *
         * @param conn The connection to remove the function from.
         * @param name The name of the function.
         * @throws SQLException
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun destroy(conn: Connection, name: String) {
            destroy(conn, name, -1)
        }
    }
}
