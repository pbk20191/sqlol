package org.example.sqlite.jdbc

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.StringReader
import java.io.Writer
import java.sql.Clob
import java.sql.NClob
import java.sql.SQLException

/**
 * 쓰기 가능한 인메모리 `java.sql.NClob`(= Clob) — [java.sql.Connection.createClob]/
 * [java.sql.Connection.createNClob] 와 [java.sql.ResultSet.getClob]/getNClob 의 구현체.
 * SQLite 에 national-character 구분이 없으므로 NClob == Clob.
 */
class SqliteNClob(initial: String = "") : NClob {

    private var data: StringBuilder? = StringBuilder(initial)

    @Throws(SQLException::class)
    private fun buf(): StringBuilder = data ?: throw SQLException("Clob was freed")

    @Throws(SQLException::class)
    private fun checkPos(pos: Long) {
        if (pos < 1) throw SQLException("position must be >= 1: $pos")
    }

    @Throws(SQLException::class)
    override fun length(): Long = buf().length.toLong()

    @Throws(SQLException::class)
    override fun getSubString(pos: Long, length: Int): String {
        checkPos(pos)
        if (length < 0) throw SQLException("length must be >= 0: $length")
        val b = buf()
        val from = (pos - 1).toInt().coerceAtMost(b.length)
        return b.substring(from, minOf(from + length, b.length))
    }

    @Throws(SQLException::class)
    override fun getCharacterStream(): Reader = StringReader(buf().toString())

    @Throws(SQLException::class)
    override fun getCharacterStream(pos: Long, length: Long): Reader =
        StringReader(getSubString(pos, length.toInt()))

    @Throws(SQLException::class)
    override fun getAsciiStream(): InputStream =
        ByteArrayInputStream(buf().toString().toByteArray(Charsets.US_ASCII))

    @Throws(SQLException::class)
    override fun position(searchstr: String, start: Long): Long {
        checkPos(start)
        val idx = buf().indexOf(searchstr, (start - 1).toInt())
        return if (idx < 0) -1 else (idx + 1).toLong()
    }

    @Throws(SQLException::class)
    override fun position(searchstr: Clob, start: Long): Long =
        position(searchstr.getSubString(1, searchstr.length().toInt()), start)

    @Throws(SQLException::class)
    override fun setString(pos: Long, str: String): Int = setString(pos, str, 0, str.length)

    @Throws(SQLException::class)
    override fun setString(pos: Long, str: String, offset: Int, len: Int): Int {
        checkPos(pos)
        val b = buf()
        val from = (pos - 1).toInt()
        if (from > b.length) throw SQLException("position past end of Clob: $pos (length=${b.length})")
        val piece = str.substring(offset, offset + len)
        b.replace(from, minOf(from + len, b.length), piece)
        return len
    }

    @Throws(SQLException::class)
    override fun setAsciiStream(pos: Long): OutputStream {
        checkPos(pos)
        buf()
        return object : OutputStream() {
            private var at = pos

            override fun write(b: Int) {
                setString(at, (b.toChar()).toString())
                at++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                setString(at, String(b, off, len, Charsets.US_ASCII))
                at += len
            }
        }
    }

    @Throws(SQLException::class)
    override fun setCharacterStream(pos: Long): Writer {
        checkPos(pos)
        buf()
        // Writer 직접 상속 — 모든 오버로드(write(int)/write(String)/append 등)가
        // write(char[],off,len) 으로 수렴한다. StringWriter 상속은 일부 오버로드가
        // 자기 내부 버퍼로 새어 조용히 유실된다.
        return object : Writer() {
            private var at = pos

            override fun write(cbuf: CharArray, off: Int, len: Int) {
                setString(at, String(cbuf, off, len))
                at += len
            }

            override fun flush() {}

            override fun close() {}
        }
    }

    @Throws(SQLException::class)
    override fun truncate(len: Long) {
        val b = buf()
        if (len < 0 || len > b.length) throw SQLException("truncate length out of range: $len")
        b.setLength(len.toInt())
    }

    override fun free() {
        data = null
    }
}
