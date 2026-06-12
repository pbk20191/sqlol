package org.example.sqlite

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 같은 호스트 DB 파일을 여러 [SqliteDb] (= 독립 WASM 인스턴스)가 동시에 열 때의 조율자.
 *
 * 모든 인스턴스가 하나의 JVM 프로세스 안에 있으므로, 네이티브 SQLite 가 OS 의 advisory lock
 * 으로 하던 커넥션 간 조율을 프로세스 내 [ReentrantReadWriteLock] 으로 대체한다.
 * 파일 경로(정규화된 절대경로)별로 락을 하나씩 공유한다.
 *
 * 동시성 모델: 다중 reader 동시 진행 + writer 1명 배타 (롤백 저널 모드 기준).
 * SQLite 의 db 헤더 change-counter 가 각 커넥션의 페이지 캐시 일관성을 보장한다.
 */
internal object DbCoordinator {
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    fun lockFor(canonicalPath: String): ReentrantReadWriteLock =
        locks.computeIfAbsent(canonicalPath) { ReentrantReadWriteLock() }
}
