/*
 * helpers.c — JVM 쪽이 쓰는 libc 심볼 keep-alive + wasm→JVM 콜백 테이블.
 *
 * 콜백 패턴 (BUILD.md §10 ① — sqlite4j sqlite3_helpers.c 와 같은 import/export 이름):
 * extern 선언을 env import 로 박고 주소를 취하는 *Ptr() export 를 두면, LLD 가 그 import 에
 * 타입드 테이블 슬롯을 등재한다. JVM 은 *Ptr() 로 인덱스를 얻어 sqlite3_create_function_v2 등에
 * 함수 포인터로 넘기고, 호출은 env 호스트 함수(JvmVfsRuntime.buildImports)로 들어온다.
 *
 * keep-alive: sqlite3.c 가 참조하지 않는 심볼은 링커 GC 로 제거되어 --export-all 에도 안 잡힌다.
 *  - pthread_detach: JVM 발 워커 detached 전환 (§9.1 — 종료 동기화는 JVM Thread.join).
 */
#include <pthread.h>
#include "sqlite3.h"

__attribute__((used)) void *sqlite_jvm_keepalive[] = {
    (void *)&pthread_detach,
};

#define JVM_IMPORT(name) __attribute__((__import_module__("env"), __import_name__(#name)))
#define PTR_EXPORT(name) void *name##Ptr(void) { return (void *)&name; }

/* ---- UDF (스칼라/집계/윈도) ---- */
extern void xFunc(sqlite3_context *ctx, int argc, sqlite3_value **argv) JVM_IMPORT(xFunc);
PTR_EXPORT(xFunc)

extern void xStep(sqlite3_context *ctx, int argc, sqlite3_value **argv) JVM_IMPORT(xStep);
PTR_EXPORT(xStep)

extern void xFinal(sqlite3_context *ctx) JVM_IMPORT(xFinal);
PTR_EXPORT(xFinal)

extern void xValue(sqlite3_context *ctx) JVM_IMPORT(xValue);
PTR_EXPORT(xValue)

extern void xInverse(sqlite3_context *ctx, int argc, sqlite3_value **argv) JVM_IMPORT(xInverse);
PTR_EXPORT(xInverse)

extern void xDestroy(void *userData) JVM_IMPORT(xDestroy);
PTR_EXPORT(xDestroy)

/* ---- 콜레이션 (주의: sorter 보조 스레드에서도 호출될 수 있음 — JVM 쪽 디스패치는 스레드 무관) ---- */
extern int xCompare(void *userData, int len1, const void *s1, int len2, const void *s2) JVM_IMPORT(xCompare);
PTR_EXPORT(xCompare)

extern void xDestroyCollation(void *userData) JVM_IMPORT(xDestroyCollation);
PTR_EXPORT(xDestroyCollation)

/* ---- 커넥션 훅 ---- */
extern int xBusy(void *userData, int nPrev) JVM_IMPORT(xBusy);
PTR_EXPORT(xBusy)

extern int xProgress(void *userData) JVM_IMPORT(xProgress);
PTR_EXPORT(xProgress)

extern int xCommit(void *userData) JVM_IMPORT(xCommit);
PTR_EXPORT(xCommit)

extern void xRollback(void *userData) JVM_IMPORT(xRollback);
PTR_EXPORT(xRollback)

extern void xUpdate(void *userData, int op, const char *dbName, const char *table, sqlite3_int64 rowid) JVM_IMPORT(xUpdate);
PTR_EXPORT(xUpdate)
