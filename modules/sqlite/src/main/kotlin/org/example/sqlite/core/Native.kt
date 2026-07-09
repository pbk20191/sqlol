package org.example.sqlite.core

import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.Memory
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import com.example.wasm.Sqlite3Module
import com.example.wasm.Sqlite3Module_ModuleExports
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 하나의 WASM 인스턴스(= 하나의 SQLite 커넥션)에 대한 저수준 FFI 계층.
 *
 * Chicory AOT 머신으로 sqlite3.wasm 을 인스턴스화하고, export 함수 호출과
 * linear memory 상의 포인터 마샬링을 담당한다. thread-safe 하지 않다
 * (Chicory Instance 자체가 thread-safe 아님) — 커넥션당 하나의 스레드에서만 사용.
 */
class Native(wasiOptions: WasiOptions) : AutoCloseable {
    private val wasi = WasiPreview1.builder().withOptions(wasiOptions).build()

    private val lock = ReentrantLock()
    private val instance: Instance = Instance.builder(Sqlite3Module.load())
        .withMachineFactory(Sqlite3Module::create)
        .withImportValues(ImportValues.builder().addFunction(*wasi.toHostFunctions().map { old ->
            HostFunction(old.module(), old.name(), old.functionType()) { a, b ->
                lock.withLock {
                    old.handle().apply(a, *b)
                }
            }
        }.toTypedArray()).build())
        .withStart(false)        // reactor 모델: _start 없음
        .withInitialize(true)    // data/element 세그먼트 초기화
        .build()

    val exports = Sqlite3Module_ModuleExports(instance)

    val mem: Memory

    init {
        exports._initialize() // reactor 런타임 초기화
        mem = exports.memory()
    }

    // ---- 메모리 할당 ----
    fun malloc(n: Int): Int {
        val p = exports.malloc(n)
        check(p != 0) { "wasm malloc($n) 실패 (OOM)" }
        return p
    }

    fun free(ptr: Int) {
        if (ptr != 0) exports.free(ptr)
    }

    // ---- 쓰기 ----
    fun cString(s: String): Int {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val ptr = malloc(bytes.size + 1)
        mem.write(ptr, bytes)
        mem.write(ptr + bytes.size, byteArrayOf(0))
        return ptr
    }

    fun writeBytes(bytes: ByteArray): Int {
        val ptr = malloc(maxOf(bytes.size, 1))
        if (bytes.isNotEmpty()) mem.write(ptr, bytes)
        return ptr
    }

    // ---- 읽기 ----
    fun readInt(ptr: Int): Int = mem.readInt(ptr)
    fun readCString(ptr: Int): String = if (ptr == 0) "" else mem.readCString(ptr)
    fun readString(ptr: Int, len: Int): String = if (ptr == 0 || len == 0) "" else mem.readString(ptr, len)
    fun readBytes(ptr: Int, len: Int): ByteArray = if (ptr == 0 || len == 0) ByteArray(0) else mem.readBytes(ptr, len)

    fun errmsg(db: Int): String = readCString(exports.sqlite3Errmsg(db))

    override fun close() = lock.withLock { wasi.close() }
}
