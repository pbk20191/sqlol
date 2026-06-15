package org.example.sqlite.core

import java.nio.file.Path

/**
 * [CrashRecoveryTest] 의 진짜-프로세스 크래시 테스트가 띄우는 자식 JVM 진입점 (test-only).
 *
 * DB 를 열어(=DbOwnerLock 의 OS 파일락 보유) 커밋한 뒤 "READY <count>" 를 stdout 으로 알리고
 * **close 없이 영구 대기**한다. 부모가 SIGKILL(`Process.destroyForcibly`)로 죽이면 = 크래시:
 * 프로세스 사유 linear memory(wal-index 포함) 소멸 + OS 가 파일락 해제. 부모는 같은 디렉터리를
 * 재오픈해 -wal 복구와 락 해제를 한꺼번에 검증한다.
 */
object CrashSubprocessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        val n = args[1].toInt()
        val db = SqliteWal.open(dir, "app.db", readers = 1)
        db.exec("PRAGMA wal_autocheckpoint=0")   // 체크포인트 금지 → 프레임이 -wal 에 잔류
        db.exec("CREATE TABLE IF NOT EXISTS t(id INTEGER PRIMARY KEY, v TEXT)")
        repeat(n) { i -> db.exec("INSERT INTO t(v) VALUES (?)", "row-$i") }
        val c = db.queryLong("SELECT count(*) FROM t")
        println("READY $c")
        System.out.flush()
        // 의도적으로 db.close() 안 함 — 부모의 kill -9 를 기다린다 (크래시 모사)
        Thread.sleep(600_000)
    }
}
