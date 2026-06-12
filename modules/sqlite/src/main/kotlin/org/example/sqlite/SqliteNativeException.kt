package org.example.sqlite

/**
 * sqlite rc 를 보존하는 예외 — JDBC 어댑터([WorkerDbPort] 소비자)가 rc 를 SQLITE_* 에러 코드
 * (예: rc=14 → SQLITE_CANTOPEN)로 매핑할 수 있게 한다. 메시지만 있는 check() 와 달리 rc 가 데이터.
 */
class SqliteNativeException(val rc: Int, message: String) : RuntimeException(message)
