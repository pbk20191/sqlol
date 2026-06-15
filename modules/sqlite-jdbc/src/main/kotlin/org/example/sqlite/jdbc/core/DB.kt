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
package org.example.sqlite.jdbc.core

import org.example.sqlite.jdbc.*
import org.example.sqlite.jdbc.Function
import org.example.sqlite.jdbc.BusyHandler
import org.example.sqlite.jdbc.Collation
import org.example.sqlite.jdbc.ProgressHandler
import org.example.sqlite.jdbc.SQLiteConfig
import org.example.sqlite.jdbc.SQLiteErrorCode
import org.example.sqlite.jdbc.SQLiteException
import org.example.sqlite.jdbc.SQLiteUpdateListener
import org.example.sqlite.jdbc.core.SafeStmtPtr.*
import java.sql.BatchUpdateException
import java.sql.SQLException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

/*
 * This class is the interface to SQLite. It provides some helper functions
 * used by other parts of the driver. The goal of the helper functions here
 * are not only to provide functionality, but to handle contractual
 * differences between the JDBC specification and the SQLite C API.
 *
 * The process of moving SQLite weirdness into this class is incomplete.
 * You'll still find lots of code in Stmt and PrepStmt that are doing
 * implicit contract conversions. Sorry.
 *
 * The subclass, NativeDB, provides the actual access to SQLite functions.
 */
abstract class DB(@JvmField val url: String, private val fileName: String, @JvmField val config: SQLiteConfig) : Codes {
    private val closed = AtomicBoolean(true)

    /** The "begin;"and "commit;" statement handles.  */
    @Volatile
    var begin: SafeStmtPtr? = null

    @Volatile
    var commit: SafeStmtPtr? = null

    /** Tracer for statements to avoid unfinalized statements on db close.  */
    private val stmts: MutableSet<SafeStmtPtr> = ConcurrentHashMap.newKeySet<SafeStmtPtr>()

    // [벤더링 패치] COW: onUpdate/onCommit 은 워커 스레드의 훅 콜백에서 불린다 — 모니터 금지
    // (caller 가 DB synchronized 안에서 step 결과를 기다리는 동안 콜백이 모니터를 기다리면 데드락)
    @JvmField
    protected val updateListeners: MutableSet<SQLiteUpdateListener> = CopyOnWriteArraySet<SQLiteUpdateListener>()
    @JvmField
    protected val commitListeners: MutableSet<SQLiteCommitListener> = CopyOnWriteArraySet<SQLiteCommitListener>()

    fun isClosed(): Boolean {
        return closed.get()
    }

    // WRAPPER FUNCTIONS ////////////////////////////////////////////
    /**
     * Aborts any pending operation and returns at its earliest opportunity.
     * 
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/interrupt.html](https://www.sqlite.org/c3ref/interrupt.html)
     */
    @Throws(SQLException::class)
    abstract fun interrupt()

    /**
     * Sets a [busy handler](https://www.sqlite.org/c3ref/busy_handler.html) that sleeps
     * for a specified amount of time when a table is locked.
     * 
     * @param ms Time to sleep in milliseconds.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/busy_timeout.html](https://www.sqlite.org/c3ref/busy_timeout.html)
     */
    @Throws(SQLException::class)
    abstract fun busy_timeout(ms: Int)

    /**
     * Sets a [busy handler](https://www.sqlite.org/c3ref/busy_handler.html) that sleeps
     * for a specified amount of time when a table is locked.
     * 
     * @param busyHandler
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/busy_timeout.html](https://www.sqlite.org/c3ref/busy_handler.html)
     */
    @Throws(SQLException::class)
    abstract fun busy_handler(busyHandler: BusyHandler?)

    /**
     * Return English-language text that describes the error as either UTF-8 or UTF-16.
     * 
     * @return Error description in English.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/errcode.html](https://www.sqlite.org/c3ref/errcode.html)
     */
    @Throws(SQLException::class)
    abstract fun errmsg(): String?

    /**
     * Returns the value for SQLITE_VERSION, SQLITE_VERSION_NUMBER, and SQLITE_SOURCE_ID C
     * preprocessor macros that are associated with the library.
     * 
     * @return Compile-time SQLite version information.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/libversion.html](https://www.sqlite.org/c3ref/libversion.html)
     * 
     * @see [https://www.sqlite.org/c3ref/c_source_id.html](https://www.sqlite.org/c3ref/c_source_id.html)
     */
    @Throws(SQLException::class)
    abstract fun libversion(): String?

    /**
     * @return Number of rows that were changed, inserted or deleted by the last SQL statement
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/changes.html](https://www.sqlite.org/c3ref/changes.html)
     */
    @Throws(SQLException::class)
    abstract fun changes(): Long

    /**
     * @return Number of row changes caused by INSERT, UPDATE or DELETE statements since the
     * database connection was opened.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/total_changes.html](https://www.sqlite.org/c3ref/total_changes.html)
     */
    @Throws(SQLException::class)
    abstract fun total_changes(): Long

    /**
     * Enables or disables the sharing of the database cache and schema data structures between
     * connections to the same database.
     * 
     * @param enable True to enable; false otherwise.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/enable_shared_cache.html](https://www.sqlite.org/c3ref/enable_shared_cache.html)
     * 
     * @see org.example.sqlite.jdbc.SQLiteErrorCode
     */
    @Throws(SQLException::class)
    abstract fun shared_cache(enable: Boolean): Int

    /**
     * Enables or disables loading of SQLite extensions.
     * 
     * @param enable True to enable; false otherwise.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/load_extension.html](https://www.sqlite.org/c3ref/load_extension.html)
     */
    @Throws(SQLException::class)
    abstract fun enable_load_extension(enable: Boolean): Int

    /**
     * Executes an SQL statement using the process of compiling, evaluating, and destroying the
     * prepared statement object.
     * 
     * @param sql SQL statement to be executed.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/exec.html](https://www.sqlite.org/c3ref/exec.html)
     */
    @Synchronized
    @Throws(SQLException::class)
    fun exec(sql: String, autoCommit: Boolean) {
        val pointer = prepare(sql)
        try {
            val rc = pointer.safeRunInt<SQLException>(SafePtrIntFunction { obj: DB?, stmt: Long -> obj!!.step(stmt) })
            when (rc) {
                Codes.SQLITE_DONE -> {
                    ensureAutoCommit(autoCommit)
                    return
                }

                Codes.SQLITE_ROW -> return
                else -> throwex(rc)
            }
        } finally {
            pointer.close()
        }
    }

    /**
     * Creates an SQLite interface to a database for the given connection.
     * 
     * @param file The database.
     * @param openFlags File opening configurations ([https://www.sqlite.org/c3ref/c_open_autoproxy.html](https://www.sqlite.org/c3ref/c_open_autoproxy.html))
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/open.html](https://www.sqlite.org/c3ref/open.html)
     */
    @Synchronized
    @Throws(SQLException::class)
    fun open(file: String, openFlags: Int) {
        _open(file, openFlags)
        closed.set(false)

        if (fileName.startsWith("file:") && !fileName.contains("cache=")) {
            // URI cache overrides flags
            shared_cache(config.isEnabledSharedCache)
        }
        enable_load_extension(config.isEnabledLoadExtension)
        busy_timeout(config.getBusyTimeout())
    }

    /**
     * Closes a database connection and finalizes any remaining statements before the closing
     * operation.
     * 
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/close.html](https://www.sqlite.org/c3ref/close.html)
     */
    @Synchronized
    @Throws(SQLException::class)
    fun close() {
        // finalize any remaining statements before closing db
        for (element in stmts) {
            element.close()
        }

        // clean up commit object
        if (begin != null) begin!!.close()
        if (commit != null) commit!!.close()

        closed.set(true)
        _close()
    }

    /**
     * Complies the an SQL statement.
     * 
     * @param stmt The SQL statement to compile.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/prepare.html](https://www.sqlite.org/c3ref/prepare.html)
     */
    @Synchronized
    @Throws(SQLException::class)
    fun prepare(stmt: CoreStatement) {
        if (stmt.sql == null) {
            throw NullPointerException()
        }
        if (stmt.pointer != null) {
            stmt.pointer!!.close()
        }
        stmt.pointer = prepare(stmt.sql!!)
        val added = stmts.add(stmt.pointer!!)
        check(added) { "Already added pointer to statements set" }
    }

    /**
     * Destroys a statement.
     * 
     * @param safePtr the pointer wrapper to remove from internal structures
     * @param ptr the raw pointer to free
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException if finalization fails
     * @see [https://www.sqlite.org/c3ref/finalize.html](https://www.sqlite.org/c3ref/finalize.html)
     */
    @Synchronized
    @Throws(SQLException::class)
    fun finalize(safePtr: SafeStmtPtr, ptr: Long): Int {
        try {
            return finalize(ptr)
        } finally {
            stmts.remove(safePtr)
        }
    }

    /**
     * Creates an SQLite interface to a database with the provided open flags.
     * 
     * @param filename The database to open.
     * @param openFlags File opening configurations ([https://www.sqlite.org/c3ref/c_open_autoproxy.html](https://www.sqlite.org/c3ref/c_open_autoproxy.html))
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/open.html](https://www.sqlite.org/c3ref/open.html)
     */
    @Throws(SQLException::class)
    protected abstract fun _open(filename: String, openFlags: Int)

    /**
     * Closes the SQLite interface to a database.
     * 
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/close.html](https://www.sqlite.org/c3ref/close.html)
     */
    @Throws(SQLException::class)
    protected abstract fun _close()

    /**
     * Complies, evaluates, executes and commits an SQL statement.
     * 
     * @param sql An SQL statement.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/exec.html](https://www.sqlite.org/c3ref/exec.html)
     */
    @Throws(SQLException::class)
    abstract fun _exec(sql: String): Int

    /**
     * Complies an SQL statement.
     * 
     * @param sql An SQL statement.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/prepare.html](https://www.sqlite.org/c3ref/prepare.html)
     */
    @Throws(SQLException::class)
    protected abstract fun prepare(sql: String): SafeStmtPtr

    /**
     * Destroys a prepared statement.
     * 
     * @param stmt Pointer to the statement pointer.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/finalize.html](https://www.sqlite.org/c3ref/finalize.html)
     */
    @Throws(SQLException::class)
    protected abstract fun finalize(stmt: Long): Int

    /**
     * Evaluates a statement.
     * 
     * @param stmt Pointer to the statement.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/step.html](https://www.sqlite.org/c3ref/step.html)
     */
    @Throws(SQLException::class)
    abstract fun step(stmt: Long): Int

    /**
     * Sets a prepared statement object back to its initial state, ready to be re-executed.
     * 
     * @param stmt Pointer to the statement.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/reset.html](https://www.sqlite.org/c3ref/reset.html)
     */
    @Throws(SQLException::class)
    abstract fun reset(stmt: Long): Int

    /**
     * Reset all bindings on a prepared statement (reset all host parameters to NULL).
     * 
     * @param stmt Pointer to the statement.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/clear_bindings.html](https://www.sqlite.org/c3ref/clear_bindings.html)
     */
    @Throws(SQLException::class)
    abstract fun clear_bindings(stmt: Long): Int // TODO remove?

    /**
     * @param stmt Pointer to the statement.
     * @return Number of parameters in a prepared SQL.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/bind_parameter_count.html](https://www.sqlite.org/c3ref/bind_parameter_count.html)
     */
    @Throws(SQLException::class)
    abstract fun bind_parameter_count(stmt: Long): Int

    /**
     * @param stmt Pointer to the statement.
     * @return Number of columns in the result set returned by the prepared statement.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_count.html](https://www.sqlite.org/c3ref/column_count.html)
     */
    @Throws(SQLException::class)
    abstract fun column_count(stmt: Long): Int

    /**
     * @param stmt Pointer to the statement.
     * @param col Number of column.
     * @return Datatype code for the initial data type of the result column.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_blob.html](https://www.sqlite.org/c3ref/column_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun column_type(stmt: Long, col: Int): Int

    /**
     * @param stmt Pointer to the statement.
     * @param col Number of column.
     * @return Declared type of the table column for prepared statement.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_decltype.html](https://www.sqlite.org/c3ref/column_decltype.html)
     */
    @Throws(SQLException::class)
    abstract fun column_decltype(stmt: Long, col: Int): String?

    /**
     * @param stmt Pointer to the statement.
     * @param col Number of column.
     * @return Original text of column name which is the declared in the CREATE TABLE statement.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_database_name.html](https://www.sqlite.org/c3ref/column_database_name.html)
     */
    @Throws(SQLException::class)
    abstract fun column_table_name(stmt: Long, col: Int): String?

    /**
     * @param stmt Pointer to the statement.
     * @param col The number of column.
     * @return Name assigned to a particular column in the result set of a SELECT statement.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_name.html](https://www.sqlite.org/c3ref/column_name.html)
     */
    @Throws(SQLException::class)
    abstract fun column_name(stmt: Long, col: Int): String?

    /**
     * @param stmt Pointer to the statement.
     * @param col Number of column.
     * @return Value of the column as text data type in the result set of a SELECT statement.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_blob.html](https://www.sqlite.org/c3ref/column_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun column_text(stmt: Long, col: Int): String?

    /**
     * @param stmt Pointer to the statement.
     * @param col Number of column.
     * @return BLOB value of the column in the result set of a SELECT statement
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_blob.html](https://www.sqlite.org/c3ref/column_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun column_blob(stmt: Long, col: Int): ByteArray?

    /**
     * @param stmt Pointer to the statement.
     * @param col Number of column.
     * @return DOUBLE value of the column in the result set of a SELECT statement
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_blob.html](https://www.sqlite.org/c3ref/column_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun column_double(stmt: Long, col: Int): Double

    /**
     * @param stmt Pointer to the statement.
     * @param col Number of column.
     * @return LONG value of the column in the result set of a SELECT statement.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_blob.html](https://www.sqlite.org/c3ref/column_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun column_long(stmt: Long, col: Int): Long

    /**
     * @param stmt Pointer to the statement.
     * @param col Number of column.
     * @return INT value of column in the result set of a SELECT statement.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/column_blob.html](https://www.sqlite.org/c3ref/column_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun column_int(stmt: Long, col: Int): Int

    /**
     * Binds NULL value to prepared statements with the pointer to the statement object and the
     * index of the SQL parameter to be set to NULL.
     * 
     * @param stmt Pointer to the statement.
     * @param pos The index of the SQL parameter to be set to NULL.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     */
    @Throws(SQLException::class)
    abstract fun bind_null(stmt: Long, pos: Int): Int

    /**
     * Binds int value to prepared statements with the pointer to the statement object, the index of
     * the SQL parameter to be set and the value to bind to the parameter.
     * 
     * @param stmt Pointer to the statement.
     * @param pos The index of the SQL parameter to be set.
     * @param v Value to bind to the parameter.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/bind_blob.html](https://www.sqlite.org/c3ref/bind_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun bind_int(stmt: Long, pos: Int, v: Int): Int

    /**
     * Binds long value to prepared statements with the pointer to the statement object, the index
     * of the SQL parameter to be set and the value to bind to the parameter.
     * 
     * @param stmt Pointer to the statement.
     * @param pos The index of the SQL parameter to be set.
     * @param v Value to bind to the parameter.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/bind_blob.html](https://www.sqlite.org/c3ref/bind_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun bind_long(stmt: Long, pos: Int, v: Long): Int

    /**
     * Binds double value to prepared statements with the pointer to the statement object, the index
     * of the SQL parameter to be set and the value to bind to the parameter.
     * 
     * @param stmt Pointer to the statement.
     * @param pos Index of the SQL parameter to be set.
     * @param v Value to bind to the parameter.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/bind_blob.html](https://www.sqlite.org/c3ref/bind_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun bind_double(stmt: Long, pos: Int, v: Double): Int

    /**
     * Binds text value to prepared statements with the pointer to the statement object, the index
     * of the SQL parameter to be set and the value to bind to the parameter.
     * 
     * @param stmt Pointer to the statement.
     * @param pos Index of the SQL parameter to be set.
     * @param v value to bind to the parameter.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/bind_blob.html](https://www.sqlite.org/c3ref/bind_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun bind_text(stmt: Long, pos: Int, v: String): Int

    /**
     * Binds blob value to prepared statements with the pointer to the statement object, the index
     * of the SQL parameter to be set and the value to bind to the parameter.
     * 
     * @param stmt Pointer to the statement.
     * @param pos Index of the SQL parameter to be set.
     * @param v Value to bind to the parameter.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/bind_blob.html](https://www.sqlite.org/c3ref/bind_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun bind_blob(stmt: Long, pos: Int, v: ByteArray): Int

    /**
     * Sets the result of an SQL function as NULL with the pointer to the SQLite database context.
     * 
     * @param context Pointer to the SQLite database context.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/result_blob.html](https://www.sqlite.org/c3ref/result_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun result_null(context: Long)

    /**
     * Sets the result of an SQL function as text data type with the pointer to the SQLite database
     * context and the the result value of String.
     * 
     * @param context Pointer to the SQLite database context.
     * @param val Result value of an SQL function.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/result_blob.html](https://www.sqlite.org/c3ref/result_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun result_text(context: Long, `val`: String)

    /**
     * Sets the result of an SQL function as blob data type with the pointer to the SQLite database
     * context and the the result value of byte array.
     * 
     * @param context Pointer to the SQLite database context.
     * @param val Result value of an SQL function.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/result_blob.html](https://www.sqlite.org/c3ref/result_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun result_blob(context: Long, `val`: ByteArray)

    /**
     * Sets the result of an SQL function as double data type with the pointer to the SQLite
     * database context and the the result value of double.
     * 
     * @param context Pointer to the SQLite database context.
     * @param val Result value of an SQL function.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/result_blob.html](https://www.sqlite.org/c3ref/result_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun result_double(context: Long, `val`: Double)

    /**
     * Sets the result of an SQL function as long data type with the pointer to the SQLite database
     * context and the the result value of long.
     * 
     * @param context Pointer to the SQLite database context.
     * @param val Result value of an SQL function.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/result_blob.html](https://www.sqlite.org/c3ref/result_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun result_long(context: Long, `val`: Long)

    /**
     * Sets the result of an SQL function as int data type with the pointer to the SQLite database
     * context and the the result value of int.
     * 
     * @param context Pointer to the SQLite database context.
     * @param val Result value of an SQL function.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/result_blob.html](https://www.sqlite.org/c3ref/result_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun result_int(context: Long, `val`: Int)

    /**
     * Sets the result of an SQL function as an error with the pointer to the SQLite database
     * context and the the error of String.
     * 
     * @param context Pointer to the SQLite database context.
     * @param err Error result of an SQL function.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/result_blob.html](https://www.sqlite.org/c3ref/result_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun result_error(context: Long, err: String)

    /**
     * @param f SQLite function object.
     * @param arg Pointer to the parameter of the SQLite function or aggregate.
     * @return Parameter value of the given SQLite function or aggregate in text data type.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/value_blob.html](https://www.sqlite.org/c3ref/value_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun value_text(f: Function, arg: Int): String?

    /**
     * @param f SQLite function object.
     * @param arg Pointer to the parameter of the SQLite function or aggregate.
     * @return Parameter value of the given SQLite function or aggregate in blob data type.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/value_blob.html](https://www.sqlite.org/c3ref/value_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun value_blob(f: Function, arg: Int): ByteArray?

    /**
     * @param f SQLite function object.
     * @param arg Pointer to the parameter of the SQLite function or aggregate.
     * @return Parameter value of the given SQLite function or aggregate in double data type
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/value_blob.html](https://www.sqlite.org/c3ref/value_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun value_double(f: Function, arg: Int): Double

    /**
     * @param f SQLite function object.
     * @param arg Pointer to the parameter of the SQLite function or aggregate.
     * @return Parameter value of the given SQLite function or aggregate in long data type.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/value_blob.html](https://www.sqlite.org/c3ref/value_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun value_long(f: Function, arg: Int): Long

    /**
     * Accesses the parameter values on the function or aggregate in int data type with the function
     * object and the parameter value.
     * 
     * @param f SQLite function object.
     * @param arg Pointer to the parameter of the SQLite function or aggregate.
     * @return Parameter value of the given SQLite function or aggregate.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/value_blob.html](https://www.sqlite.org/c3ref/value_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun value_int(f: Function, arg: Int): Int

    /**
     * @param f SQLite function object.
     * @param arg Pointer to the parameter of the SQLite function or aggregate.
     * @return Parameter datatype of the function or aggregate in int data type.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/value_blob.html](https://www.sqlite.org/c3ref/value_blob.html)
     */
    @Throws(SQLException::class)
    abstract fun value_type(f: Function, arg: Int): Int

    /**
     * Create a user defined function with given function name and the function object.
     * 
     * @param name The function name to be created.
     * @param f SQLite function object.
     * @param flags Extra flags to use when creating the function, such as [     ][Function.FLAG_DETERMINISTIC]
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/create_function.html](https://www.sqlite.org/c3ref/create_function.html)
     */
    @Throws(SQLException::class)
    abstract fun create_function(name: String, f: Function, nArgs: Int, flags: Int): Int

    /**
     * De-registers a user defined function
     * 
     * @param name Name of the function to de-registered.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     */
    @Throws(SQLException::class)
    abstract fun destroy_function(name: String): Int

    /**
     * Create a user defined collation with given collation name and the collation object.
     * 
     * @param name The collation name to be created.
     * @param c SQLite collation object.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/create_collation.html](https://www.sqlite.org/c3ref/create_collation.html)
     */
    @Throws(SQLException::class)
    abstract fun create_collation(name: String, c: Collation): Int

    /**
     * Create a user defined collation with given collation name and the collation object.
     * 
     * @param name The collation name to be created.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     */
    @Throws(SQLException::class)
    abstract fun destroy_collation(name: String): Int

    /**
     * @param dbName Database name to be backed up.
     * @param destFileName Target backup file name.
     * @param observer ProgressObserver object.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     */
    @Throws(SQLException::class)
    abstract fun backup(
        dbName: String,
        destFileName: String,
        observer: ProgressObserver?
    ): Int

    /**
     * @param dbName Database name to be backed up.
     * @param destFileName Target backup file name.
     * @param observer ProgressObserver object.
     * @param sleepTimeMillis time to wait during a backup/restore operation if sqlite3_backup_step
     * returns SQLITE_BUSY before continuing
     * @param nTimeouts the number of times sqlite3_backup_step can return SQLITE_BUSY before
     * failing
     * @param pagesPerStep the number of pages to copy in each sqlite3_backup_step. If this is
     * negative, the entire DB is copied at once.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     */
    @Throws(SQLException::class)
    abstract fun backup(
        dbName: String,
        destFileName: String,
        observer: ProgressObserver?,
        sleepTimeMillis: Int,
        nTimeouts: Int,
        pagesPerStep: Int
    ): Int

    /**
     * @param dbName Database name for restoring data.
     * @param sourceFileName Source file name.
     * @param observer ProgressObserver object.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     */
    @Throws(SQLException::class)
    abstract fun restore(
        dbName: String,
        sourceFileName: String,
        observer: ProgressObserver?
    ): Int

    /**
     * @param dbName the name of the db to restore
     * @param sourceFileName the filename of the source db to restore
     * @param observer ProgressObserver object.
     * @param sleepTimeMillis time to wait during a backup/restore operation if sqlite3_backup_step
     * returns SQLITE_BUSY before continuing
     * @param nTimeouts the number of times sqlite3_backup_step can return SQLITE_BUSY before
     * failing
     * @param pagesPerStep the number of pages to copy in each sqlite3_backup_step. If this is
     * negative, the entire DB is copied at once.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     */
    @Throws(SQLException::class)
    abstract fun restore(
        dbName: String,
        sourceFileName: String,
        observer: ProgressObserver?,
        sleepTimeMillis: Int,
        nTimeouts: Int,
        pagesPerStep: Int
    ): Int

    /**
     * @param id The id of the limit.
     * @param value The new value of the limit.
     * @return The prior value of the limit
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/limit.html](https://www.sqlite.org/c3ref/limit.html)
     */
    @Throws(SQLException::class)
    abstract fun limit(id: Int, value: Int): Int

    interface ProgressObserver {
        fun progress(remaining: Int, pageCount: Int)
    }

    /** Progress handler  */
    @Throws(SQLException::class)
    abstract fun register_progress_handler(vmCalls: Int, progressHandler: ProgressHandler?)

    @Throws(SQLException::class)
    abstract fun clear_progress_handler()

    /**
     * Returns an array describing the attributes (not null, primary key and auto increment) of
     * columns.
     * 
     * @param stmt Pointer to the statement.
     * @return Column attribute array.<br></br>
     * index[col][0] = true if column constrained NOT NULL;<br></br>
     * index[col][1] = true if column is part of the primary key; <br></br>
     * index[col][2] = true if column is auto-increment.
     * @throws SQLException
     */
    @Throws(SQLException::class)
    abstract fun column_metadata(stmt: Long): Array<BooleanArray>

    // COMPOUND FUNCTIONS ////////////////////////////////////////////
    /**
     * Returns an array of column names in the result set of the SELECT statement.
     * 
     * @param stmt Stmt object.
     * @return String array of column names.
     * @throws SQLException
     */
    @Synchronized
    @Throws(SQLException::class)
    fun column_names(stmt: Long): Array<String> {
        val count = column_count(stmt)

        return (0..<count).map{
            column_name(stmt, it)!!
        }.toTypedArray()
//        val names: Array<String> = arrayOfNulls<String>(column_count(stmt))
//        for (i in names.indices) {
//            names[i] = column_name(stmt, i)!!
//        }
//        return names
    }

    /**
     * Bind values to prepared statements
     * 
     * @param stmt Pointer to the statement.
     * @param pos Index of the SQL parameter to be set to NULL.
     * @param v Value to bind to the parameter.
     * @return [Result Codes](https://www.sqlite.org/c3ref/c_abort.html)
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/bind_blob.html](https://www.sqlite.org/c3ref/bind_blob.html)
     */
    @Synchronized
    @Throws(SQLException::class)
    fun sqlbind(stmt: Long, pos: Int, v: Any?): Int {
        var pos = pos
        pos++
        if (v == null) {
            return bind_null(stmt, pos)
        } else if (v is Int) {
            return bind_int(stmt, pos, v)
        } else if (v is Short) {
            return bind_int(stmt, pos, v.toInt())
        } else if (v is Long) {
            return bind_long(stmt, pos, v)
        } else if (v is Float) {
            return bind_double(stmt, pos, v.toDouble())
        } else if (v is Double) {
            return bind_double(stmt, pos, v)
        } else if (v is String) {
            return bind_text(stmt, pos, v)
        } else if (v is ByteArray) {
            return bind_blob(stmt, pos, v)
        } else {
            throw SQLException("unexpected param type: " + v.javaClass)
        }
    }

    /**
     * Submits a batch of commands to the database for execution.
     * 
     * @see java.sql.Statement.executeBatch
     * @param stmt Pointer of Stmt object.
     * @param count Number of SQL statements.
     * @param vals Array of parameter values.
     * @return Array of the number of rows changed or inserted or deleted for each command if all
     * commands execute successfully;
     * @throws SQLException if statement is not open or is being used elsewhere
     */
    @Synchronized
    @Throws(SQLException::class)
    fun executeBatch(
        stmt: SafeStmtPtr, count: Int, vals: Array<Any?>?, autoCommit: Boolean
    ): LongArray {
        return stmt.safeRun<LongArray, SQLException>(SafePtrFunction { db: DB?, ptr: Long ->
            this.executeBatch(
                ptr,
                count,
                vals,
                autoCommit
            )
        })
    }

    @Synchronized
    @Throws(SQLException::class)
    private fun executeBatch(
        stmt: Long, count: Int, vals: Array<Any?>?, autoCommit: Boolean
    ): LongArray {
        if (count < 1) {
            throw SQLException("count (" + count + ") < 1")
        }

        val params = bind_parameter_count(stmt)

        var rc: Int
        val changes = LongArray(count)

        try {
            for (i in 0..<count) {
                reset(stmt)
                for (j in 0..<params) {
                    rc = sqlbind(stmt, j, vals!![(i * params) + j])
                    if (rc != Codes.SQLITE_OK) {
                        throwex(rc)
                    }
                }

                rc = step(stmt)
                if (rc != Codes.SQLITE_DONE) {
                    reset(stmt)
                    if (rc == Codes.SQLITE_ROW) {
                        throw BatchUpdateException(
                            "batch entry " + i + ": query returns results",
                            null,
                            0,
                            changes,
                            null
                        )
                    }
                    throwex(rc)
                }

                changes[i] = changes()
            }
        } finally {
            ensureAutoCommit(autoCommit)
        }

        reset(stmt)
        return changes
    }

    /**
     * @see [https://www.sqlite.org/c_interface.html.sqlite_exec](https://www.sqlite.org/c_interface.html.sqlite_exec)
     * 
     * @param stmt Stmt object.
     * @param vals Array of parameter values.
     * @return True if a row of ResultSet is ready; false otherwise.
     * @throws SQLException
     */
    @Synchronized
    @Throws(SQLException::class)
    fun execute(stmt: CoreStatement, vals: Array<Any?>?): Boolean {
        val statusCode =
            stmt.pointer!!.safeRunInt<SQLException>(SafePtrIntFunction { db: DB?, ptr: Long -> execute(ptr, vals) })
        when (statusCode and 0xFF) {
            Codes.SQLITE_DONE -> {
                ensureAutoCommit(stmt.conn.getAutoCommit())
                return false
            }

            Codes.SQLITE_ROW -> return true
            Codes.SQLITE_BUSY, Codes.SQLITE_LOCKED, Codes.SQLITE_MISUSE, Codes.SQLITE_CONSTRAINT -> throw newSQLException(
                statusCode
            )

            else -> {
                stmt.pointer!!.close()
                throw newSQLException(statusCode)
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    private fun execute(ptr: Long, vals: Array<Any?>?): Int {
        if (vals != null) {
            val params = bind_parameter_count(ptr)
            if (params > vals.size) {
                throw SQLException(
                    ("assertion failure: param count ("
                            + params
                            + ") > value count ("
                            + vals.size
                            + ")")
                )
            }

            for (i in 0..<params) {
                val rc = sqlbind(ptr, i, vals[i])
                if (rc != Codes.SQLITE_OK) {
                    throwex(rc)
                }
            }
        }

        val statusCode = step(ptr)
        if ((statusCode and 0xFF) == Codes.SQLITE_DONE) reset(ptr)
        return statusCode
    }

    /**
     * Executes the given SQL statement using the one-step query execution interface.
     * 
     * @param sql SQL statement to be executed.
     * @return True if a row of ResultSet is ready; false otherwise.
     * @throws SQLException
     * @see [https://www.sqlite.org/c3ref/exec.html](https://www.sqlite.org/c3ref/exec.html)
     */
    @Synchronized
    @Throws(SQLException::class)
    fun execute(sql: String, autoCommit: Boolean): Boolean {
        val statusCode = _exec(sql)
        when (statusCode) {
            Codes.SQLITE_OK -> return false
            Codes.SQLITE_DONE -> {
                ensureAutoCommit(autoCommit)
                return false
            }

            Codes.SQLITE_ROW -> return true
            else -> throw newSQLException(statusCode)
        }
    }

    /**
     * Execute an SQL INSERT, UPDATE or DELETE statement with the Stmt object and an array of
     * parameter values of the SQL statement..
     * 
     * @param stmt Stmt object.
     * @param vals Array of parameter values.
     * @return Number of database rows that were changed or inserted or deleted by the most recently
     * completed SQL.
     * @throws SQLException
     */
    @Synchronized
    @Throws(SQLException::class)
    fun executeUpdate(stmt: CoreStatement, vals: Array<Any?>?): Long {
        try {
            if (execute(stmt, vals)) {
                throw SQLException("query returns results")
            }
        } finally {
            if (!stmt.pointer!!.isClosed()) {
                stmt.pointer!!.safeRunInt<SQLException>(SafePtrIntFunction { obj: DB?, stmt: Long -> obj!!.reset(stmt) })
            }
        }
        return changes()
    }

    abstract fun set_commit_listener(enabled: Boolean)

    abstract fun set_update_listener(enabled: Boolean)

    @Synchronized
    fun addUpdateListener(listener: SQLiteUpdateListener) {
        if (updateListeners.add(listener) && updateListeners.size == 1) {
            set_update_listener(true)
        }
    }

    @Synchronized
    fun addCommitListener(listener: SQLiteCommitListener) {
        if (commitListeners.add(listener) && commitListeners.size == 1) {
            set_commit_listener(true)
        }
    }

    @Synchronized
    fun removeUpdateListener(listener: SQLiteUpdateListener) {
        if (updateListeners.remove(listener) && updateListeners.isEmpty()) {
            set_update_listener(false)
        }
    }

    @Synchronized
    fun removeCommitListener(listener: SQLiteCommitListener) {
        if (commitListeners.remove(listener) && commitListeners.isEmpty()) {
            set_commit_listener(false)
        }
    }

    fun onUpdate(type: Int, database: String, table: String, rowId: Long) {
        // [벤더링 패치] COW 직접 순회 — 모니터 없이 thread-safe
        for (listener in updateListeners) {
            val operationType: SQLiteUpdateListener.Type?

            when (type) {
                18 -> operationType = SQLiteUpdateListener.Type.INSERT
                9 -> operationType = SQLiteUpdateListener.Type.DELETE
                23 -> operationType = SQLiteUpdateListener.Type.UPDATE
                else -> throw AssertionError("Unknown type: " + type)
            }

            listener.onUpdate(operationType, database, table, rowId)
        }
    }

    fun onCommit(commit: Boolean) {
        // [벤더링 패치] COW 직접 순회 — 모니터 없이 thread-safe
        for (listener in commitListeners) {
            if (commit) listener.onCommit()
            else listener.onRollback()
        }
    }

    /**
     * Throws SQLException with error message.
     * 
     * @throws SQLException
     */
    @Throws(SQLException::class)
    fun throwex() {
        throw SQLException(errmsg())
    }

    /**
     * Throws SQLException with error code.
     * 
     * @param errorCode Error code to be passed.
     * @throws SQLException Formatted SQLException with error code.
     */
    @Throws(SQLException::class)
    fun throwex(errorCode: Int) {
        throw newSQLException(errorCode)
    }

    /**
     * Throws SQL Exception with error code.
     * 
     * @param errorCode Error code to be passed.
     * @return SQLException with error code and message.
     * @throws SQLException Formatted SQLException with error code
     */
    @Throws(SQLException::class)
    private fun newSQLException(errorCode: Int): SQLiteException {
        return Companion.newSQLException(errorCode, errmsg()!!)
    }

    /**
     * SQLite and the JDBC API have very different ideas about the meaning of auto-commit. Under
     * JDBC, when executeUpdate() returns in auto-commit mode (the default), the programmer assumes
     * the data has been written to disk. In SQLite however, a call to sqlite3_step() with an INSERT
     * statement can return SQLITE_OK, and yet the data is still in limbo.
     * 
     * 
     * This limbo appears when another statement on the database is active, e.g. a SELECT. SQLite
     * auto-commit waits until the final read statement finishes, and then writes whatever updates
     * have already been OKed. So if a program crashes before the reads are complete, data is lost.
     * E.g:
     * 
     * 
     * select begins insert select continues select finishes
     * 
     * 
     * Works as expected, however
     * 
     * 
     * select beings insert select continues crash
     * 
     * 
     * Results in the data never being written to disk.
     * 
     * 
     * As a solution, we call "commit" after every statement in auto-commit mode.
     * 
     * @throws SQLException
     */
    @Throws(SQLException::class)
    fun ensureAutoCommit(autoCommit: Boolean) {
        if (!autoCommit) {
            return
        }

        ensureBeginAndCommit()

        begin!!.safeRunConsume<SQLException>(
            SafePtrConsumer { db: DB?, beginPtr: Long ->
                commit!!.safeRunConsume<SQLException>(
                    SafePtrConsumer { db2: DB?, commitPtr: Long -> ensureAutocommit(beginPtr, commitPtr) })
            })
    }

    @Throws(SQLException::class)
    private fun ensureBeginAndCommit() {
        if (begin == null) {
            synchronized(this) {
                if (begin == null) {
                    begin = prepare("begin;")
                }
            }
        }
        if (commit == null) {
            synchronized(this) {
                if (commit == null) {
                    commit = prepare("commit;")
                }
            }
        }
    }

    @Throws(SQLException::class)
    private fun ensureAutocommit(beginPtr: Long, commitPtr: Long) {
        try {
            if (step(beginPtr) != Codes.SQLITE_DONE) {
                return  // assume we are in a transaction
            }
            val rc = step(commitPtr)
            if (rc != Codes.SQLITE_DONE) {
                reset(commitPtr)
                throwex(rc)
            }
            // throw new SQLException("unable to auto-commit");
        } finally {
            reset(beginPtr)
            reset(commitPtr)
        }
    }

    @Throws(SQLException::class)
    abstract fun serialize(schema: String): ByteArray?

    @Throws(SQLException::class)
    abstract fun deserialize(schema: String, buff: ByteArray)

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is DB) return false
        val db = o
        return url == db.url
                && fileName == db.fileName
                && config == db.config
    }

    override fun hashCode(): Int {
        return hashCode(url, fileName, config)
    }

    companion object {
        /**
         * Throws SQL Exception with error code and message.
         * 
         * @param errorCode Error code to be passed.
         * @param errorMessage Error message to be passed.
         * @throws SQLException Formatted SQLException with error code and message.
         */
        @Throws(SQLException::class)
        fun throwex(errorCode: Int, errorMessage: String) {
            throw newSQLException(errorCode, errorMessage)
        }

        /**
         * Throws formatted SQLException with error code and message.
         * 
         * @param errorCode Error code to be passed.
         * @param errorMessage Error message to be passed.
         * @return Formatted SQLException with error code and message.
         */
        @JvmStatic
        fun newSQLException(errorCode: Int, errorMessage: String): SQLiteException {
            val code = SQLiteErrorCode.getErrorCode(errorCode)
            val msg: String?
            if (code == SQLiteErrorCode.UNKNOWN_ERROR) {
                msg = String.format("%s:%s (%s)", code, errorCode, errorMessage)
            } else {
                msg = String.format("%s (%s)", code, errorMessage)
            }
            return SQLiteException(msg, code)
        }

        fun hashCode(
            url: String, fileName: String, config: SQLiteConfig
        ): Int {
            return Objects.hash(url, fileName, config)
        }
    }
}
