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

interface Codes {
    companion object {
        /** Successful result  */
        const val SQLITE_OK: Int = 0

        /** SQL error or missing database  */
        const val SQLITE_ERROR: Int = 1

        /** An internal logic error in SQLite  */
        const val SQLITE_INTERNAL: Int = 2

        /** Access permission denied  */
        const val SQLITE_PERM: Int = 3

        /** Callback routine requested an abort  */
        const val SQLITE_ABORT: Int = 4

        /** The database file is locked  */
        const val SQLITE_BUSY: Int = 5

        /** A table in the database is locked  */
        const val SQLITE_LOCKED: Int = 6

        /** A malloc() failed  */
        const val SQLITE_NOMEM: Int = 7

        /** Attempt to write a readonly database  */
        const val SQLITE_READONLY: Int = 8

        /** Operation terminated by sqlite_interrupt()  */
        const val SQLITE_INTERRUPT: Int = 9

        /** Some kind of disk I/O error occurred  */
        const val SQLITE_IOERR: Int = 10

        /** The database disk image is malformed  */
        const val SQLITE_CORRUPT: Int = 11

        /** (Internal Only) Table or record not found  */
        const val SQLITE_NOTFOUND: Int = 12

        /** Insertion failed because database is full  */
        const val SQLITE_FULL: Int = 13

        /** Unable to open the database file  */
        const val SQLITE_CANTOPEN: Int = 14

        /** Database lock protocol error  */
        const val SQLITE_PROTOCOL: Int = 15

        /** (Internal Only) Database table is empty  */
        const val SQLITE_EMPTY: Int = 16

        /** The database schema changed  */
        const val SQLITE_SCHEMA: Int = 17

        /** Too much data for one row of a table  */
        const val SQLITE_TOOBIG: Int = 18

        /** Abort due to constraint violation  */
        const val SQLITE_CONSTRAINT: Int = 19

        /** Data type mismatch  */
        const val SQLITE_MISMATCH: Int = 20

        /** Library used incorrectly  */
        const val SQLITE_MISUSE: Int = 21

        /** Uses OS features not supported on host  */
        const val SQLITE_NOLFS: Int = 22

        /** Authorization denied  */
        const val SQLITE_AUTH: Int = 23

        /** sqlite_step() has another row ready  */
        const val SQLITE_ROW: Int = 100

        /** sqlite_step() has finished executing  */
        const val SQLITE_DONE: Int = 101

        // types returned by sqlite3_column_type()
        const val SQLITE_INTEGER: Int = 1
        const val SQLITE_FLOAT: Int = 2
        const val SQLITE_TEXT: Int = 3
        const val SQLITE_BLOB: Int = 4
        const val SQLITE_NULL: Int = 5
    }
}
