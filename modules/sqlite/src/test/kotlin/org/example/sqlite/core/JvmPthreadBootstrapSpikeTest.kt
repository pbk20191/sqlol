package org.example.sqlite.core

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportMemory
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import com.example.wasm.JvmVfsModule
import com.example.wasm.JvmVfsModule_ModuleExports
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 스파이크: **C 워커/재링크 없이** child 인스턴스를 "직접" 견고하게 부트스트랩할 수 있는가?
 *
 * 아이디어 — 수동 부트스트랩(~33% 플래키)의 정체는 JVM 이 startArg(스택+TLS+musl `struct pthread`
 * 초기화)를 위조해야 했던 것. 그런데 현재 배포 wasm 은 `--export-all` 덕에:
 *   - `pthread_create` 가 export 되어 있고  ← libc 가 startArg 를 *정석으로* 만들어 줌
 *   - `__indirect_function_table` 이 export 되어 있음  ← 테이블 슬롯에 host function ref 주입 가능
 *
 * 경로: JVM 이 main 의 `pthread_create(pt, 0, START_IDX, key)` 호출
 *   → libc 가 스택/TLS/pthread struct 할당·초기화 → `wasi.thread-spawn`(우리 핸들러)
 *   → child 인스턴스 빌드 + child 테이블 START_IDX 에 "Java 본체" host fn ref 심기
 *   → `wasi_thread_start(tid, startArg)` → libc 자식측 초기화 → `call_indirect(START_IDX, key)`
 *   → **Java 코드가 정식 wasm pthread 본체로 실행** (child exports 로 SQL 실행).
 *
 * 성공하면: 워커 본체가 Java 라서 공유메모리 채널(req/done)도 불필요 — j.u.c 로 대체 가능.
 * 테이블 ref 는 child 의 `wasi.thread-spawn` import 함수 인덱스를 가리키되, child 빌드 시
 * 그 import 슬롯에 본체 구현을 바인딩한다 (모듈의 import 섹션은 불변이므로 기존 슬롯 재활용).
 */
class JvmPthreadBootstrapSpikeTest {

    /** 워커 키 → Java 본체. call_indirect(START_IDX, key) 가 디스패치. */
    private class Rt(
        val mem: ByteBufferMemory,
        val x: JvmVfsModule_ModuleExports,
        val startIdx: Int,
        val bodies: ConcurrentHashMap<Int, (JvmVfsModule_ModuleExports) -> Unit>,
        val errors: ConcurrentLinkedQueue<Throwable>,
    )

    /** 간이 C API — 임의 인스턴스의 exports 로 SQL 실행 (rc 인지). */
    private class Db(val x: JvmVfsModule_ModuleExports, val mem: ByteBufferMemory) {
        fun cstr(s: String): Int {
            val b = s.toByteArray(Charsets.UTF_8)
            val p = x.malloc(b.size + 1)
            mem.write(p, b); mem.write(p + b.size, byteArrayOf(0))
            return p
        }
        fun open(path: String): Int {
            val pp = x.malloc(4)
            val rc = x.sqlite3Open(cstr(path), pp)
            val db = mem.readInt(pp); x.free(pp)
            check(rc == 0) { "open($path) rc=$rc" }
            return db
        }
        fun exec(db: Int, sql: String): Int = x.sqlite3Exec(db, cstr(sql), 0, 0, 0)
        fun execRetryBusy(db: Int, sql: String) {
            repeat(200) {
                val rc = exec(db, sql)
                if (rc == 0) return
                check(rc == 5) { "exec rc=$rc: $sql" }
                Thread.sleep(5)
            }
            error("BUSY 지속: $sql")
        }
        fun scalarLong(db: Int, sql: String): Long {
            val pp = x.malloc(4)
            val prc = x.sqlite3PrepareV2(db, cstr(sql), -1, pp, 0)
            val st = mem.readInt(pp); x.free(pp)
            if (prc != 0 || st == 0) return -1   // 일시 잠금 등 — 호출측에서 재시도
            val v = if (x.sqlite3Step(st) == 100) x.sqlite3ColumnInt64(st, 0) else -1
            x.sqlite3Finalize(st)
            return v
        }
        fun scalarText(db: Int, sql: String): String {
            val pp = x.malloc(4)
            val prc = x.sqlite3PrepareV2(db, cstr(sql), -1, pp, 0)
            val st = mem.readInt(pp); x.free(pp)
            if (prc != 0 || st == 0) return ""
            val v = if (x.sqlite3Step(st) == 100) mem.readCString(x.sqlite3ColumnText(st, 0)) else ""
            x.sqlite3Finalize(st)
            return v
        }
        fun close(db: Int) { x.sqlite3CloseV2(db) }
    }

    /** JvmVfsRuntime.open 과 동일 배선 + (1) child 의 thread-spawn 슬롯에 본체 바인딩 (2) 테이블 ref 주입. */
    private fun wire(preopens: Map<String, Path>): Rt {
        val mem = ByteBufferMemory(MemoryLimits(512, 32768, true))
        val locks = JvmVfsLocks()
        val arena = ShmArena(mem)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val bodies = ConcurrentHashMap<Int, (JvmVfsModule_ModuleExports) -> Unit>()

        val optsB = WasiOptions.builder().inheritSystem()
        preopens.forEach { (g, h) -> optsB.withDirectory(g, h) }
        val wasi = WasiPreview1.builder().withOptions(optsB.build()).build()

        fun cstr(i: Instance, p: Long) = i.memory().readCString(p.toInt())
        fun hf(name: String, np: Int, body: (Instance, LongArray) -> Long) =
            HostFunction("vfs", name, List(np) { ValType.I32 }, listOf(ValType.I32)) { i, a -> longArrayOf(body(i, a)) }
        val vfsImports = arrayOf(
            hf("lock", 3) { i, a -> locks.lock(a[0].toInt(), cstr(i, a[1]), a[2].toInt()).toLong() },
            hf("unlock", 3) { i, a -> locks.unlock(a[0].toInt(), cstr(i, a[1]), a[2].toInt()).toLong() },
            hf("check_reserved", 2) { i, a -> locks.checkReserved(a[0].toInt(), cstr(i, a[1])).toLong() },
            hf("shm_map", 4) { i, a -> arena.map(cstr(i, a[0]), a[1].toInt(), a[2].toInt(), a[3].toInt()).toLong() },
            hf("shm_lock", 5) { i, a -> locks.shmLock(a[0].toInt(), cstr(i, a[1]), a[2].toInt(), a[3].toInt(), a[4].toInt()).toLong() },
            hf("shm_unmap", 3) { i, a ->
                val n = cstr(i, a[1]); locks.shmUnmap(a[0].toInt(), n)
                if (a[2].toInt() != 0) arena.dropFile(n)
                0L
            },
        )

        val module = JvmVfsModule.load()
        // 함수 인덱스 공간에서 wasi.thread-spawn 의 인덱스 (import 가 0..N-1 을 선점)
        var fi = 0; var spawnFuncIdx = -1
        val imp = module.importSection()
        for (k in 0 until imp.importCount()) {
            val im = imp.getImport(k)
            if (im.importType() == ExternalType.FUNCTION) {
                if (im.module() == "wasi" && im.name() == "thread-spawn") spawnFuncIdx = fi
                fi++
            }
        }
        check(spawnFuncIdx >= 0) { "thread-spawn import 못 찾음" }

        // child 전용 "워커 본체" — child 의 thread-spawn import 슬롯에 바인딩됨.
        // call_indirect(START_IDX, key) 가 여기로 디스패치. 시그니처 (i32)->(i32) 일치.
        val workerBody = HostFunction("wasi", "thread-spawn", listOf(ValType.I32), listOf(ValType.I32)) { inst, a ->
            val key = a[0].toInt()
            val h = bodies[key] ?: error("본체 미등록: key=$key")
            h(JvmVfsModule_ModuleExports(inst))
            longArrayOf(0)
        }

        // start_func 슬롯 = 0. LLD 테이블은 min=max 고정이라 grow 불가(-1 조용히 실패 — 1차 시도의 함정).
        // slot 0 은 LLD 가 "null 함수 포인터" 트랩용으로 예약하는 자리 — 정상 코드는 call_indirect(0) 을
        // 절대 하지 않으므로, child 테이블에서만 본체 ref 로 덮어써도 안전하다. (main 테이블은 그대로)
        val startIdx = 0
        val threadSpawn = HostFunction("wasi", "thread-spawn", listOf(ValType.I32), listOf(ValType.I32)) { parent, args ->
            val startArg = args[0].toInt()
            val th = Thread.ofVirtual().name("spike-pthread-worker").start {
                try {
                    val fns = parent.imports().functions()
                        .map { f -> if (f.module() == "wasi" && f.name() == "thread-spawn") workerBody else f }
                        .toTypedArray()
                    val w = Instance.builder(parent.module())
                        .withMachineFactory(JvmVfsModule::create)
                        .withImportValues(
                            ImportValues.builder().addFunction(*fns)
                                .addMemory(ImportMemory("env", "memory", mem)).build()
                        )
                        .withStart(false).withInitialize(true).build()
                    w.table(0).setRef(startIdx, spawnFuncIdx, w)
                    JvmVfsModule_ModuleExports(w)
                        .wasiThreadStart(Thread.currentThread().threadId().toInt(), startArg)
                } catch (t: Throwable) {
                    errors += t
                }
            }
            longArrayOf(th.threadId())
        }

        val lockedArray = wasi.toHostFunctions().map { old ->
            HostFunction(old.module(), old.name(), old.functionType()) { a, b ->
                synchronized(wasi) { old.handle().apply(a, *b) }
            }
        }.toTypedArray()

        // helpers.c 의 env.x* 콜백 import 13종 — 스파이크는 콜백 미사용이라 스텁으로 충족
        val i64 = ValType.I64
        fun cbStub(name: String, params: List<ValType>, results: List<ValType>) =
            HostFunction("env", name, params, results) { _, _ ->
                if (results.isEmpty()) null else longArrayOf(0)
            }
        val i32 = ValType.I32
        val cbStubs = arrayOf(
            cbStub("xFunc", listOf(i32, i32, i32), emptyList()),
            cbStub("xStep", listOf(i32, i32, i32), emptyList()),
            cbStub("xFinal", listOf(i32), emptyList()),
            cbStub("xValue", listOf(i32), emptyList()),
            cbStub("xInverse", listOf(i32, i32, i32), emptyList()),
            cbStub("xDestroy", listOf(i32), emptyList()),
            cbStub("xCompare", listOf(i32, i32, i32, i32, i32), listOf(i32)),
            cbStub("xDestroyCollation", listOf(i32), emptyList()),
            cbStub("xBusy", listOf(i32, i32), listOf(i32)),
            cbStub("xProgress", listOf(i32), listOf(i32)),
            cbStub("xCommit", listOf(i32), listOf(i32)),
            cbStub("xRollback", listOf(i32), emptyList()),
            cbStub("xUpdate", listOf(i32, i32, i32, i32, i64), emptyList()),
        )

        val main = Instance.builder(module)
            .withMachineFactory(JvmVfsModule::create)
            .withImportValues(
                ImportValues.builder()
                    .addFunction(*lockedArray)
                    .addFunction(threadSpawn)
                    .addFunction(*vfsImports)
                    .addFunction(*cbStubs)
                    .addMemory(ImportMemory("env", "memory", mem))
                    .build()
            )
            .withStart(false).withInitialize(true).build()

        val x = JvmVfsModule_ModuleExports(main)
        x._initialize()
        check(x.installJvmVfs() == 0) { "install_jvm_vfs 실패" }
        arena.base = x.malloc(8 * 1024 * 1024)
        return Rt(mem, x, startIdx, bodies, errors)
    }

    private fun spawn(rt: Rt, key: Int): Int {
        val pt = rt.x.malloc(4)
        val rc = rt.x.pthreadCreate(pt, 0, rt.startIdx, key)
        assertEquals(0, rc, "pthread_create rc=$rc")
        val t = rt.mem.readInt(pt)
        rt.x.free(pt)
        return t
    }

    @Test
    fun `메커니즘 - JVM 이 pthread_create 직접 호출, Java 본체가 child 에서 SQL 실행`() {
        val rt = wire(emptyMap())
        val done = CountDownLatch(1)
        val count = AtomicLong(-1)
        rt.bodies[7] = { x ->
            val db = Db(x, rt.mem)
            val h = db.open(":memory:")
            check(db.exec(h, "CREATE TABLE t(v INTEGER)") == 0)
            repeat(3) { check(db.exec(h, "INSERT INTO t(v) VALUES (1)") == 0) }
            count.set(db.scalarLong(h, "SELECT count(*) FROM t"))
            db.close(h)
            done.countDown()
        }
        val t = spawn(rt, 7)
        assertTrue(done.await(30, TimeUnit.SECONDS), "본체 미실행 (errors=${rt.errors})")
        assertEquals(0, rt.x.pthreadJoin(t, 0), "pthread_join 실패")
        assertEquals(3L, count.get())
        assertTrue(rt.errors.isEmpty()) { "worker errors: ${rt.errors}" }
    }

    @Test
    fun `파일 WAL - writer 1 + reader 2 동시, 채널은 j_u_c`() {
        val dir = Files.createTempDirectory("spike-pthread-wal")
        val rt = wire(mapOf("/db" to dir))
        val path = "/db/t.db"

        // 파일 선-생성 + WAL + 스키마 (main 커넥션)
        Db(rt.x, rt.mem).also { d ->
            val h = d.open(path)
            check(d.exec(h, "PRAGMA journal_mode=WAL") == 0)
            check(d.exec(h, "CREATE TABLE t(v INTEGER)") == 0)
            d.close(h)
        }

        // 검증된 규율(BUILD.md §7 #2): **순차 spawn + warmup**. 동시 open/WAL/memory.grow 가 겹치면
        // 공유 ByteBufferMemory grow 경합·wal-index 초기화 경합으로 깨진다(1차 시도: 동시 spawn → ~58% 실패,
        // OOB/BufferOverflow/rc=1 등 메모리 경합 증상). open 완료를 ready 래치로 기다린 뒤 다음 워커를 띄우고,
        // 워크로드만 go 래치로 동시 시작한다 — Java 본체라 래치 대기가 그냥 j.u.c 코드인 것이 이 방식의 장점.
        // 검증된 동시성 시맨틱(BUILD.md §7 #3, LoadTest 18/18): reader 끼리만 동시, write 중엔 reader 정지.
        // (reader-during-write 는 Chicory 배리어 한계로 미검증 영역 — 1차에서 rc=1/OOB ~25% 재현)
        // Java 본체라 wasm 채널 없이 JVM RRWL 을 클로저로 공유하면 끝 — 이 방식의 장점이 그대로 드러나는 지점.
        val rwl = java.util.concurrent.locks.ReentrantReadWriteLock()
        val inserts = 30
        val done = CountDownLatch(3)
        val go = CountDownLatch(1)
        val ready = mapOf(1 to CountDownLatch(1), 2 to CountDownLatch(1), 3 to CountDownLatch(1))
        val readerMax = AtomicLong(0)
        rt.bodies[1] = { x ->   // writer
            val db = Db(x, rt.mem)
            val h = db.open(path)
            db.exec(h, "PRAGMA journal_mode=WAL"); db.exec(h, "PRAGMA busy_timeout=5000")
            check(db.scalarLong(h, "SELECT 1") == 1L)            // warmup
            ready[1]!!.countDown(); go.await()
            repeat(inserts) {
                rwl.writeLock().lock()
                try { db.execRetryBusy(h, "INSERT INTO t(v) VALUES (1)") }
                finally { rwl.writeLock().unlock() }
            }
            db.close(h); done.countDown()
        }
        val reader: (Int) -> (JvmVfsModule_ModuleExports) -> Unit = { key -> { x ->
            val db = Db(x, rt.mem)
            val h = db.open(path)
            db.exec(h, "PRAGMA journal_mode=WAL"); db.exec(h, "PRAGMA busy_timeout=5000")
            check(db.scalarLong(h, "SELECT 1") == 1L)            // warmup
            ready[key]!!.countDown(); go.await()
            repeat(60) {
                rwl.readLock().lock()
                val c = try { db.scalarLong(h, "SELECT count(*) FROM t") }
                        finally { rwl.readLock().unlock() }
                if (c >= 0) readerMax.accumulateAndGet(c) { a, b -> maxOf(a, b) }
            }
            db.close(h); done.countDown()
        } }
        rt.bodies[2] = reader(2)
        rt.bodies[3] = reader(3)

        val ts = ArrayList<Int>()
        for (key in 1..3) {                                       // 순차 spawn + warmup 대기
            ts += spawn(rt, key)
            assertTrue(ready[key]!!.await(30, TimeUnit.SECONDS), "worker $key warmup 실패 (errors=${rt.errors})")
        }
        go.countDown()                                            // 워크로드 동시 시작
        assertTrue(done.await(60, TimeUnit.SECONDS), "워커 미완료 (errors=${rt.errors})")
        ts.forEach { assertEquals(0, rt.x.pthreadJoin(it, 0)) }
        assertTrue(rt.errors.isEmpty()) { "worker errors: ${rt.errors}" }

        // 검증 (main 의 새 커넥션)
        Db(rt.x, rt.mem).also { d ->
            val h = d.open(path)
            assertEquals(inserts.toLong(), d.scalarLong(h, "SELECT count(*) FROM t"))
            assertEquals("ok", d.scalarText(h, "PRAGMA integrity_check"))
            d.close(h)
        }
        assertTrue(readerMax.get() in 0..inserts.toLong())
    }
}
