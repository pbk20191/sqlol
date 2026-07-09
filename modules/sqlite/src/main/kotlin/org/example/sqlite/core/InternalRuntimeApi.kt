package org.example.sqlite.core

/**
 * 런타임 생성/공유 SPI 마커 — jdbc 캐시 레이어([org.example.sqlite.jdbc.core] 의 WorkerDbRuntimes)
 * 전용. 일반 사용 금지: 런타임 수명/단독 소유 락([DbOwnerLock])을 직접 다루므로,
 * 잘못 쓰면 잔류 락/누수가 생긴다. 사용하려면 `@OptIn(InternalRuntimeApi::class)` 명시.
 */
@RequiresOptIn(
    message = "런타임 공유/생성 SPI — jdbc 캐시 레이어 전용. @OptIn 으로만 사용.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class InternalRuntimeApi
