package org.example.sqlite.core

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.runtime.WasmRuntimeException
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.types.DataSegment
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.function.Function
import kotlin.math.min

/**
 * **stateless 벌크** Memory — [ByteBufferMemory] 위임 래퍼 (BUILD.md §9.1 함정 3 의 근원 수술).
 *
 * Chicory 1.7.5 `ByteBufferMemory` 의 벌크 read/write/fill 은 공유 64KB 페이지 ByteBuffer 의
 * `position()` 상태를 변경한다 — 두 스레드가 같은 페이지에 동시 벌크 접근하면 position 을 서로 덮어
 * 바이트가 엉뚱한 오프셋에 쓰인다. 이 레이스의 호출자는 우리 코드만이 아니라 **chicory-wasi 의
 * fd I/O 결과 마샬링**이라서, WASI 데이터플레인을 동시화(RW-락)하려면 락 정책이 아니라 메모리
 * 구현이 stateless 여야 한다 (rc=26 A/B 실측으로 확정 — §9.1).
 *
 * 구현: 벌크 연산을 **단건 absolute 접근(writeLong/readLong/writeByte/read — `put(index,v)` 계열,
 * 페이지 position 불변)의 합성**으로 재구현. 8바이트 청크 + 꼬리 바이트. 페이지 경계를 걸치는
 * multibyte 접근의 정합성은 inner 의 단건 구현이 보장(wasm 스펙상 비정렬 접근 허용).
 * 단건/atomic/wait-notify/grow/init 은 전부 inner 로 위임 — monitor(waitStates) 시맨틱 동일.
 *
 * 주의: Kotlin 위임은 인터페이스의 **default 메서드도 inner 로 포워딩**하므로, 벌크를 경유하는
 * 편의 메서드(2-인자 write, read/writeString, read/writeCString)도 전부 여기서 가로채야 한다.
 */
class StatelessBulkMemory(private val inner: ByteBufferMemory) : Memory by inner {

//    private val lock = ReentrantReadWriteLock()

    companion object {
        val field = ByteBufferMemory::class.java.getDeclaredField("pages")
        init {
            field.isAccessible = true
        }


        private fun checkBounds(
            addr: Int, size: Int, limit: Int, exceptionFactory: Function<String, ChicoryException>
        ) {
            if (addr < 0 || size < 0 || addr > limit || (size > 0 && ((addr + size) > limit))) {
                val errorMsg =
                    ("out of bounds memory access: attempted to access address: "
                            + addr
                            + " but limit is: "
                            + limit
                            + " and size: "
                            + size)
                throw exceptionFactory.apply(errorMsg)
            }
        }

    }

    // ---- 벌크 쓰기: 8B 청크(absolute writeLong) + 꼬리(writeByte) ----
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    override fun write(addr: Int, data: ByteArray, offset: Int, size: Int) {
//        super.read()
        checkBounds(offset, size, data.size, ::WasmRuntimeException)
        checkBounds(addr, size, Memory.PAGE_SIZE * inner.pages(), ::WasmRuntimeException)
        var addr = addr
        var offset = offset
        var size = size

        while (size > 0) {
            val pageIdx = addr ushr ByteBufferMemory.PAGE_SHIFT
            val pageOffset = addr and ByteBufferMemory.PAGE_MASK
            val chunk = min(size, Memory.PAGE_SIZE - pageOffset)
            val page =  (field.get(inner) as Array<ByteBuffer>)[pageIdx]
            page.put(pageOffset, data, offset, chunk)
//            inner.pages[pageIdx].put(data, offset, chunk)
            addr += chunk
            offset += chunk
            size -= chunk
        }
//        inner.write(addr, data, offset, size)
//        return lock.write {
//            inner.write(addr, data)
//        }

//        val src = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
////        src.put
//        inner.pages
//        var i = 0
//        while (i + 8 <= size) { inner.writeLong(addr + i, src.getLong(offset + i)); i += 8 }
//        while (i < size) { inner.writeByte(addr + i, data[offset + i]); i++ }
    }

    override fun write(addr: Int, data: ByteArray) = this.write(addr, data, 0, data.size)

    // ---- 벌크 읽기 ----
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

    override fun readBytes(addr: Int, len: Int): ByteArray {
        checkBounds(addr, len, Memory.PAGE_SIZE * inner.pages(), ::WasmRuntimeException)
        val result = ByteArray(len)
        var destOffset = 0
        var remaining = len
        var a = addr
        while (remaining > 0) {
            val pageIdx = a ushr ByteBufferMemory.PAGE_SHIFT
            val pageOffset = a and ByteBufferMemory.PAGE_MASK
            val chunk = min(remaining, Memory.PAGE_SIZE - pageOffset)
            val page =  (field.get(inner) as Array<ByteBuffer>)[pageIdx]

            page.get(pageOffset, result, destOffset, chunk)
//            inner.pages[pageIdx].position(pageOffset)
//            inner.pages[pageIdx].get(result, destOffset, chunk)
            a += chunk
            destOffset += chunk
            remaining -= chunk
        }
        return result

////        inner.readBytes()
//        val out = ByteArray(len)
//        val dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
//        var i = 0
//        while (i + 8 <= len) { dst.putLong(i, inner.readLong(addr + i)); i += 8 }
//        while (i < len) { out[i] = inner.read(addr + i); i++ }
//        return out
    }

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) {
        inner.fill(value, fromIndex, toIndex)
//        val v = value.toLong() and 0xFF
//        val pattern = v or (v shl 8) or (v shl 16) or (v shl 24) or
//                (v shl 32) or (v shl 40) or (v shl 48) or (v shl 56)
//        var i = fromIndex
//        while (i + 8 <= toIndex) { inner.writeLong(i, pattern); i += 8 }
//        while (i < toIndex) { inner.writeByte(i, value); i++ }
    }

    override fun copy(dest: Int, src: Int, size: Int) {
        inner.copy(dest, src, size)
    }

    // ---- 벌크를 경유하는 default 편의 메서드 가로채기 ----
    override fun readString(addr: Int, len: Int): String = String(readBytes(addr, len), Charsets.UTF_8)
    override fun readString(addr: Int, len: Int, charset: Charset): String = String(readBytes(addr, len), charset)

//    override fun writeString(addr: Int, data: String) = write(addr, data.toByteArray(Charsets.UTF_8))
    override fun initialize(
        instance: Instance,
        dataSegments: Array<out DataSegment>,
        memoryIndex: Int
    ) {
        inner.initialize(instance, dataSegments, memoryIndex)
    }

    override fun writeString(addr: Int, data: String, charset: Charset) = write(addr, data.toByteArray(charset))

    override fun writeCString(addr: Int, data: String) = writeCString(addr, data, Charsets.UTF_8)
    override fun writeCString(addr: Int, data: String, charset: Charset) {
        val b = data.toByteArray(charset)
        write(addr, b)
        inner.writeByte(addr + b.size, 0)
    }

    override fun readCString(addr: Int): String = readCString(addr, Charsets.UTF_8)
    override fun readCString(addr: Int, charset: Charset): String {
        return super.readCString(addr, charset)
//        if (addr == 0) return ""
//        val out = java.io.ByteArrayOutputStream()
//        var i = addr
//        while (true) {
//            val v = inner.read(i)
//            if (v == 0.toByte()) break
//            out.write(v.toInt()); i++
//        }
//        return out.toString(charset)
    }
}
