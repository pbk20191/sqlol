/* 커넥션-워커 PoC.
 * wasm 안의 워커 스레드가 sqlite3 커넥션 하나를 소유하고, 공유메모리 채널을 통해
 * JVM 이 제출한 SQL 을 실행한다.
 *
 * JVM↔wasm wait/notify 는 Chicory 에서 같은 waitStates 큐를 공유한다(JVM mem.notify 가 wasm atomic.wait 을 깨움).
 * 단 Chicory notify/wait 에 드문 lost-wakeup 이 있어, 워커는 무한 대기 대신 **짧은 타임아웃 재폴링**(안전망)을 쓴다.
 * JVM 은 atomicWriteInt+notify 로 깨우고(빠른 경로) 워커 타임아웃이 안전망. ch_request/ch_await 는 wasm-only 대안.
 *
 * channel: state@0(i32), rc@4(i32), result@8(i64), sql@16(cstr)
 *   state: 0=idle, 1=request, 2=done, 3=stop
 */
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include "sqlite3.h"

typedef struct {
    _Atomic int state;
    int rc;
    long long result;
    char sql[4080];
    char dbpath[512];   /* 비어있으면 :memory: */
} channel_t;

static int row_cb(void *p, int nc, char **vals, char **cols) {
    channel_t *ch = (channel_t *)p;
    if (nc > 0 && vals[0]) ch->result = atoll(vals[0]);
    return 0;
}

static void *worker_main(void *arg) {
    channel_t *ch = (channel_t *)arg;
    sqlite3 *db = 0;
    if (ch->dbpath[0]) {
        sqlite3_open(ch->dbpath, &db);
        sqlite3_exec(db, "PRAGMA journal_mode=WAL", 0, 0, 0);
        sqlite3_busy_timeout(db, 5000);
    } else {
        sqlite3_open(":memory:", &db);
    }
    for (;;) {
        int s;
        while ((s = atomic_load(&ch->state)) != 1) {
            if (s == 3) { sqlite3_close(db); return 0; }
            __builtin_wasm_memory_atomic_wait32((int *)&ch->state, s, 5000000);
        }
        ch->result = 0;
        ch->rc = sqlite3_exec(db, ch->sql, row_cb, ch, 0);
        atomic_store(&ch->state, 2);
        __builtin_wasm_memory_atomic_notify((int *)&ch->state, 1);
    }
}

__attribute__((export_name("start_worker")))
int start_worker(channel_t *ch) {
    ch->dbpath[0] = 0;
    atomic_store(&ch->state, 0);
    pthread_t t;
    return pthread_create(&t, NULL, worker_main, ch);
}

/* 파일 DB + WAL 워커. JVM 이 path 를 넘기면 그 파일을 WAL 로 연다. */
__attribute__((export_name("start_worker_file")))
int start_worker_file(channel_t *ch, const char *path) {
    strncpy(ch->dbpath, path, sizeof(ch->dbpath) - 1);
    ch->dbpath[sizeof(ch->dbpath) - 1] = 0;
    atomic_store(&ch->state, 0);
    pthread_t t;
    return pthread_create(&t, NULL, worker_main, ch);
}

/* ---- Spike B: 무한 대기(폴링 안전망 없음) 워커 ----
 * worker_main 과 동일하나 wait32 timeout=-1(무한). JVM 이 Memory.atomicWriteInt+atomicNotify 로
 * 직접 깨우고(ch_request 미사용), Memory.atomicWait 로 done 을 블로킹 수신한다.
 * notify 를 한 번이라도 놓치면 폴링 안전망이 없어 즉시 hang → 진짜 lost-wakeup 검출.
 */
static void *worker_blocking_main(void *arg) {
    channel_t *ch = (channel_t *)arg;
    sqlite3 *db = 0;
    sqlite3_open(":memory:", &db);
    for (;;) {
        int s;
        while ((s = atomic_load(&ch->state)) != 1) {
            if (s == 3) { sqlite3_close(db); return 0; }
            __builtin_wasm_memory_atomic_wait32((int *)&ch->state, s, -1);  /* 무한 대기 */
        }
        ch->result = 0;
        ch->rc = sqlite3_exec(db, ch->sql, row_cb, ch, 0);
        atomic_store(&ch->state, 2);
        __builtin_wasm_memory_atomic_notify((int *)&ch->state, 1);
    }
}

__attribute__((export_name("start_worker_blocking")))
int start_worker_blocking(channel_t *ch) {
    ch->dbpath[0] = 0;
    atomic_store(&ch->state, 0);
    pthread_t t;
    return pthread_create(&t, NULL, worker_blocking_main, ch);
}

/* ---- Spike B-fix: 방향별 주소 분리(req/done) 무폴링 워커 ----
 * 한 워드 양방향 신호의 wakeup 도둑질을 제거: 워커는 req 에서만 대기, 드라이버는 done 에서만 대기.
 * 각 주소에 waiter 1종류뿐이라 pendingWakeups 가로채기가 불가능 → 폴링 없이 -1 무한 대기로 안전.
 * 채널: req@0, done@4, rc@8, result@16(i64), sql@24(cstr)
 */
typedef struct {
    _Atomic int req;     /* 0=idle, 1=request, 3=stop */
    _Atomic int done;    /* 완료 시퀀스(워커가 +1) */
    int rc;
    int got;             /* @12: 첫 행 캡처 여부 (이전엔 result 정렬 패딩) */
    long long result;    /* @16: 첫 행 첫 컬럼 정수값 */
    char sql[4072];      /* @24, ends @4096 */
    char dbpath[512];    /* @4096; 비어있으면 :memory: */
    char restext[2048];  /* @4608: 첫 행 첫 컬럼 텍스트(>2047 절단) */
} bchannel_t;

static int row_cb2(void *p, int nc, char **vals, char **cols) {
    bchannel_t *c = (bchannel_t *)p;
    if (!c->got && nc > 0 && vals[0]) {                 /* 첫 행만 캡처 */
        c->result = atoll(vals[0]);
        strncpy(c->restext, vals[0], sizeof(c->restext) - 1);
        c->restext[sizeof(c->restext) - 1] = 0;
        c->got = 1;
    }
    return 0;
}

static void *worker_2addr_main(void *arg) {
    bchannel_t *c = (bchannel_t *)arg;
    sqlite3 *db = 0;
    sqlite3_open(":memory:", &db);
    for (;;) {
        int r;
        while ((r = atomic_load(&c->req)) != 1) {
            if (r == 3) { sqlite3_close(db); return 0; }
            __builtin_wasm_memory_atomic_wait32((int *)&c->req, r, -1);  /* req 에서만 대기 */
        }
        atomic_store(&c->req, 0);                 /* 요청 소비(idle) → 다음 루프가 다시 대기 */
        c->result = 0; c->got = 0; c->restext[0] = 0;
        c->rc = sqlite3_exec(db, c->sql, row_cb2, c, 0);
        atomic_store(&c->done, atomic_load(&c->done) + 1);          /* done 시퀀스 +1 */
        __builtin_wasm_memory_atomic_notify((int *)&c->done, 1);    /* done 만 notify */
    }
}

__attribute__((export_name("start_worker_2addr")))
int start_worker_2addr(bchannel_t *c) {
    atomic_store(&c->req, 0);
    atomic_store(&c->done, 0);
    pthread_t t;
    return pthread_create(&t, NULL, worker_2addr_main, c);
}

/* 파일 DB + WAL 을 여는 req/done 2주소 무폴링 워커 (SqliteWalPool 핫패스). */
static void *worker_file_2addr_main(void *arg) {
    bchannel_t *c = (bchannel_t *)arg;
    sqlite3 *db = 0;
    sqlite3_open(c->dbpath, &db);
    sqlite3_exec(db, "PRAGMA journal_mode=WAL", 0, 0, 0);
    sqlite3_busy_timeout(db, 5000);
    for (;;) {
        int r;
        while ((r = atomic_load(&c->req)) != 1) {
            if (r == 3) { sqlite3_close(db); return 0; }
            __builtin_wasm_memory_atomic_wait32((int *)&c->req, r, -1);
        }
        atomic_store(&c->req, 0);
        c->result = 0; c->got = 0; c->restext[0] = 0;
        c->rc = sqlite3_exec(db, c->sql, row_cb2, c, 0);
        atomic_store(&c->done, atomic_load(&c->done) + 1);
        __builtin_wasm_memory_atomic_notify((int *)&c->done, 1);
    }
}

__attribute__((export_name("start_worker_file_2addr")))
int start_worker_file_2addr(bchannel_t *c, const char *path) {
    strncpy(c->dbpath, path, sizeof(c->dbpath) - 1);
    c->dbpath[sizeof(c->dbpath) - 1] = 0;
    atomic_store(&c->req, 0);
    atomic_store(&c->done, 0);
    pthread_t t;
    return pthread_create(&t, NULL, worker_file_2addr_main, c);
}

/* JVM 이 SQL 을 ch->sql 에 쓴 뒤 호출: 요청 게시 + 워커 깨우기 (wasm 명령) */
__attribute__((export_name("ch_request")))
void ch_request(channel_t *ch) {
    atomic_store(&ch->state, 1);
    __builtin_wasm_memory_atomic_notify((int *)&ch->state, 1);
}

/* JVM 이 호출: 워커가 done(2) 으로 만들 때까지 wasm 에서 대기. 깨어나면 idle(0) 로 ack.
 * 무한 대기(-1)는 wasm atomic.load 가 wakeup 직후 stale 값을 읽고 재수면하면 교착하므로
 * 5ms 백스톱으로 재폴링(worker_main 과 동일). 자세한 근거는 ConnWorkerBlockingSpikeTest. */
__attribute__((export_name("ch_await")))
void ch_await(channel_t *ch) {
    int s;
    while ((s = atomic_load(&ch->state)) != 2)
        __builtin_wasm_memory_atomic_wait32((int *)&ch->state, s, 5000000);
    atomic_store(&ch->state, 0);
}

__attribute__((export_name("ch_stop")))
void ch_stop(channel_t *ch) {
    atomic_store(&ch->state, 3);
    __builtin_wasm_memory_atomic_notify((int *)&ch->state, 1);
}

/* ---- Stage 3: 자율 벤치 워커 (같은 파일 + WAL 동시 접근) ----
 * bench_t: done@0(i32) mode@4 count@8 rc@12 result@16(i64) path@24(cstr)
 *   mode 0=writer(INSERT 반복), 1=reader(SELECT count 반복)
 */
typedef struct {
    _Atomic int done;
    int mode;
    int count;
    int rc;
    long long result;
    char path[512];
} bench_t;

static int bench_cb(void *p, int nc, char **v, char **c) {
    bench_t *b = (bench_t *)p;
    if (nc > 0 && v[0]) b->result = atoll(v[0]);
    return 0;
}

static void *bench_main(void *arg) {
    bench_t *b = (bench_t *)arg;
    sqlite3 *db = 0;
    if (sqlite3_open(b->path, &db) != SQLITE_OK) { b->rc = 999; goto done; }
    sqlite3_exec(db, "PRAGMA journal_mode=WAL", 0, 0, 0);
    sqlite3_busy_timeout(db, 5000);
    for (int i = 0; i < b->count; i++) {
        int rc = (b->mode == 0)
            ? sqlite3_exec(db, "INSERT INTO t(tag) VALUES (7)", 0, 0, 0)
            : sqlite3_exec(db, "SELECT count(*) FROM t", bench_cb, b, 0);
        if (rc != SQLITE_OK) { b->rc = rc; break; }
    }
done:
    if (db) sqlite3_close(db);
    atomic_store(&b->done, 1);
    __builtin_wasm_memory_atomic_notify((int *)&b->done, 1);
    return 0;
}

__attribute__((export_name("start_bench")))
int start_bench(bench_t *b) {
    atomic_store(&b->done, 0);
    pthread_t t;
    return pthread_create(&t, NULL, bench_main, b);
}
