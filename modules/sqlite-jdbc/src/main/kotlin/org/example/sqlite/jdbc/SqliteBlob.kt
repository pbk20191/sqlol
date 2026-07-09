package org.example.sqlite.jdbc

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.sql.Blob
import java.sql.SQLException

/**
 * 쓰기 가능한 인메모리 `java.sql.Blob` — [java.sql.Connection.createBlob] 와
 * [java.sql.ResultSet.getBlob] 의 구현체. SQLite 값은 결국 한 셀의 BLOB 바이트이므로
 * 클라이언트 측 버퍼가 정확한 시맨틱이다 (임의 쿼리 결과엔 증분 blob 핸들을 열 수 없다 —
 * sqlite3_blob_open 은 (table, column, rowid) 직접 지정이 필요).
 */
class SqliteBlob(initial: ByteArray = ByteArray(0)) : Blob {

    private var data: ByteArray? = initial.copyOf()

    @Throws(SQLException::class)
    private fun buf(): ByteArray = data ?: throw SQLException("Blob was freed")

    @Throws(SQLException::class)
    private fun checkPos(pos: Long) {
        if (pos < 1) throw SQLException("position must be >= 1: $pos")
    }

    @Throws(SQLException::class)
    override fun length(): Long = buf().size.toLong()

    @Throws(SQLException::class)
    override fun getBytes(pos: Long, length: Int): ByteArray {
        checkPos(pos)
        if (length < 0) throw SQLException("length must be >= 0: $length")
        val b = buf()
        val from = (pos - 1).toInt().coerceAtMost(b.size)
        return b.copyOfRange(from, minOf(from + length, b.size))
    }

    @Throws(SQLException::class)
    override fun getBinaryStream(): InputStream = ByteArrayInputStream(buf())

    @Throws(SQLException::class)
    override fun getBinaryStream(pos: Long, length: Long): InputStream =
        ByteArrayInputStream(getBytes(pos, length.toInt()))

    @Throws(SQLException::class)
    override fun position(pattern: ByteArray, start: Long): Long {
        checkPos(start)
        val b = buf()
        if (pattern.isEmpty()) return start
        outer@ for (i in (start - 1).toInt()..b.size - pattern.size) {
            for (j in pattern.indices) if (b[i + j] != pattern[j]) continue@outer
            return (i + 1).toLong()
        }
        return -1
    }

    @Throws(SQLException::class)
    override fun position(pattern: Blob, start: Long): Long =
        position(pattern.getBytes(1, pattern.length().toInt()), start)

    @Throws(SQLException::class)
    override fun setBytes(pos: Long, bytes: ByteArray): Int = setBytes(pos, bytes, 0, bytes.size)

    @Throws(SQLException::class)
    override fun setBytes(pos: Long, bytes: ByteArray, offset: Int, len: Int): Int {
        checkPos(pos)
        val from = (pos - 1).toInt()
        val b = buf()
        val out = if (from + len > b.size) b.copyOf(from + len) else b
        System.arraycopy(bytes, offset, out, from, len)
        data = out
        return len
    }

    @Throws(SQLException::class)
    override fun setBinaryStream(pos: Long): OutputStream {
        checkPos(pos)
        buf()
        val sink = object : OutputStream() {
            private var at = pos

            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                setBytes(at, b, off, len)
                at += len
            }
        }
        // setBytes 는 성장 시 backing 배열 전체를 복사하므로, 바이트 단위 write(int) 호출자가
        // O(N²) 이 되지 않도록 8KB 청크로 모아 커밋한다.
        return BufferedOutputStream(sink, 8192)
    }

    @Throws(SQLException::class)
    override fun truncate(len: Long) {
        val b = buf()
        if (len < 0 || len > b.size) throw SQLException("truncate length out of range: $len")
        data = b.copyOf(len.toInt())
    }

    override fun free() {
        data = null
    }
}
