package org.example.sqlite.jdbc.core

import java.sql.SQLException

/**
 * A class for safely wrapping calls to a native pointer to a statement, ensuring no other thread
 * has access to the pointer while it is run
 */
class SafeStmtPtr(
    // store a reference to the DB, to lock it before any safe function is called. This avoids
    // deadlocking by locking the DB. All calls with the raw pointer are synchronized with the DB
    // anyways, so making a separate lock would be pointless
    private val db: DB,
    private val ptr: Int
) {
    @Volatile
    private var closed = false

    // to return on subsequent calls to close() after this ptr has been closed
    private var closedRC = 0

    // to throw on subsequent calls to close after this ptr has been closed, if the close function
    // threw an exception
    private var closeException: SQLException? = null

    /**
     * Check whether this pointer has been closed
     *
     * @return whether this pointer has been closed
     */
    fun isClosed(): Boolean {
        return closed
    }

    /**
     * Close this pointer
     *
     * @return the return code of the close callback function
     * @throws SQLException if the close callback throws an SQLException, or the pointer is locked
     *     elsewhere
     */
    @Throws(SQLException::class)
    fun close(): Int {
        synchronized(db) {
            return internalClose()
        }
    }

    @Throws(SQLException::class)
    private fun internalClose(): Int {
        try {
            // if this is already closed, return or throw the previous result
            if (closed) {
                closeException?.let { throw it }
                return closedRC
            }
            closedRC = db.finalize(this, ptr.toLong())
            return closedRC
        } catch (ex: SQLException) {
            this.closeException = ex
            throw ex
        } finally {
            this.closed = true
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @return the return of the passed in function
     * @throws SQLException if the pointer is utilized elsewhere
     */
    @Throws(SQLException::class)
    fun <E : Throwable> safeRunInt(run: SafePtrIntFunction<E>): Int {
        synchronized(db) {
            this.ensureOpen()
            return run.run(db, ptr.toLong())
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @return the return of the passed in function
     * @throws SQLException if the pointer is utilized elsewhere
     */
    @Throws(SQLException::class)
    fun <E : Throwable> safeRunLong(run: SafePtrLongFunction<E>): Long {
        synchronized(db) {
            this.ensureOpen()
            return run.run(db, ptr.toLong())
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @return the return of the passed in function
     * @throws SQLException if the pointer is utilized elsewhere
     */
    @Throws(SQLException::class)
    fun <E : Throwable> safeRunDouble(run: SafePtrDoubleFunction<E>): Double {
        synchronized(db) {
            this.ensureOpen()
            return run.run(db, ptr.toLong())
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @return the return code of the function
     * @throws SQLException if the pointer is utilized elsewhere
     */
    @Throws(SQLException::class)
    fun <T, E : Throwable> safeRun(run: SafePtrFunction<T, E>): T {
        synchronized(db) {
            this.ensureOpen()
            return run.run(db, ptr.toLong())
        }
    }

    /**
     * Run a callback with the wrapped pointer safely.
     *
     * @param run the function to run
     * @throws SQLException if the pointer is utilized elsewhere
     */
    @Throws(SQLException::class)
    fun <E : Throwable> safeRunConsume(run: SafePtrConsumer<E>) {
        synchronized(db) {
            this.ensureOpen()
            run.run(db, ptr.toLong())
        }
    }

    @Throws(SQLException::class)
    private fun ensureOpen() {
        if (this.closed) {
            throw SQLException("stmt pointer is closed")
        }
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val that = o as SafeStmtPtr
        return ptr == that.ptr
    }

    override fun hashCode(): Int {
        return ptr.toLong().hashCode()
    }

    fun interface SafePtrIntFunction<E : Throwable> {
        @Throws(Throwable::class)
        fun run(db: DB, ptr: Long): Int
    }

    fun interface SafePtrLongFunction<E : Throwable> {
        @Throws(Throwable::class)
        fun run(db: DB, ptr: Long): Long
    }

    fun interface SafePtrDoubleFunction<E : Throwable> {
        @Throws(Throwable::class)
        fun run(db: DB, ptr: Long): Double
    }

    fun interface SafePtrFunction<T, E : Throwable> {
        @Throws(Throwable::class)
        fun run(db: DB, ptr: Long): T
    }

    fun interface SafePtrConsumer<E : Throwable> {
        @Throws(Throwable::class)
        fun run(db: DB, ptr: Long)
    }
}
