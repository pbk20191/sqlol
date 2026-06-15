# JVM SQLite (WASM + Chicory) 빌드 노트

순수 JVM에서 동작하는 전체 기능 SQLite. 네이티브 코드/JNI 없음.
파이프라인: **SQLite(C) → wasm32-wasi(.wasm) → Chicory AOT(JVM 바이트코드) → Kotlin C API 래퍼**

> **상태 스냅샷 — 2026-06-12 통합 (이어서 작업 시 이 블록 → 필요 시 § 상세 순으로 읽을 것)**
>
> **한 줄 요약**: 단일 JVM 프로세스 안에서, 검증된 메모리 모델 위에, WAL 이론 그대로의 동시성을 내는
> 순수 JVM SQLite — xerial 호환 JDBC 드라이버까지. 모듈 2개: `modules/sqlite`(코어, Kotlin) +
> `modules/sqlite-jdbc`(sqlite4j 벤더링 + WorkerDB, `jdbc:sqlite:` URL).
>
> **실행 모델 = park-carrier (§9.2, 2026-06-12 — 제안: 사용자)**:
> - **wasm pthread = 잠든 정체성 캐리어, 실행 = caller 스레드 직접.** 정체성(스택/TLS/tid)은 인스턴스
>   globals 에 있지 JVM 스레드에 있지 않다 — caller 가 child exports 를 per-child 락 직렬화 하에
>   직접 부른다 (큐/future/워치독/STOP 전부 삭제, per-op ~µs → 락 ~ns; SerializeTest 28.8s→8.0s).
> - spawn 핸드셰이크 = ScopedValue(Phase1/Phase2 상태기계) + future 3개(started/shutdown/lifetime):
>   Phase1(route A, pthread_create 안 — **무예외 구간**: libc __tl_lock 보유, 실패 = 음수 반환→EAGAIN)
>   이 child 를 **eager 생성**(생성 OK/호출 금지 — started 완료 전 exports 호출 금지) 후 exports 동기
>   회신; Phase2(route B, call_indirect slot 0)는 pthread_detach(self) 후 shutdown.join 으로 잠듦
>   (CF = spurious 면역). lifetime(runAsync) = VT 수명 future = **워커 예외 전파 채널**
>   (closeChild = shutdown.complete → lifetime.get = guest exit/detached 자가 해제 완료 보장).
> - 진짜 spawn(sorter)은 스코프 미바인딩으로 구분 + start_args{stack@0,tls@4,fn@8,arg@12} 디스어셈블
>   확정 — fn==0 만 위조 spawn, arg==ARG_SENTINEL 자가검증이 ABI 회귀 감시.
> - 콜백(UDF 등)은 caller 스레드 재진입 — sqlite4j 와 같은 모니터 시맨틱.
>
> **아키텍처 (위→아래):**
> ```
> JDBC modules/sqlite-jdbc — sqlite4j 벤더링(Apache-2.0, 패키지/URL 업스트림 유지) + WorkerDB(xerial DB
>      절단면) + WorkerDBFactory. 코퍼스 437 테스트 0 실패 = xerial 표면 완전 동등 (스텁 0).
> L4   WorkerDbPort (공개 raw-op 포트: 연산 1건 = run 1회 — JDBC 다중 커서 인터리빙 요구상 의도적)
>      + WorkerDbRuntimes (파일 canonical 경로별 런타임 refcount 공유 — DbOwnerLock 양립;
>      :memory: 글로벌 런타임 1 + 워커별 사유 DB + /tmp preopen(backup 중계))
> L3   SqliteDataSource/SqliteConnection (JDBC형 온디맨드) · SqliteWal (writer 1 + reader N 풀 빌림/반납)
>      SqlitePreparedStatement (캐시 밖 사용자 stmt — Session.userStmts registry 가 수명/UAF 가드)
>      SqliteWorker (internal: ChildModule + Session + per-child 락 — run(fn) 직접 실행, stop = 락 하
>      session.close → closeChild) · Session (stmt LRU 64/바인딩 TRANSIENT 스크래치/queryRows 코어스닝/
>      BUSY(5)·PROTOCOL(15) 재시도/성장형 SQL 버퍼)
> L2   JvmVfsRuntime (생성자 = 배선) ├ spawnPthread/closeChild (park-carrier, §9.2)
>      ├ SpawnWorkerHandle (Phase1/Phase2 디스패처 + sorter lazy 위임) ├ JvmCallbacks + CallbackEnv
>      (콜백 13종 — user_data 키 디스패치: 콜레이션은 sorter VT 에서도 호출, 스레드 스코프 금지)
>      ├ WASI 공유 1 + RW-락 └ vfs imports: JvmVfsLocks + ShmArena(8MB arena)
> L1   StatelessBulkMemory — 벌크 = 단건 absolute 합성 (함정 3 근원 수술)
> L0   sqlite3-jvmvfs.wasm = sqlite 3.53(THREADSAFE=1) + os_jvm.c + helpers.c(재링크 2회: pthread_detach
>      keep-alive + 콜백 13종 env import/*Ptr() export) — **--export-all 유지 필수**, wasi-sdk-33, 자기재현 md5
> ```
>
> **동시성 (전부 실측 검증):** WAL 이론 그대로 — reader N 동시 + writer 1 상호 불간섭, JVM 레벨 락 0개
> (xShmLock=JVM synchronized HB + 체크섬 이중읽기 + xShmBarrier→atomic.fence→VarHandle.fullFence).
> 다중 커넥션 동시 writer 안전 (WAL write 락 직렬화). 전부 가상 스레드 — **전제 = JEP 491 (JDK 24+)**.
> PRAGMA threads(sorter) 동작. **단일 프로세스 한정** — wal-index 가 프로세스 사유 linear memory
> (DbOwnerLock 사이드카가 독점 강제, close 시 토큰 왕복 재검증으로 안전 삭제 — 잔재 0).
>
> **불변식 (위반 = 과거 실측 사고):**
> 1. child exports 호출은 child 당 직렬화 (__stack_pointer 전역 공유) — SqliteWorker 락이 담당.
> 2. eager 생성 OK / **eager 호출 금지** (started 전 = TLS/SP 미세팅 → main 스택 파괴).
> 3. route A(= pthread_create 안 호스트 함수)는 **무예외** — Java 예외가 wasm 관통 시 __tl_lock 고아.
> 4. **malloc 계열 혼합 금지 (함정 4)**: sqlite 에 소유권 넘기는 버퍼 = sqlite3_malloc64, sqlite 가 준
>    버퍼 = sqlite3_free. libc free/malloc 과 섞으면 힙 메타데이터 오염 → "보유자 없는 락" 지연 발화.
> 5. 콜백 디스패치 키 = user_data (스레드 무관 — sorter VT 콜레이션). 콜백 안에서 자기 워커 락/큐 재진입 금지.
> 6. main 인스턴스 export 동시 호출 금지 — mainLock (spawn/interrupt 만 잔존).
> 7. wasm 재빌드 시 --export-all + 동일 링크 플래그 (§2 build.sh 에 코드화).
>
> **함정 4종 (각 § 상세 — 전부 근원 수술 완료):**
> - 함정 1 (§7): chicory-wasi fd table thread-unsafe → 공유 WASI 1 + synchronized/RW-락.
> - 함정 2 (§8): 단일 워드 양방향 futex 신호 = wakeup 도둑질 → (현재는 모델 자체가 소멸).
> - 함정 3 (§9.1): ByteBufferMemory 벌크의 position 레이스 → StatelessBulkMemory (Chicory 이슈 후보).
> - 함정 4 (§11.3): libc/sqlite malloc 혼합 → 힙 오염 (진단법: jcmd VT 덤프 + wasm-objdump 함수명 역해석).
>
> **검증 그물:** :modules:sqlite 56 (활성 50: 크래시 복구 4종 포함 + 스킵 6 = @Disabled 스파이크 4 + 성능 벤치 2) + :modules:sqlite-jdbc **437**
> (스킵 23 = 업스트림 자체 + 저널 시맨틱 2 — 상시 WAL 에선 우리가 정답). JDBC 코퍼스 = xerial 무수정.
> 빌드: `WASI_SDK=… ./wasm-lib/build.sh` → out/*.wasm 을 chicory-aot/ 복사 → gradle 이 AOT 재생성.
>
> **성능:** 네이티브 표면 부하(1200w+3200r) 0.26s (~22배 — synchronous=NORMAL + stmt LRU).
> JDBC fine-grained 핫루프는 park-carrier 로 3.4× (마샬링 제거). 지배 비용 = wasm 실행.
> SQLITE_DEFAULT_MEMSTATUS=0 (2026-06-12): mem0 static mutex 를 malloc/free 핫패스에서 제거 —
> 단일 워커 INSERT -6%, 동시 GROUP/ORDER 읽기 -4% (작지만 무손실; 지배 비용이 wasm 실행임을 재확인).
>
> **기각/보류 (재논의 방지):** unix-excl 대체 기각(WASI 에 파일락 시스콜 없음 — os_jvm 이 그 대체물),
> poll_oneoff 락 면제 기각(이득 0), WASI p2/p3 보류(스레드 없음 — wasi-threads 는 p1 전용),
> **chicory-redline 보류 (2026-06-12, §10)**: ⓐ FFM 다운콜은 VT 를 핀 — JEP 491 은 synchronized 만 풀고
> 네이티브 프레임은 여전히 핀하므로, park-carrier 의 "블로킹 지점에서 언마운트" 전제가 깨져 캐리어
> 스타베이션 위험 (피하려 워커를 플랫폼 스레드로 빼면 park-carrier→큐 모델 회귀). ⓑ 네이티브 머신코드
> 생성 = 프로젝트의 "네이티브 코드/JNI 없음" 정체성과 충돌. CPU-바운드는 AOT-on-VT 도 #cores 로 묶여
> 순이득 빈약. → 순수-JVM 실행엔진 레버는 Chicory 바이트코드 AOT 최적화 쪽으로.
>
> **다음 후보:** ① 성능 build-flag 트랙 = **일단락** (MEMSTATUS=0 완료 ✓; redline 은 **보류** — 아래
> 기각/보류; 지배 비용 wasm 실행을 순수-JVM·VT 범위에서 더 깎을 플래그 레버 없음 확인). 순수-JVM 실행엔진
> 레버는 redline 이 아니라 **Chicory 바이트코드 AOT 자체 최적화/업스트림**(③과 묶임). ② 크래시 복구
> 테스트 **완료 ✓**(§13 — wal-index 재구성/찢어진 프레임/stale 락/진짜 kill-9 4종). 다음 리드 =
> ③ Chicory 업스트림 이슈 (함정 3) ④ sqlite4j 업스트림 기여 (THREADSAFE=1 반증 + 함정 4 — 그들도
> lib.malloc+FREEONCLOSE 패턴) ⑤ SqliteWal 빌림 API 공개 / mainLock 도메인 재검토.
>
> **역사적 § 포인터:** §9 부트스트랩 원리 / §9.1 시간순 증거 로그(클로저→바인딩→ResultSet→detached→
> ScopedValue) / **§9.2 park-carrier(현행 모델)** / §10 생태계(sqlite4j·redline·ZeroFs) / §11 JDBC 벤더링 /
> §11.1 코퍼스 이식 / §11.2 콜백 / §11.3 backup·serialize + 함정 4.


## 1. 툴체인: wasi-sdk

[wasi-sdk](https://github.com/WebAssembly/wasi-sdk/releases) 다운로드 (예: `wasi-sdk-33.0-arm64-macos`).

macOS는 다운로드 바이너리에 quarantine이 붙어 clang이 안 뜨므로 제거:
```bash
xattr -dr com.apple.quarantine /path/to/wasi-sdk-33.0-arm64-macos
```

## 2. SQLite → wasm 빌드 (아말감 — autoconf/configure 불필요)

**[2026-06-11 전환]** 소스는 공식 아말감 배포본(`sqlite-amalgamation/`: sqlite3.c/h/ext.h)만 사용한다.
빌드 전체가 `wasm-lib/build.sh` 한 스크립트:

```bash
WASI_SDK=/path/to/wasi-sdk-33+ ./wasm-lib/build.sh
# → wasm-lib/out/sqlite3.wasm (단일, THREADSAFE=0)
# → wasm-lib/out/sqlite3-jvmvfs.wasm (threads + os_jvm VFS)
# 둘을 chicory-aot/ 로 복사 → gradle 빌드가 AOT 자동 재생성 → 전체 테스트
```

레이아웃: `wasm-lib/{build.sh, os_jvm.c, extras/{conn_worker.c, thread_shim.c, kotlin/}}` +
`sqlite-amalgamation/`(아말감 원본, 갱신은 sqlite.org zip 교체). 재현성: 같은 SDK·아말감이면
비트 동일 산출(md5 검증함). 과거 autoconf 폴더(33MB)는 제거됨 — configure 는 초기 탐사(M1)에만
쓰였고 shipped 빌드는 처음부터 직접 컴파일이었다.

(이하 기능 플래그·threads 링크 레시피는 build.sh 에 코드화된 것과 동일 — 역사 기록용으로 보존)

### 기능 정책: deprecated 비활성 + 실험/디버그 제외 + 안정 기능 전부 활성
`--enable-all`(autosetup) 대신 `sqlite3.c` 를 직접 컴파일해 명시적 플래그로 제어한다.

활성(안정):
```
-DSQLITE_OMIT_DEPRECATED           # deprecated 인터페이스 제거
-DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS4 -DSQLITE_ENABLE_FTS5
-DSQLITE_ENABLE_RTREE -DSQLITE_ENABLE_GEOPOLY
-DSQLITE_ENABLE_MATH_FUNCTIONS -DSQLITE_ENABLE_COLUMN_METADATA
-DSQLITE_ENABLE_DBSTAT_VTAB -DSQLITE_ENABLE_DBPAGE_VTAB -DSQLITE_ENABLE_STMTVTAB -DSQLITE_ENABLE_BYTECODE_VTAB
-DSQLITE_ENABLE_NORMALIZE -DSQLITE_ENABLE_PREUPDATE_HOOK -DSQLITE_ENABLE_SESSION
-DSQLITE_ENABLE_STAT4 -DSQLITE_ENABLE_UPDATE_DELETE_LIMIT
-DSQLITE_ENABLE_API_ARMOR -DSQLITE_ENABLE_EXPLAIN_COMMENTS -DSQLITE_ENABLE_OFFSET_SQL_FUNC
-DSQLITE_ENABLE_QPSG -DSQLITE_ENABLE_RBU -DSQLITE_ENABLE_SORTER_REFERENCES
-DSQLITE_ENABLE_NULL_TRIM -DSQLITE_ENABLE_PERCENTILE -DSQLITE_ENABLE_MEMORY_MANAGEMENT
-DSQLITE_ENABLE_COLUMN_USED_MASK -DSQLITE_ENABLE_CARRAY
# JSON / sqlite3_deserialize 는 3.53 기본 내장
```
컴파일 명령:
```bash
clang --target=wasm32-wasi -O2 -c sqlite3.c -o sqlite3-full.o -I. \
  -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION=1 <위 플래그들>
clang --target=wasm32-wasi -O2 -mexec-model=reactor -o sqlite3.wasm sqlite3-full.o \
  -Wl,--export-all,--allow-undefined
```

의도적 제외:
- **실험/디버그**: SNAPSHOT, TREETRACE/WHERETRACE/SELECTTRACE, CURSOR_HINTS, IOTRACE, EXPENSIVE_ASSERT, DEBUG
- **WASI 불가/불필요**: ICU·ICU_COLLATIONS(라이브러리 없음), LOAD_EXTENSION(동적 .so), UNLOCK_NOTIFY(THREADSAFE 필요)
- **프로프라이어터리/특수**: CEROD, ZIPVFS, MEMSYS3/5, 8_3_NAMES, FTS3_TOKENIZER(2-인자 보안 위험)

> 주의: 일부 `SQLITE_OMIT_*` 는 amalgamation 과 안 맞지만 `OMIT_DEPRECATED` 는 단순 `#ifdef` 라 정상 동작(검증함).

### wasi-sdk가 강제로 끄는 것 (WASI preview1 한계)
- `THREADSAFE=0` — 내부 mutex 비활성. **인스턴스-per-커넥션 모델에선 오히려 정답** (동시성은 JVM 레이어에서).
- `OMIT_LOAD_EXTENSION` — 런타임 `.so` 동적 로딩 불가. 정적 컴파일 확장은 전부 포함됨.

## 3. AOT 컴파일 (Chicory)

`chicory-aot/pom.xml` 의 `chicory-compiler-maven-plugin` 이 wasm → JVM 바이트코드 생성.
- `.java` 소스(인터페이스 + `Sqlite3Module`)와 `.class`(AOT 머신 `Sqlite3ModuleMachine`)를 분리 생성.
- **머신 `.class`는 소스가 아니라 컴파일 classpath에 올려야 함** (build.gradle.kts의 `generatedClasses` 참조).
- **두 모듈을 AOT 생성**: `Sqlite3Module`(non-threads, 메인 라이브러리) + `JvmVfsModule`(threads+VFS, M3 WAL).
  → 각각 `*_ModuleExports` 컴파일타임 타입드 인터페이스 제공(문자열 export 조회 불필요, 타입 안전).
  - Chicory AOT 가 **threads/atomics wasm 도 컴파일** 가능함을 확인(`JvmVfsModule`).

## 4. 라이브러리 API (`src/main/kotlin/org/example/sqlite/`)

`java-library` 모듈. 실행 진입점(main) 없음 — 사용은 테스트/외부 코드에서.

| 파일 | 역할 |
|------|------|
| `Native.kt` (internal) | WASM 인스턴스 1개 = 커넥션 1개. export 호출 + linear memory 포인터 마샬링 |
| `SqliteDb.kt` | 커넥션. `openMemory()` / `openFile(dir, name)`, `exec`/`prepare`/`query`/`update` |
| `SqliteStmt.kt` | prepared statement. 전체 타입 bind(int/long/double/text/blob/null) + column 접근 + `row()` |
| `SqliteType.kt` | 컬럼 저장 클래스 enum |

배선 핵심:
- import는 `wasi_snapshot_preview1.*` 뿐 → `chicory-wasi`의 `WasiPreview1.toHostFunctions()` 로 충족.
- reactor 모델: `withStart(false)` + 인스턴스화 후 `_initialize` 호출.
- 메모리는 wasm이 export (`memory`) → `instance.memory()`.
- f64 인자/반환은 `Double.toRawBits()` / `Double.fromBits()` 로 인코딩.
- void 반환 export의 `apply()`는 `null` 반환에 주의.

사용 예:
```kotlin
SqliteDb.openMemory().use { db ->
    db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)")
    db.update("INSERT INTO t(name) VALUES (?)", "alice")
    val names = db.query("SELECT name FROM t ORDER BY id") { it.getText(0) }
}
```

## 5. 테스트 실행

데모/검증은 `src/test/kotlin/org/example/sqlite/SqliteTest.kt` 에 있다.
```bash
./gradlew :modules:sqlite:test
```
커버: 버전, CRUD+바인딩, 전체 타입 바인딩/컬럼 타입, `row()` 매핑, 확장(JSON/math/FTS5/RTree/Geopoly), 파일 WAL.

## 진행 상황 / 로드맵

- [x] **M1** — 전체 기능 빌드 + AOT + 단일 커넥션. `:memory:` 및 파일 DB 동작.
- [x] **M1-WAL** — 파일 DB에서 `locking_mode=EXCLUSIVE` + `journal_mode=WAL`. shm 불필요(힙 wal-index).
- [x] **M2** — 다중 커넥션 동시 접근 (롤백 저널 모드). `DbCoordinator` + busy_timeout. 멀티스레드 안전 검증.
- [x] **M3** — 다중 커넥션 동시 WAL. `wasm32-wasi-threads` + 커스텀 JVM VFS(`xShm*`) + 인메모리 락. (상세 아래 Stage 1~3)
- [x] **M4** — WAL 커넥션 풀 `SqliteWalPool` (다중 워커 동시 접근, 12/12 안정). 상세 §7.

### M2 구현 메모 (완료)
실증 결과 두 가지가 설계를 정했다:
1. **WASI 빌드는 이미 인스턴스 간 락을 한다** — 공유 호스트 파일시스템을 통해. 경합 시 `SQLITE_BUSY`(rc=5) 반환.
2. **WAL 멀티커넥션은 불가** — `journal_mode=WAL` 이 shm 부재로 `delete` 로 조용히 폴백.

그래서 M2는 C VFS 없이 JVM 레이어만으로:
- `DbCoordinator`: 파일 경로별 `ReentrantReadWriteLock`. 쓰기=write lock, 읽기=read lock.
- 트랜잭션 경계는 `sqlite3_get_autocommit` 으로 감지 → BEGIN~COMMIT 동안 write lock 유지.
- `sqlite3_busy_timeout(5000)`: WASI 락 경합 시 즉시 실패 대신 대기·재시도 (reader/reader 경합 흡수).
- 롤백 저널의 change-counter 가 커넥션별 페이지 캐시 일관성 보장.
- 검증: `MultiConnTest` — 멀티스레드 동시 쓰기(유실 없음+integrity), 쓰기 중 동시 읽기(단조 증가),
  멀티스레드 트랜잭션 원자성(롤백 포함). 3회 반복 안정.

한계: 롤백 모드라 writer 가 reader 를 막는다(coarse). "reader 가 writer 를 안 막는" 진짜 동시성은 M3(WAL+shm).

### M3 진행 (threads 빌드 / 공유메모리) — 스파이크 결과

진짜 병렬 WAL 은 여러 인스턴스가 **같은 linear memory 를 진짜 공유**해야 한다(wal-index 가 lock-free
배리어 프로토콜이라 copy-sync 로는 불가). 그래서 wasm 을 `wasm32-wasi-threads`(공유메모리)로 재빌드:

빌드:
```bash
clang --target=wasm32-wasi-threads -pthread -O2 -c sqlite3.c -o sqlite3-ts.o \
  -DSQLITE_THREADSAFE=1 -DSQLITE_OMIT_LOAD_EXTENSION=1 <FEATURES>
clang --target=wasm32-wasi-threads -pthread -mexec-model=reactor -o sqlite3-threads.wasm sqlite3-ts.o \
  -Wl,--shared-memory,--import-memory,--export-all,--allow-undefined,--initial-memory=33554432,--max-memory=2147483648
```
→ `memory[0] ... shared <- env.memory`, import `wasi.thread-spawn`, export `__wasm_init_tls`/`__wasi_init_tp`.

스파이크 검증 (`src/test/.../*SpikeTest.kt`, 모두 인터프리터):
- [x] **ThreadsWasmSpike** — 단일 인스턴스 기동. 공유 Memory 주입 + thread-spawn stub + TLS/메모리 init OK.
- [x] **AtomicWaitSpike** — 두 인스턴스 동시 malloc/free 2000회. Chicory 의 atomic wait/notify(futex) 정상.
- [x] **SharedMemSpike** — 두 인스턴스 공유메모리 순차. 인스턴스별 스택/TLS 설정 정확.
- [~] **ConcurrentSharedMemSpike** (`@Disabled`) — 두 스레드 동시 SQLite. **메커니즘 동작하나 ~25% 플래키.**

인스턴스(=스레드)별 셋업 레시피 (메인 외 워커):
1. 공유 `ByteBufferMemory(MemoryLimits(initial, max, shared=true))` 1개를 모든 인스턴스에 `ImportMemory("env","memory",mem)` 로 주입.
2. `wasi.thread-spawn` 은 stub (SQLite 는 spawn 안 함).
3. 메인 인스턴스: `_initialize` → `sqlite3_initialize`(워커 전 1회 선행, 동시 init 경합 회피).
4. 워커 인스턴스: 자기 스택 영역 할당 후 `global[0](__stack_pointer)` 설정 → 자기 TLS 할당 후
   `__wasm_init_tls(tls)` → `__wasi_init_tp()` (고유 pthread self/tid).
5. `SQLITE_THREADSAFE=1` 필수 (공유 메모리의 SQLite 전역 상태 보호). THREADSAFE=0 은 전역 경합으로 행 유실.

**TID 부트스트랩 (구현·검증 완료, 그러나 플래키 미해소):**
- `__wasi_thread_start_C` 디스어셈블로 확인: tid 는 `pthread_self(=__tls_base+20) + 20` = **`__tls_base + 40`** 에 저장.
- 워커마다 `__wasm_init_tls` → `__wasi_init_tp` 후 `mem.writeI32(__tls_base+40, uniqueTid)` 로 고유 tid 부여(메인 포함).
- 진단 결과 tid 는 정확히 distinct(A=1,B=2), self 도 분리 확인. **그럼에도 동시 실행이 ~37% 플래키.**
- → **잔여 경합은 tid 무관.** 독립 :memory: db 에서도 insert 유실. 의심: (a) Chicory **인터프리터**의 공유
  ByteBuffer 동시 접근 thread-safety, (b) `memory.grow` 동시 호출 경합, (c) malloc/SQLite 전역의 미세 경합.
  다음 조사: AOT 머신으로 실행 시 재현되는지(인터프리터 한정 버그 여부), Chicory 공유메모리 동시성 한계 확인.

**돌파: 정석 wasi-threads 경로는 견고함 (ThreadSpawnTest, 6/6 안정)**
- 수동 인스턴스 셋업(stack/TLS/tid 직접) 대신, **wasm 안의 `pthread_create`** 가 spawn 하도록 한다.
- import `wasi.thread-spawn` 구현: 새 JVM 스레드에서 `parent.imports()`(같은 공유메모리)로 인스턴스 빌드 →
  `_initialize` 호출 안 함 → export `wasi_thread_start(tid, startArg)` 호출.
- 스택/TLS/tid 를 wasi-libc 가 startArg(=pthread_create 가 할당)로부터 정확히 세팅 → 수동 방식의 플래키 근본 제거.
- 검증: `thread_shim.c` 의 `spawn_add(5)` → `pthread_create`→thread-spawn→worker→`pthread_join` → 105. 6/6 통과.
- **함의**: 견고한 병렬 SQLite 는 "JVM 이 인스턴스를 수동 생성"이 아니라 **"wasm 안의 워커 스레드(pthread)가
  커넥션을 쥐고 JVM 명령 큐를 처리"** 하는 구조로 가야 한다(메시지패싱). 그게 다음 단계.

**커넥션-워커 메시지패싱 PoC (ConnWorkerPoCTest, 8/8 안정)**
`conn_worker.c`: wasm 안의 워커 스레드(pthread)가 sqlite3 커넥션을 소유하고 공유메모리 채널로
JVM 의 SQL 을 실행. 검증된 학습:
- **tid = `Thread.currentThread().threadId()`** 로 충분(고유 양수). 카운터 불필요. (i32 절단은 실무상 무시 가능)
- ~~JVM-API 의 notify 는 wasm 의 atomic.wait 을 못 깨운다~~ **(정정: 오진)** — 실제론 Chicory 가 둘을
  **같은 `waitStates` 큐**로 구현한다(바이트코드 확인: wasm `atomic.notify`→`Memory.atomicNotify`→`notify`,
  `atomic.wait32`→`atomicWait`→`waitOn`). 초기 hang 의 진짜 원인은 plain `writeI32` 가시성 부족 lost-wakeup.
  → wasm-only 신호 헬퍼(`ch_request`/`ch_await`/`ch_stop`) + JVM 폴링으로 회피. (브리지 상세는 §8)
- **Chicory 의 `atomic.wait32`/`notify` 는 드물게 lost-wakeup** → 무한 대기(-1) 대신 **짧은 타임아웃(5ms) 폴링**
  으로 데드락 회피(notify 놓쳐도 재확인). 이걸로 8/8 안정.
- 채널: `state@0(i32,_Atomic) rc@4 result@8(i64) sql@16`. state: 0=idle/1=request/2=done/3=stop.

**커스텀 JVM VFS — 다중 커넥션 WAL (인메모리 락 기반)**

THREADSAFE 와 무관하게, WAL 은 **VFS 의 shm(wal-index) 메서드**가 있어야 켜진다(WASI 기본엔 없어 `delete` 폴백).
`os_jvm.c`: 기본 WASI VFS 를 복사(파일 I/O 위임)하고 락/shm 만 import 로 빼낸 커스텀 VFS.
- iVersion=2 io_methods + xShmMap/xShmLock/xShmBarrier/xShmUnmap 제공.
- shm 바이트는 **공유 linear memory 의 arena**(JVM 이 malloc 1회로 확보 후 region 분배) → 모든 커넥션이 같은 포인터 = copy-sync 불필요.
- 락/shm-lock 은 **JVM import**(인메모리). `xShmLock` 은 대부분 try-lock 이라 futex 불필요 → flaky 회피.
- 등록: 1회성 export `install_jvm_vfs()` 가 `sqlite3_vfs_register(makeDefault=1)`. (VFS 는 auto_extension 이 아니라 1회 install. auto_extension 은 함수/vtab 등록용)
- import (module "vfs"): lock/unlock/check_reserved/shm_map/shm_lock/shm_unmap. 파일 키 = 파일명 문자열.

진행:
- [x] **Stage 1** (`JvmVfsStage1Test`) — 단일 커넥션이 커스텀 VFS 로 파일 DB 를 열고 `journal_mode=WAL` → **"wal" 진입 성공**(이전 "delete" 폴백 해소). 락/shm-lock 은 permissive.
  - 함정: Chicory `ByteBufferMemory.fill(value, fromIndex, toIndex)` 는 (from,to) 시맨틱 — 길이로 넘기면 음수 범위 OOB.
- [x] **Stage 2** (`JvmVfsLocks` + `JvmVfsLocksTest`) — 실제 인메모리 락 매니저.
  - VFS import 에 **conn(파일 핸들 포인터) 식별자 추가**(파일명만으론 커넥션 구분 불가): `lock(conn,name,level)` 등.
  - 파일 락: SHARED 공존 / RESERVED 단독 / EXCLUSIVE 배타 상태기계. shm 락: 8슬롯 슬롯별 shared(다중)·exclusive(단독).
  - 단위 테스트로 시맨틱 검증 + Stage 1 회귀 통과(단일 커넥션엔 BUSY 없음).
- [x] **Stage 3** (`JvmVfsStage3Test`) — **다중 워커 동시 WAL, 같은 파일.** 목표 달성.
  - 자율 워커(`conn_worker.c`의 `start_bench`/`bench_main`): 각자 파일 열고 `PRAGMA journal_mode=WAL`+busy_timeout 후 INSERT/SELECT 반복. JVM 은 spawn 후 done 플래그 폴링 + 검증.
  - 검증: writer 2(각 50) + reader 1 동시 → 최종 100행 정확, `integrity_check=ok`.
  - `ShmArena` thread-safe(동시 shm_map), `shm_unmap(deleteFlag)` 시 region 비워 다음 세션 0 재초기화.
  - **플래키 해소**: 초기 ~4% 실패(rc=10 SQLITE_IOERR)의 원인은 **워커들이 하나의 `WasiPreview1`(fd 테이블)을
    공유**한 동시 파일 I/O 경합. → **워커마다 독립 WASI** 부여(공유메모리·VFS 락은 공유)로 해결. AOT 경로 10/10 안정.

## 결론
WASM(`wasm32-wasi-threads`) SQLite + Chicory + JVM 보조 커스텀 VFS 로 **순수 JVM 위에서 다중 커넥션 WAL 동시성**을 달성. 네이티브/JNI 없음. (M1 단일 WAL, M2 롤백 다중커넥션, M3 WAL 다중커넥션)

## 6. 라이브러리 구성 (M3 WAL 런타임)

C 측 (`wasm-lib/`, threads 빌드 → `sqlite3-jvmvfs.wasm`):
- `os_jvm.c` — 커스텀 VFS(기본 VFS 복사 + 락/shm 만 import). `install_jvm_vfs()` 로 등록.
- `conn_worker.c` — pthread 워커: 채널 기반(`ch_*`) + 자율 벤치(`start_bench`).
- `thread_shim.c` — wasi-threads 동작 검증용(`spawn_add`).

JVM 측 (`src/main/kotlin/org/example/sqlite/`):
- `JvmVfsLocks.kt` — 파일/shm 인메모리 락 매니저(커넥션별).
- `ShmArena.kt` — wal-index 공유메모리 region 분배(thread-safe).
- `JvmVfsRuntime.kt` — **AOT(`JvmVfsModule`) + 타입드 exports(`JvmVfsModule_ModuleExports`)** 기반.
  공유메모리 + (워커별)WASI + thread-spawn + VFS import 배선 + install. `openDb`/`exec`/`scalar*`/`spawnBench`/`awaitAll` 제공.
- `SqliteWalPool.kt` — WAL 커넥션 풀 (M4).

## 7. WAL 커넥션 풀 (`SqliteWalPool`)

각 워커 = 파일+WAL 커넥션을 쥔 wasm pthread 스레드(채널 기반). JVM 은 풀에서 워커를 빌려 SQL 제출, 여러 스레드 동시 접근.
```kotlin
SqliteWalPool.open(hostDir, "app.db", size = 4).use { pool ->
    pool.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, tag INTEGER)")
    // 여러 스레드에서 동시에:
    pool.exec("INSERT INTO t(tag) VALUES (1)")   // writer
    pool.queryLong("SELECT count(*) FROM t")     // reader (동시, 첫 컬럼 정수)
    pool.queryText("PRAGMA integrity_check")     // reader (동시, 첫 컬럼 텍스트, 빈 결과→"")
}
```
견고화에 필요했던 4가지 (12/12 안정):
1. **파일 선-생성**: 워커 spawn 전 1회 open+WAL+close (동시 생성 경합 방지).
2. **순차 워커 spawn+warmup**: 워커를 하나씩 띄우고 첫 쿼리로 open 완료 보장 (동시 -wal/wal-index 생성 경합 방지).
3. **writer 직렬화**(`ReentrantReadWriteLock`): WAL 은 writer 1명만 허용. 다중 writer 가 wal-index 를 동시 갱신하면
   공유메모리 가시성 경합으로 **커밋 유실**(Chicory 배리어 한계). writer 직렬화 + reader 동시 허용으로 해결.
4. **BUSY(5) 재시도**: 일시적 락 경합(특히 startup) 흡수.

> 신호 방식: 채널은 **무폴링 블로킹**(req/done 2주소, `start_worker_file_2addr`). JVM 은 `atomicWait(done)` 로
> park, 워커는 `atomicNotify(done)` 로 깨움. 폴링 안 함. (전환 경위·원인은 §8) — 이전엔 `ch_request`+JVM 폴링.

> 한계/주의: 다중 writer 의 진짜 병렬 쓰기는 Chicory 공유메모리 배리어 가시성 한계로 유실 위험 → JVM 에서 직렬화함
> (WAL 의 "writer 1명 + reader 다수" 모델과 일치). 쓰기 처리량보다 동시 읽기 + 안전성을 우선.

> **[2026-06 부하 테스트 — 동시 reader 근본 원인 = WASI fd table thread-safety, 해결됨]**
> `SqliteWalPoolLoadTest`에서 **동시 reader 다수일 때 transient rc=26(NOTADB)/11(CORRUPT)** 재현. 조사:
> - tearing 아님(`TearingProbeTest`: 정렬 int 동시 접근 1e8회 torn=0 — ByteBuffer 정렬 word 접근은 원자적).
> - 스레드 종류 무관(platform/virtual 둘 다 플래키).
> - **진짜 원인 = WASI fd table 의 thread-safety 부재.** `Descriptors`(chicory-wasi)는 `ArrayList`+`TreeSet` 을
>   무동기화로 alloc/free/get → 여러 워커가 한 WASI 를 공유하면 fd 해석이 깨짐(원래 Stage3 IOERR 의 정체).
>   워커별 독립 WASI 우회는 wasi-threads 의미(프로세스당 fd table 1개)와 어긋나고 rc=26 잔존.
> - **해결(정석) = 공유 WASI 1개 + host function 을 `synchronized(wasi)` 로 래핑**(JvmVfsRuntime: `lockedArray`).
>   fd table 을 thread-safe 화하고 wasi 호출을 직렬화(파일 I/O happens-before 도 부수 확보). 워커는 `parent.imports()`
>   로 그 공유 WASI 를 사용.
> - 검증: 동시 reader(readLock) 유지한 채 6w×200 + **8r×400, 18/18 안정**(~6s, maxSeen=1200). 직렬화 불필요.
> - 교훈: rc=26/11 은 wal-index shm 코히런스가 아니라 **fd table 자료구조 경합**이었다(직감 적중).

## 8. JVM ↔ wasm wait/notify 브리지 (조사 결론)

**연결돼 있는가? → 그렇다 (같은 큐).** Chicory 바이트코드 추적:
```
wasm memory.atomic.notify → Shaded.memoryAtomicNotify → Memory.atomicNotify(default) → notify(addr,n)
wasm memory.atomic.wait32 → Shaded.memoryAtomicWait32 → Memory.atomicWait(default)  → waitOn(addr,exp,to)
JVM  mem.notify / mem.waitOn ───────────────────────────────────────────────────────→ 동일 메서드
```
`ByteBufferMemory` 가 주소별 `waitStates`(WaitState monitor)로 구현 → wasm 명령과 JVM API 가 **물리적으로 같은 wait-queue**.
전제: 메모리가 `shared`(`notify` 는 `if(!shared) return 0`).

**[당시 관측 — 아래 추가 검증에서 원인 재진단됨. 결론은 다음 블록 참조]**
- 워커 무한대기(-1) + JVM `atomicWriteInt`+`notify`: 간헐적 hang. (당시 "lost-wakeup/미세 경합"으로 봤으나,
  실제 원인은 **단일 워드 양방향 신호의 wakeup 도둑질**. notify 큐 결함 아님 — 아래 참조.)
- 다중 JVM 스레드 + 다중 wasm 워커: 경합/데드락 관측. (동일 원인.)
- → 당시 임시 결론은 "wasm-only `ch_*` + JVM 폴링". 그러나 **req/done 주소 분리로 무폴링 블로킹이 가능**함이
  아래에서 입증되어, 핫패스도 블로킹으로 전환함.

**[2026-06 검증 — "폴링 없이 가능한가?" 결론: 가능. 단 방향별로 주소를 분리해야 함]**
Chicory 1.7.5 기준 스파이크로 못박음. (초기엔 원인을 "가시성"으로 오진했다가 인터프리터 소스 확인 후 정정)
- **인터프리터 사실확인**: wasm `atomic.load/store` 는 `InterpreterMachine` 에서 `Memory.atomicReadInt`/
  `atomicWriteInt`(= `synchronized(lock(addr))`)로 낮춰진다. **monitor 동기화되므로 가시성 문제는 없다.**
- `FutexBridgeSpikeTest`(순수 JVM, `req`/`done` **두 주소**): 핑퐁 2만 + 6워커, lost-wakeup·stale·herd 0.
- `ConnWorkerBlockingSpikeTest`(실제 브리지, 단일 `state` 워드로 양방향, `wait32(-1)`): **첫 INSERT 에서 교착**
  (@Disabled 보존). 스택: driver/worker 둘 다 `waitOn`, state=1.
  - **진짜 원인 = wakeup 도둑질**: 워커와 드라이버가 **같은 주소(state@0)** 에서 대기. `pendingWakeups` 는
    waiter 종류를 구분 못 하는 단일 카운터라, 드라이버가 워커용 wakeup 을 가로채 소비 → 워커는
    `pendingWakeups==0` 보고 재수면(`waitOn` 은 값 조건을 재검사하지 않음) → state=1 에서 둘 다 영구 대기.
    리눅스 futex 도 동일한 "한 워드 양방향 신호" 함정. **가시성/lost-wakeup 큐 결함이 아니라 채널 설계 버그.**
- **해결(검증됨) — `ConnWorker2AddrSpikeTest`**: 방향별 주소 분리. 워커는 `req@0` 에서만, 드라이버는 `done@4`
  에서만 대기 → 각 주소에 waiter 1종류뿐이라 가로채기 불가. `start_worker_2addr`(req/done/rc/result/sql).
  **-1 무한 대기, 폴링/타임아웃 백스톱 0** 으로: 단일 2001 submits 1.0s(~1940/s), 4워커 2004 submits 0.6s,
  3/3 안정. (인터프리터도 ~2000 submits/s — 느린 게 아니라 교착이었음.)
- **부수 발견 + 수정**: `ch_request`/`ch_await` 도 `state@0` 공유라 동일 취약 → ConnWorkerPoCTest 가 실은
  플래키(2/3 실패)였음. 임시로 `ch_await` 에 5ms 백스톱 적용해 5/5 안정화. **정석 수정은 주소 분리**(req/done).
- **함의(정정)**: 폴링은 필수가 아니다. 함정은 **단일 워드 양방향 신호**다. 핫패스를 무폴링 블로킹으로 가려면
  채널을 req/done 2워드로 설계하면 된다(길 2 = 블로킹을 JVM 밖으로 빼는 것은 별개 선택지로 여전히 유효).
- **적용 완료**: `SqliteWalPool` 핫패스를 폴링→**무폴링 블로킹**으로 전환. `start_worker_file_2addr`(req/done
  bchannel_t) + `submitOnce` 가 `atomicWriteInt(req)+atomicNotify(req)` 게시 후 `atomicWait(done, ...)` 로 블록
  (30s 는 데드락 watchdog, 핫패스 폴링 아님). writer 직렬화(rwl)는 유지. 풀 5/5 + 전체 스위트 그린.


## 9. 스파이크 — 순수 JVM pthread 부트스트랩 (C 워커/재링크 없이)

**질문**: child Instance 를 wasm/wasi 메서드 호출만으로(작은 C 진입점 없이) 견고하게 부트스트랩할 수 있는가?
**답**: 가능. 수동 부트스트랩(~33% 플래키)의 정체는 JVM 이 startArg 를 *위조*해야 했던 것인데, 현재 배포
wasm 은 `--export-all` 이라 그 위조를 libc 에게 시킬 수 있다. (`JvmPthreadBootstrapSpikeTest`)

경로:
1. JVM 이 main 의 **exported `pthread_create(pt, 0, START_IDX=0, key)`** 호출 → libc 가 스택/TLS/musl
   `struct pthread` 를 정석 할당·초기화 → `wasi.thread-spawn`(우리 핸들러) 트리거.
2. 핸들러: child 빌드 시 **child 의 `wasi.thread-spawn` import 슬롯에 "Java 워커 본체" host fn 바인딩**
   (모듈 import 섹션은 불변이라 기존 슬롯 재활용 — 시그니처 (i32)->(i32) 일치, child 는 spawn 안 하므로 안전)
   + **child 테이블 slot 0 에 그 함수 ref `setRef`** → `wasi_thread_start(tid, startArg)`.
3. libc 자식측 초기화 후 `call_indirect(0, key)` → **Java 본체가 정식 wasm pthread 위에서 실행**.
   본체는 child exports 로 SQL 실행, 채널/대기/직렬화는 전부 j.u.c (공유메모리 채널 불필요).
   본체 리턴 → libc thread-exit → `pthread_join` 정상.

함정 2개 (스파이크에서 실측):
- **LLD 테이블은 min=max(=768) 고정** → `grow()` 가 -1 로 조용히 실패. → slot 0(LLD 가 null 함수 포인터
  트랩용으로 예약, 정상 코드는 call_indirect(0) 안 함)을 child 테이블에서만 `setRef` 로 덮어쓴다.
- **기존 견고화 규율은 부트스트랩 방식과 무관하게 그대로 필요**: 동시 spawn(동시 open/WAL/grow)은
  OOB/BufferOverflow/rc=1 로 ~58% 깨짐 → 순차 spawn+warmup 필수. reader-during-write 도 미검증 영역
  그대로(~25% 깨짐) → rwl(write 배타) 시맨틱 유지. 둘 다 적용하면 12/12.

### §9.1 본 구현 — `SqliteWal` + `JvmVfsRuntime.spawnPthread` (main, shipped)

**[2026-06-11 — 다중 행/열 (= ResultSet): 코어스닝 materialize, JDBC 선행 조건 완결]**
- 표면: `query(sql, vararg params): List<SqliteRow>` — SqliteConnection / SqliteWal(reader 풀) /
  SqlitePreparedStatement.query() 모두. 코어: Session.decodeRows (queryRows=LRU 캐시 경로 /
  runUserRows=사용자 stmt 경로 공용).
- **코어스닝이 설계의 전부** (§10 sqlite4j 분석에서 예고): 쿼리 1건 = 워커 태스크 1건 = stmt 를 끝까지
  step 하며 전 행 디코드. 효과 3종 — 행당 큐 왕복 0 / 단일 태스크라 **스냅샷 원자**(다른 문장 인터리빙
  불가) / 커서를 워커에 열어두지 않으므로 **공유 큐 워크스틸링과 호환** (lazy 커서였다면 SqliteWal 불가).
  트레이드: 결과 전체가 JVM 힙 — 대형 결과는 LIMIT (문서화). 커서 수명 문제는 발생 자체가 없음.
- `SqliteRow`: 값은 storage class 그대로 (column_type 스위치: INTEGER→Long, FLOAT→Double, TEXT→String,
  BLOB→ByteArray, NULL→null) + 컬럼명 접근(indexOf) + get* 는 sqlite 형변환 근사 (NULL→0/""/빈 배열).
  TEXT/BLOB 디코드는 column_text/blob **후** column_bytes (sqlite 규약) + readString/readBytes
  (StatelessBulkMemory 라 벌크 안전, 임베디드 NUL 도 정확).
- BUSY/PROTOCOL: decodeRows 가 null 반환 → reset 후 **통째로 재시도** (중간 행까지 갔어도 처음부터 —
  부분 결과 노출 없음).
- 검증: `SqliteQueryRowsTest` 5종 — storage class/컬럼명/NULL, 빈 결과+1000행 1태스크, 형변환,
  PS 재바인딩 query, SqliteWal reader 풀 동시 40건. 전체 스위트 그린.

**[2026-06-11 — 바인딩 (= PreparedStatement): 2-레이어, 클로저 일반화의 첫 수확]**
- **레이어 ① vararg (전 표면)**: `execute/queryLong/queryText(sql, vararg params)` (SqliteConnection +
  SqliteWal). 백엔드 = Session.query 에 bindAll 추가 — **LRU 캐시 stmt 그대로 재사용** (바인딩으로 SQL
  텍스트가 상수 → 캐시 적중 100%, "값 보간 시 무한 누적" 문제 자체가 소멸). 실행 후 reset+clear_bindings.
  **워크스틸링 안전**: 캐시 키가 SQL 문자열이라 공유 reader 큐에서 어느 워커가 집어도 자기 stmt 에 적중
  (raw stmt 공유가 아님) — SqliteWal 에 바인딩을 줄 수 있는 유일한 형태.
- **레이어 ② `SqlitePreparedStatement`** (SqliteConnection.prepare, JDBC 대응): 캐시 밖 사용자 소유 stmt.
  - **수명 = Session.userStmts registry**: prepareUser 등록 → finalizeUser(멱등) → 잔존분은 Session.close
    가 일괄 finalize (커넥션 선닫힘 시 누수 없음 + runUser 의 등록 확인이 use-after-finalize 가드 겸함).
    커넥션 close 는 열린 PS 를 markClosed 만 (제출 없음 — STOP 뒤 태스크는 실행 불가).
  - 파라미터는 JVM 쪽에 모았다가 execute 시 **한 태스크로 bind+step(+changes)** — 행당 왕복 1회,
    공유 큐 인터리빙 불가, execute() 가 sqlite3_changes 반환.
- **게스트 버퍼**: bind 는 전부 SQLITE_TRANSIENT(-1) — sqlite 가 호출 중 복사하므로 워커당 성장형 스크래치
  (2배 성장, 워커 전용) 1개 재사용. 정상 상태 malloc 0 유지. scratch(0) 도 유효 포인터 (빈 문자열 ≠ NULL).
- BUSY/PROTOCOL 재시도: runUser 는 reset 후 **전체 재바인딩** (bind 멱등) — run 과 동일 시맨틱.
- 부수 견고화: `drainAfterStop` — STOP 후 큐 잔여 태스크를 30s watchdog 대신 즉시 실패 처리 (stop/SqliteWal.close).
- 검증: `SqlitePreparedStatementTest` 5종 — 전 타입 라운드트립(+빈 문자열=text), 인젝션 차단, 100회 재바인딩
  +영향행수, 수명(이중 close/use-after-close/커넥션 선닫힘), SqliteWal 동시 워크스틸링 바인딩. 전체 스위트 그린.

**[2026-06-11 — SpawnWorkerHandle 무상태화: "위조 spawn vs 진짜 spawn" 을 start_args 에서 직접 판별]**
- 기존: 디스패처가 main 빌드 후 주입받는 `var mainInstance` 로 `instance !== mainInstance` 비교 —
  mutable 주입 + "main 인스턴스 위 guest spawn" 케이스를 위임에서 누락하는 시맨틱 구멍.
- 해소: else 분기(진짜 thread-spawn)에서 받는 v = wasi-libc `start_args*` 를 직접 읽는다.
  **레이아웃 디스어셈블 확정** (`wasi_thread_start`: @0→__stack_pointer, @4→__tls_base;
  `__wasi_thread_start_C`: call_indirect(table[@8], @12) — 즉 {stack@0, tls_base@4, start_func@8, start_arg@12};
  부수 확인: tid 가 (__tls_base+20)+20 atomic.store — 기존 §M3 tid=+40 발견과 일치).
- 판별: **start_func==0 ⟺ spawnPthread 의 위조** — slot 0 은 LLD null-trap 예약석이라 정상 guest 코드는
  null 함수 포인터로 pthread_create 불가 (키/포인터 구분과 같은 값 공간 논증, 휴리스틱 아님).
  fn≠0 만 delegatedSpawns 로 카운트 = "guest 발 진짜 spawn" (main 인스턴스 발이어도 정확).
- ABI 자가 감시: 위조 spawn 의 start_arg@12 는 방금 등록된 worker key (mainLock 직렬화 + 소비는 route B)
  → 매 spawnPthread 가 `containsKey(arg)` hard-check 로 레이아웃 회귀 검증을 겸함. wasi-sdk 업그레이드로
  레이아웃이 틀어지면: fn 오독 → 위임 0 회귀(SqliteWorkerClosureTest) 또는 containsKey 즉발.
- 디스패처는 이제 호출 내용만으로 모든 판별 (불변 객체, 외부 상태 주입 0). 죽은 코드 WorkerPSpawn 제거.
- 검증: 전체 스위트 그린 — 위임 0 방향(SqliteWorkerClosureTest 3종) + sorter 위임 ≥1 방향(SqliteWalTest) 동시 통과.

**[2026-06-11 — SqliteWorker 클로저 일반화 (바인딩/ResultSet/WorkerDB 의 선행 조건)]**
- `SqlTask(sql, isQuery)` 고정형 → **`WorkerTask<T>(fn: (Session) -> T)`** — 워커 본체가 큐에서 꺼내
  자기 Session 에 대해 클로저를 실행하고 결과(타입드 T)/예외를 CompletableFuture 로 회신.
  기존 `submit(sql, isQuery)` 는 `{ s -> s.run(sql, isQuery) }` 편의 오버로드로 유지 — 호출부 무변경.
- `Conn` → **`Session`** (internal 노출): db 를 생성 시 바인딩(open 실패 = 생성 예외), 클로저 표면 =
  고수준 `run`/`exec`/`query` + 저수준 `x`(child exports)/`mem`/`db`/`sqlPtr`/`errmsg`.
  stmt LRU 캐시는 비공개 유지 (외부 stmt 수명은 다음 단계 — sqlite4j SafeStmtPtr 패턴 예정).
- 시맨틱: **한 클로저 = 큐 항목 1개** → 다단계(BEGIN~COMMIT)가 인터리빙 없이 원자적 (공유 reader 큐에서도).
  BUSY/PROTOCOL 재시도는 클로저 전체가 아니라 `s.run` 단위 — 클로저는 비멱등일 수 있어 통재시도 금지.
- 불변식 (기존 그대로): 클로저는 워커 스레드 위 = **자기 child exports 만** (main export 호출 금지 — mainLock 도메인).
- 검증: `SqliteWorkerClosureTest` 3종 — 트랜잭션 커밋/롤백 원자성, raw export 조립(prepare→step 루프→finalize,
  List<String> 반환 = ResultSet 전구체), 클로저 예외 전파 + 워커 생존. 전체 스위트 그린.

**[2026-06-11 — `sqlite3_interrupt` 쿼리 취소 (sqlite4j 분석의 1순위 수확)]**
- 기존 결함: submit watchdog 이 30s 후 예외만 던지고 **실행 중 문장은 워커에서 계속 돌았음**.
- `SqliteConnection.cancel()` → `JvmVfsRuntime.interruptDb(dbPtr)` — sqlite3_interrupt 는 "타 스레드 호출
  안전"이 문서화된 API(db 플래그 쓰기). 실행 중 문장이 rc=9(SQLITE_INTERRUPT)로 즉시 풀리고 커넥션은 재사용 가능.
- 동반 불변식 정리: **main 인스턴스 export 동시 호출 금지**(단일 __stack_pointer) → `mainLock` 으로
  spawn/join/interrupt 직렬화. **pthread_join 은 main 위에서 블로킹**하므로 워커 본체 종료를 JVM 래치
  (`SpawnedWorker.done`)로 먼저 기다린 뒤 join 은 순간 호출 (join 중 cancel 불가 데드락 제거).
  공유 reader 큐의 종료는 "STOP 전부 게시 → done 전부 대기 → join 전부" (STOP 을 누가 집을지 모름).
- 검증: 무한 재귀 CTE 를 cancel 로 ~50ms 내 중단 + rc=9 + 커넥션 재사용, 전체 스위트 그린.

### §10. 생태계 분석 (2026-06-11) — sqlite4j / chicory-redline / ZeroFs

**sqlite4j (roastedroot, xerial 포크)** — 같은 파이프라인(SQLite→wasm→Chicory AOT), 정반대 선택:
THREADSAFE=0·WAL 없음·풀=1·ZeroFs 인메모리(내구성 수동). 대신 **xerial 완전 JDBC 표면 + 테스트 코퍼스** 보유.
상호보완 정확히 합치 — 그들의 한계 3종이 우리의 해결 3종, 우리의 로드맵 3종이 그들의 보유 3종.
- **절단면**: xerial 의 추상 `DB` 클래스 (NativeDB=JNI / WasmDB=단일스레드 Chicory / **WorkerDB=우리** 가능).
  선행: SqliteWorker 태스크의 클로저 일반화 + 행 페치 배치(코어스닝).
- **가져올 것**: ① sqlite3_helpers.c 패턴 — `extern env import 선언 + &취하는 *Ptr() export` 3줄로 콜백마다
  타입드 테이블 슬롯(LLD 가 등재) → UDF/xBusy/xProgress/콜레이션/훅 13종. 우리 slot-0 약탈의 링크타임 정석
  (다음 재링크 1순위). ② sqlite3_interrupt (적용 완료, 위). ③ Online Backup API 사용 패턴(backup_init/step/
  remaining/finish + ProgressObserver). ④ SafeStmtPtr(volatile closed + DB 모니터로 raw ptr 호출 직렬화 +
  이중 close rc 캐시) — ResultSet 작업 동반자. ⑤ userData→Store 레지스트리(우리 workerBodies 와 동형).
  ⑥ WasmDBExports 메서드 집합 = 바인딩/ResultSet 체크리스트. ⑦ 빌드 플래그 후보: --stack-first,
  SQLITE_DEFAULT_MEMSTATUS=0(멀티스레드에서 mem0 뮤텍스 제거 — **적용 완료 2026-06-12**, §성능), -Oz+strip.
- 그들 build.sh 주석에 "THREADSAFE=1 은 wasm 에서 불가" — 우리가 반증 보유 = 업스트림 기여 포인트.

**chicory-redline (실험)** — Chicory AOT 의 드롭인 대체: Cranelift(wasm 으로 컴파일되어 Chicory 위에서
빌드타임 실행)로 **네이티브 머신코드** 생성, FFM(25+)/jffi 로딩, 미지원 플랫폼은 바이트코드 폴백.
- 절단면 동일(Machine/Memory/Table — withMachineFactory 한 줄 스왑 구조). NativeMemory=MemorySegment
  (벌크가 구조적으로 stateless → 함정 3 자연 소멸, StatelessBulkMemory 불요). NativeTable.setRef 존재.
- **단 "Threads 지원" = atomics 인트린식 + shared 플래그 수준** — thread-spawn 없음(원래 임베더 몫 — 우리
  소유물이라 이식 대상), **공유 메모리 다중 인스턴스 실증 없음**.
- **[2026-06-12 보류 — park-carrier 와 구조적 충돌]**: redline export 호출 = FFM 다운콜인데 **JEP 491 은
  synchronized/Object.wait 만 언피닝하고 네이티브 프레임은 여전히 캐리어에 핀**(근본 제약). park-carrier 는
  "caller VT 가 직접 실행하고 블로킹 지점(wasi 락/shm 락/futex)에서 언마운트"가 전제인데, redline 에선 그
  블로킹이 네이티브 프레임 안에서 일어나 언마운트 불가 → 동시 caller > 캐리어(JDBC 풀) 시 **스타베이션/
  스루풋 붕괴** (우리가 JEP 491 채택으로 없앤 실패 클래스의 부활). 피닝 회피로 워커를 플랫폼 스레드에
  돌리면 park-carrier→큐/마샬링 모델 회귀(3.4× 손실). + 네이티브 머신코드 = "네이티브 코드/JNI 없음"
  정체성 충돌. CPU-바운드는 AOT-on-VT 도 #cores 묶임이라 순이득 빈약. **→ 성능 실행엔진 레버는 redline 이
  아니라 Chicory 바이트코드 AOT 자체 최적화/업스트림.** (NativeMemory 의 stateless 벌크는 함정 3 의 독립
  참고점으로만 유효.)

**ZeroFs** — 인메모리 NIO FS (Jimfs 계열 무의존 포트). 우리에겐 "ephemeral 모드" 옵션 후보:
preopen 을 ZeroFs 로 → **공유 :memory: + 완전 WAL 동시성, 디스크 0 접촉** (sqlite4j 의 ":memory: 는
커넥션별 분리" 한계와 우리의 "공유엔 디스크 필요" 한계를 동시 해소). 테스트/캐시 용도. 확인 필요:
chicory-wasi 의 ZeroFs Path 수용(sqlite4j 가 동작 증거), DbOwnerLock 의 tryLock 미지원 시 스킵.

**[2026-06-11 — `DbOwnerLock`: 다중 프로세스 단독 소유 강제]**
- 락 도메인의 정직한 정리: os_jvm 의 xLock 은 **도메인 안(런타임 내) 조율자** (WAL 수명주기 — SHARED 상시
  보유/마지막 close 의 EXCLUSIVE-wal정리/저널모드 전환에 여전히 필수). 도메인 밖(타 프로세스·같은 JVM 의
  중복 런타임)은 무방비였음 — "런타임이 파일을 독점한다"는 운영 전제를 강제할 수단이 없었다 (사실상 우리는
  unix-excl 의 JVM 판인데 독점 강제만 빠진 상태).
- 해소: `DbOwnerLock` — 호스트 JVM 은 진짜 OS 프로세스이므로 `FileChannel.tryLock()` 으로 강제.
  **사이드카(`<db>.jvmlock`)에 거는 이유**: POSIX fcntl 은 "프로세스가 그 파일의 아무 fd 나 닫으면 락이
  풀림" — guest 가 워커 close 마다 DB fd 를 닫으므로 DB 파일 자체의 호스트 락은 증발한다. openRuntime 선획득
  (실패 시 즉시 거부 + 명확한 예외), close finally 해제. 같은 JVM 중복은 OverlappingFileLockException 변환.
- 한계(명시): 비협조적 네이티브 도구(sqlite3 CLI)는 사이드카를 안 보므로 못 막음. 또한 진짜 fcntl 을
  aSyscall[7] 수술(테이블 host fn + pCurrent 덮어쓰기)로 구현해도 **다중 프로세스 공유는 불가** —
  wal-index 가 프로세스 사유(linear memory) 라서 네이티브 측 -shm 파일과 분단됨. 공유가 아니라 배제가 상한.
  (wasi-libc fcntl 소스 확인: F_SETLK/GETLK 는 default→EINVAL, guest 안에서 즉사 — import 로 못 가로챔.
  wasip2/p3 분기에도 락 없음)

**[기각 — unix-excl 로 os_jvm.c/JvmVfsLocks/ShmArena 대체 가설]** (2026-06-11, `UnixExclSpikeTest` @Disabled)
unix-excl(힙 wal-index, 프로세스 내 공유)은 단일 JVM 에 이상적으로 들리지만, **WASI 엔 파일 락 시스콜이
없어** osFcntl(F_SETLK) 경로가 죽어 있음 — unix-excl 은 첫 쓰기의 프로세스 배타 락부터 rc=10. 같은 이유로
커스텀 VFS 의 존재 이유가 재확인됨: os_jvm.c = WASI 에 없는 락/shm 의 호스트(JVM) 대체물.

**[2026-06-11 — JDBC 형 온디맨드 커넥션 + 워커 코어 추출]**
- `SqliteWorker`(internal): 워커 본체(stmt LRU 캐시/PRAGMA/BUSY·PROTOCOL 재시도) 공통 코어 —
  큐를 공유하면 워크스틸링 풀(SqliteWal reader), 전용으로 주면 1:1 커넥션.
- `SqliteDataSource.getConnection()`: **필요할 때마다 워커 spawn** (JDBC DataSource/Connection 형태) —
  spawn 은 DataSource 내부 락으로 직렬화(§9 규율 캡슐화). `SqliteConnection.close()` = STOP+join. 멱등,
  DataSource.close 가 잔여 커넥션 정리.
- **다중 커넥션 동시 writer 재판정: 안전.** 과거 "다중 writer 커밋 유실" 경고는 함정 3(position 레이스) 진단
  전의 측정 — StatelessBulkMemory 이후 4 커넥션 × 50 동시 INSERT, 10/10 유실 0 + integrity ok.
  writer 간 직렬화는 WAL write 락(JvmVfsLocks=JVM 락) + busy 재시도가 담당.
- JDBC 매핑 경로: DataSource→javax.sql.DataSource, SqliteConnection→Connection, execute→Statement.execute.
  다음 단계 = 다중 행/열 ResultSet + 바인딩(PreparedStatement).

**[2026-06-10 후속 — WAL 이론 동시성 달성: reader-during-write 허용, rwl 제거]**
- 재검토 동기: "reader-during-write ~25% 실패" 측정은 **함정 3(벌크 position 레이스) 진단 전**의 것 — 오염된 증거.
- 이론 확인: wal-index 조정 쓰기는 전부 xShmLock(=JvmVfsLocks, JVM synchronized → HB) 아래. 락 없는 헤더 읽기는
  SQLite 체크섬 이중-읽기 + xShmBarrier 가 보호하며, 펜스 체인이 실재함을 바이트코드로 확인:
  `__atomic_thread_fence(SEQ_CST)` → wasm `atomic.fence` → Chicory → **`VarHandle.fullFence()`**.
  stale 읽기는 "조금 오래된 스냅샷" = WAL 정상 동작.
- 변경: SqliteWal 의 RRWL 제거 — writer 직렬화는 "writer 워커 1 + 전용 큐"가 구조적으로 보장, read 는 write 와 겹침.
- 실측: 부하 테스트(6w×200 + 8r×400, 진짜 겹침) 추가. 1차 19/20 — 실패 1회는 **rc=15(SQLITE_PROTOCOL)** =
  wal-index 가동 중 WAL 락 프로토콜 내부 재시도(~100회) 고갈. 손상 아님(트랜잭션 시작 전 실패, 재시도 안전).
  증폭 요인: 내부 재시도 sleep(poll_oneoff)이 synchronized(wasi) 를 잡고 자서 타 워커 I/O 까지 직렬화(콘보이).
  → rc=15 를 BUSY 와 같은 재시도 집합에 추가 후 **20/20 + 전체 스위트 그린**.
- ~~남은 개선 후보: poll_oneoff 를 wasi 락에서 면제~~ → **실측 후 기각** (2026-06-10): 이득 0 (sleep 은 정상상태
  희귀 경로라 평균에 안 보임). 당시 rc=26 1/5 도 관측됐으나 **이후 전역 락에서도 1회 발생** — 면제 탓이 아닐
  가능성 높음(아래 유령 항목). 어쨌든 이득이 없어 전체 직렬화 유지.

**[해소 — rc=26(NOTADB) 유령: 함정 3 의 마지막 두 서식지]** RW-락 실험이 증폭기가 되어 원인 확정 (2026-06-10):
- **(b) 우리 쪽**: vfs import 의 `cstr`(=readCString, 벌크) + `ShmArena.map` 의 `fill`(벌크) — **wasi 락 밖**에서
  매 lock/shm_lock 마다 실행되어, chicory-wasi fd I/O 의 벌크 마샬링과 같은 64KB 페이지에서 position 레이스.
  ← 전역 락에서도 ≤2% 발생했던 이유. **단건 absolute 루프로 수정 완료.**
- **(a) chicory-wasi 내부**: fd_read/fd_write 가 결과를 **벌크 Memory.write(position 기반)** 로 마샬링 —
  RW-락으로 데이터플레인을 동시화하면 커넥션들의 버퍼가 페이지 충돌 (RW-락 + (b)수정 후에도 rc=26 즉시 재현
  으로 확정). 외부에서 수정 불가 → **전역 배타(wasiLock.write) 원복.** random_get/environ_get 도 벌크 결과
  쓰기라 무락 금지(일괄 배타).
- 검증: 전역 배타 + (b) 수정으로 6/6 + 전체 스위트 그린. `Rc26ReproTest`(@Disabled) 보존.

**[해소 — `StatelessBulkMemory` 로 함정 3 근원 수술 + WASI RW-락 복원]** (2026-06-10)
- `StatelessBulkMemory`: ByteBufferMemory 위임 래퍼. 벌크 write/readBytes/fill + 벌크 경유 default
  (2-인자 write, read/writeString, read/writeCString)를 **단건 absolute(writeLong/readLong/writeByte) 합성**으로
  오버라이드 — 페이지 position 불변. 단건/atomic/wait-notify/grow/copy(arraycopy)/init 은 inner 위임.
  주의: Kotlin 위임은 default 메서드도 inner 로 포워딩 — 벌크 경유 default 전부 가로채야 함.
- 효과: chicory-wasi fd I/O 벌크 마샬링이 동시 실행돼도 안전 → **RW-락 복원** (path_open/fd_close/fd_renumber 만
  배타, 데이터플레인 readLock, clock/random 등 무락). 이전 A/B 에서 즉시 깨지던 조합이 **15/15 + 전체 그린.**
  AOT 머신도 Memory 인터페이스 경유 확인(캐스팅 CCE 없음).
- **정직한 수치**: IO-헤비 1.76~1.87s — 전역 배타(1.73~1.86s) 대비 **이득 ~0.** 이 워크로드에선 OS 파일 캐시
  덕에 pread 가 ~µs 라 wasi 락이 병목이 아니었음(지배 비용 = wasm 실행). RW-락의 가치는 처리량이 아니라
  (1) 콘보이 제거(sleep 이 readLock — I/O 비차단), (2) 진짜 I/O-바운드(콜드캐시/대형 DB) 헤드룸,
  (3) 함정 3 클래스의 근원 제거(SqliteWal 의 byte-loop 우회도 이제 선택사항).
- Chicory 업스트림 이슈 후보 통합: shared memory 에서 bulk accessor 의 position 사용 (함정 3 = rc=1/26 의 공통 근원).

**[2026-06-10 성능 — 진짜 병목 2개 제거: 부하 테스트 5.7s → ~0.3s (~19×, ~14.6k ops/s)]**
- **`PRAGMA synchronous=NORMAL`** (워커 open 시): WAL 공식 권장 조합. FULL 은 커밋마다 fsync —
  1200 INSERT × fsync(수 ms, synchronized(wasi) 통과) ≈ 베이스라인 5.7s 의 대부분. NORMAL 은 checkpoint 에서만
  sync. 정합성 동일, 정전 시 마지막 커밋 유실 가능성만 트레이드.
- **prepared statement LRU 캐시** (워커당, sql→stmt, 상한 64): 반복 SQL 의 파싱/플래닝 제거. prepare 1회 후
  reset+step 재사용(finalize 대신 reset). 스키마 변경은 prepare_v2 내부 재컴파일이 흡수. 다중 문장(';')은
  sqlite3_exec 폴백. **상한 필수** — 바인딩 API 가 없어 값 보간 시 SQL 이 전부 고유 → 무제한이면 stmt 가
  wasm 메모리에 무한 누적. 초과 시 LRU finalize.
- 검증: 부하(6w×200+8r×400, reader-during-write) 0.26~0.37s × 12/12 + 전체 스위트 그린.

- `JvmVfsRuntime.spawnPthread(body: (JvmVfsModule_ModuleExports) -> Unit): Int` — 위 메커니즘을 런타임에 내장.
  thread-spawn 핸들러가 child 빌드 시 (a) child 의 thread-spawn import 슬롯에 본체 디스패처 바인딩,
  (b) `w.table(0).setRef(0, spawnFuncIdx, w)`. `pthreadCreate(pt, 0, 0, key)` 호출 → 본체 리턴 시
  libc thread-exit → `joinPthread(t)`. **하이브리드 디스패처**: 인자가 등록된 워커 키(작은 정수, 소비 즉시 제거)면
  Java 본체, 미등록 값(= child 내부 pthread_create 의 start_args 힙 포인터)이면 **진짜 spawn 위임** —
  `PRAGMA threads>0` sorter 보조 스레드도 동작(값 공간 분리라 휴리스틱 아님, `delegatedSpawns` 로 관측 가능).
  단 sorter 는 PMA 를 임시 파일로 스필하므로 게스트에 temp 경로 필요 → `SqliteWal.open` 이 `/tmp` preopen
  (hostDir/.tmp) 제공 (없으면 SQLITE_IOERR=10). 검증: 20만 행 + threads=4 + cache_size 축소 + CREATE INDEX →
  위임 ≥1 + integrity ok, 10/10.
- `SqliteWal.open(hostDir, file, readers)` — writer 워커 1(전용 큐) + reader 워커 N(**공유 BlockingQueue**
  — 유휴 워커가 집어가므로 borrow/return 불필요). caller: exec=writeLock / query*=readLock (검증 시맨틱),
  완료는 CompletableFuture(30s watchdog). 종료: STOP 센티널 + pthread_join (검증된 정상 종료).
- **함정 3 (실측, 원인 확정): Chicory 1.7.5 `ByteBufferMemory` 벌크 read/write 는 공유메모리에서 thread-unsafe.**
  증상: 부하 중 간헐 rc=1 "near \"���\": syntax error" (3/12) — cstr 버퍼의 첫 바이트들이 깨짐.
  - **무죄 판명**: dlmalloc — 디스어셈블로 확인한 스핀락(`i32.atomic.rmw.xchg`+`atomic.store`+sched_yield)이
    Chicory 주소별 모니터(`lock(addr)` → `synchronized`) 위에서 상호배제·HB 모두 성립. malloc/free 자체는 건전.
  - **진범**: JVM API 의 벌크 `write(addr, byte[])`/`readBytes`(=`readCString`) 가 공유 64KB 페이지
    ByteBuffer 의 **`position()` 상태를 변경 후 relative put/get**. 두 스레드가 같은 페이지에 동시 벌크 접근
    → position 을 서로 덮어 바이트가 상대 오프셋에 쓰임. (벌크 read 의 position 변경도 동시 write 를 오염)
  - **malloc churn 의 역할 = 배치 증폭기**: 4 워커의 소형 cstr 가 같은 smallbin → **같은 64KB 페이지**에 모여
    동시 벌크 접근 확률을 끌어올림. 고정 버퍼가 효과 있던 1차 이유도 락이 아니라 sqlite warmup 할당이 사이에
    끼며 버퍼들이 다른 페이지로 흩어진 것 — 즉 **레이아웃 운이지 구조 보장이 아니었음**.
  - **안전/위험 구분 (바이트코드 확인)**: 안전 = 단건 writeByte/read(absolute put/get), wasm load/store,
    `memory.copy`/`fill`(System.arraycopy), atomic 연산. 위험 = JVM 벌크 write/readBytes/readCString.
  - **구조적 해소**: 부하 중 JVM 쪽 접근을 **단건 absolute 만** 사용 (sqlPtr=writeByte 루프, cstring=read 루프)
    + 워커당 고정 SQL 버퍼(부하 중 JVM 발 malloc 0). 15/15 + 전체 스위트 그린.
  - 비고: wasm 내부 SQLite(memcpy 629곳 = memory.copy)가 멀쩡했던 이유 = arraycopy 기반 stateless.
    C 채널 풀(§7)이 안정였던 이유도 동일 — 채널 벌크 write 는 페이지가 분리돼 있었고 단건 atomic 위주.
    Chicory 업스트림 이슈 후보 (shared memory 에서 bulk accessor 의 position 사용).

함의: conn_worker.c 재링크 없이도 다중 WAL 워커 가능 — 워커 본체가 Java 라 BUSY 재시도/래치/풀까지 JVM
코드로 표현된다. 단 wasm 재빌드 시 `--export-all` 유지(pthread_create/테이블 export)가 전제. C 채널 워커
(§7)와 동등 성능인지는 미측정(채널 마샬링 vs export 재진입 오버헤드 트레이드오프).

## §11. JDBC 어댑터 — `modules/sqlite-jdbc` (sqlite4j 벤더링 + WorkerDB) [2026-06-11]

§10 절단면 분석의 실행: xerial 포크인 sqlite4j(Apache-2.0, 라이선스 보존 `LICENSE.sqlite4j`)의 main 소스
53파일을 벤더링하고, 그들의 wasm 구현(core/wasm/ 7파일 + WasmDB/WasmDBFactory + ZeroFs 의존)을 우리
워커 아키텍처로 **통째 교체**. 패치는 3파일뿐: SQLiteConnection(팩토리 참조 2곳), SQLiteJDBCLoader
(version()), + 신규 WorkerDB/WorkerDBFactory. 패키지는 업스트림 그대로(org.example.sqlite.jdbc) —
테스트 코퍼스 무수정 이식 + 업스트림 diff 추적용. URL prefix 도 그들 그대로 `jdbc:sqlite:`.

**3층 구조:**
```
modules/sqlite-jdbc  WorkerDB (xerial DB 절단면, Java — package-private abstract 구현 제약)
                     └ 벤더링 jdbc3/jdbc4/core (CoreStatement/ResultSet 가 step/column 단위 호출)
modules/sqlite       WorkerDbPort (공개 포트: raw op 1건 = 워커 태스크 1건)
                     └ WorkerDbRuntimes (파일 canonical 경로별 런타임 refcount 공유 — DbOwnerLock
                       "파일당 런타임 1개" 강제와 양립. :memory: = 글로벌 런타임 1 + 워커별 사유 DB)
                     └ SqliteWorker.Session (기존 코어 그대로: userStmts registry/bindOne/스크래치)
```

**설계 결정 — 포트는 의도적으로 잘다 (step 1회 = 태스크 1건):** JDBC 는 한 커넥션 위에 열린 ResultSet
여러 개가 **교차로 step** 할 수 있어 (스모크의 twoOpenCursors 테스트), 코어스닝을 포트에 넣으면 이
시맨틱이 깨진다. 코어스닝은 우리 네이티브 표면(query→List<SqliteRow>)이 이미 보유 — JDBC 쪽 성능이
필요해지면 벤더링된 CoreResultSet 내부 최적화로(우리가 소스를 소유하므로 가능). rc=15(PROTOCOL)는
포트 step 이 흡수(xerial 이 모르는 코드), BUSY(5)는 busy_timeout 내부 sleep 의 몫이라 그대로 통과.

**같은 파일 = 자동 다중 커넥션 WAL**: JDBC 커넥션들이 같은 파일을 열면 WorkerDbRuntimes 가 런타임을
공유 → 각 커넥션이 그 위의 워커가 됨 (reader 동시 + writer WAL 락 직렬화 — 기존 검증 그대로 적용).

**미지원 (SQLFeatureNotSupported 스텁)**: UDF(create_function/result_*/value_*)/콜레이션/busy_handler/
progress/커밋·업데이트 훅 — 전부 wasm→JVM 함수 포인터가 필요 = **helpers.c 패턴 재링크** (§10 ①,
다음 재링크 1순위). backup/restore/serialize/deserialize 도 보류 (Online Backup API 자체는 export 존재).

**검증**: `JdbcSmokeTest` 5종 — 파일 CRUD+PS+RS(+NULL/wasNull/비ASCII), :memory: 커넥션별 분리,
같은 파일 2커넥션(공유 런타임)+재오픈, 트랜잭션 commit/rollback, **한 커넥션 커서 2개 인터리빙**.
기존 :modules:sqlite 스위트 50개 회귀 그린. 다음: xerial 테스트 코퍼스 38파일 이식.

### §11.1 xerial 테스트 코퍼스 이식 — 그린 (2026-06-11)

29파일(콜백 테스트 8파일 + WasmDBHelper 제외) 무수정 이식 → **386 테스트 0 실패** (skip 19 = 업스트림
자체 skip + 우리 @Disabled 3). 첫 실행 13 실패의 해소 내역:

- **open-flags 구현** (6건): `sqlite3_open_v2` 경로 신설 — Session(openFlags) + spawn/port/Runtimes
  전달. WorkerDB._open 이 SQLiteOpenMode 비트 처리: READONLY → open_v2(1) (워커 PRAGMA 의 WAL 거부는
  무해), CREATE 없음+파일 없음 → "Database file doesn't exists", CREATE+부모 없음 → createDirectories
  (실패 시 "Failed to create db file").
- **rc 보존 예외** (1건): `SqliteNativeException(rc)` 신설 — openDb/Session open 실패가 rc 를 갖고
  전파, WorkerDB.call() 이 cause 체인에서 찾아 `DB.newSQLException(rc)` 로 → "[SQLITE_CANTOPEN]...".
- **spawn 즉시 실패**: 워커 open/warmup 예외를 failure 홀더로 — 30s 워치독 대기 없이 원인 그대로 전파
  (+ 실패 pthread join 정리).
- **SQL 버퍼 성장형** (1건): Session 고정 4096 → 2배 성장 (pp 슬롯 분리). DBMetaData 류 5KB+ SQL 수용.
- **extended result code** (2건): 전역 활성화 대신 WasmDB 와 같은 방식 — port.step/exec 이 오류 시
  **같은 태스크 안에서** sqlite3_extended_errcode 조회 (19 CONSTRAINT → 2067 CONSTRAINT_UNIQUE).
  modules/sqlite 네이티브 표면의 rc 시맨틱은 불변.
- **@Disabled 3건 (의미적 불일치 — 우리가 옳은 케이스)**: TransactionTest.locking/secondConnMustTimeout
  은 rollback journal 전제(open reader 가 writer 를 막음/begin immediate 가 reader 를 막음) — 상시 WAL
  에선 불간섭이 정답. SQLiteJDBCLoaderTest.function 은 UDF (helpers.c 재링크 후).
- **32-동시-DB OOM** (1건): 런타임당 공유 메모리 초기 32MB × 32 — 테스트 힙 4g 로.
- **부수 — DbOwnerLock 사이드카 삭제**: close 시 "락 보유 중 삭제→해제" + acquire 의 **토큰 왕복
  재검증**(락 획득 후 자기 fd 에 난수 기록 → 경로 재오픈 재독 — 불일치/ENOENT = 고아 inode → 재시도)
  으로 삭제/재생성 레이스를 닫음. 사용자 디렉터리에 .jvmlock 잔재 없음 (Windows 는 잔존 가능, 무해).

남은 갭(전부 콜백 = helpers.c 재링크 1건으로 수렴): UDF/콜레이션/busy_handler/progress/커밋·업데이트
훅 + backup/serialize. 코퍼스 중 제외한 8파일이 그대로 재링크 후의 수용 테스트가 된다.

**[2026-06-11 저녁 — 워커 detached 전환: pthread_join 을 JVM Thread.join 으로 대체]**
- 동기: pthread_join 이 main 인스턴스 위에서 블로킹하는 것이 §9.1 불변식 3종("done 래치 후 순간 호출" /
  mainLock 의 join 직렬화 / cancel-중-join 데드락 회피)의 공통 근원이었다.
- 관찰 (제안: 사용자): **워커 VT 의 `Thread.join` 이 pthread_join 과 동등한 보장을 공짜로 준다** —
  VT 는 wasi_thread_start 가 디스패처를 거쳐 완전히 리턴한 뒤에야 죽으므로, "guest exit 경로(dtor 루프/
  detached 자원 해제/tid clear) 완료"가 VT 종료에 포함된다. done 래치(본체 finally)보다도 강한 순서.
- 전제 확인: joinable 에서 join 의 실질 역할 = 스택/TLS 해제뿐 → detached 면 libc 자가 정리.
  wasi-libc detached exit 경로의 `free` 호출(__wasi_thread_start_C 1b1149, detach_state cmpxchg 분기)
  디스어셈블로 확인. 마지막 notify 는 wasi_thread_start asm 이 값 스택만으로 수행(스택 해제 후 안전).
- 장애물: pthread_detach 가 wasm 에 없었음 — sqlite 가 안 써서 링커 GC 로 제거(--export-all 은 살아있는
  심볼만). musl pthread_attr_t 위조(detach@12)는 또 하나의 ABI 의존이라 기각 → **재링크 1호**:
  `wasm-lib/helpers.c` 신설, `__attribute__((used)) void *keepalive[] = { &pthread_detach }` 로 부활.
  (이 파일이 §10 ① 콜백 테이블의 grow 지점 — extern import + ptr-export 패턴 주석으로 명시)
- 구현: spawnPthread(detached=true) → 생성 직후 pthread_detach (mainLock 안). SpawnedWorker.pthread →
  **SpawnedWorker.thread**(VT, 본체가 self 캡처). stop/SqliteWal.close/spawn 실패 경로의 joinPthread →
  thread.join. joinPthread API 는 보존 (스파이크 + 게스트 내부 sorter join 은 원래 무관).
- 부수 효과: 본체 예외가 wasm 프레임을 뚫고 나가도(이론상) joinable 처럼 join 영구 블록으로 안 이어짐 —
  detached 는 자원 누수로 강등 (더 안전한 실패 모드).
- 빌드 출처 정리: shipped wasm 이 구 SDK 산출물이었음이 재빌드에서 드러남(비변경 sqlite3.wasm 도 해시
  상이) → wasi-sdk-33 산출물로 양쪽 통일, 자기 재현성(동일 입력 → 동일 md5) 재검증.
- 검증: :modules:sqlite 50 + :modules:sqlite-jdbc 386 전부 그린 — 코퍼스가 커넥션 수백 회 open/close 라
  detached exit(자가 스택 해제) 처닝 검증을 겸함.

**[2026-06-11 저녁 — 부트스트랩 body 전달을 ScopedValue 2단으로 (workerBodies 키 프로토콜 제거)]**
- 동기 (제안: 사용자 — "VT 당 child 인스턴스가 고정이면 스레드 스코프 상태가 맞지 않나"):
  body 전달의 "맵 + 증가 키 + start_arg 키 왕복 + 값 공간 분리 논증"이 전부 스레드/스코프 구조로 대체 가능.
- 성립 근거: route A(thread-spawn 호스트 호출)는 `pthread_create` export 호출 **안에서 caller 스레드에
  동기로** 발생 → spawnPthread 의 ScopedValue 바인딩이 정확히 route A 까지만 보인다.
- 프로토콜: PENDING_BODY(spawnPthread→route A, caller 스레드) → 평범한 클로저 캡처(route A→runnable)
  → WORKER_BODY(워커 VT, **one-shot** AtomicReference) → route B 가 getAndSet(null) 소비.
  **one-shot 이 핵심**: sorter 의 pthread_create 는 본체 실행 중 = WORKER_BODY 스코프 안에서 디스패처로
  들어오므로 "바인딩됨"만으론 route B 와 구분 불가 — "이 VT 의 첫 진입만 본체"가 정확한 시맨틱.
- 효과: workerBodies/nextWorkerKey 삭제, start_arg 는 ARG_SENTINEL 상수(레이아웃 자가 검증은 유지 —
  arg@12==SENTINEL). "본체는 반드시 그 스레드에서 동기 실행" 불변식이 ScopedValue 의 스레드·스코프
  한정성으로 구조화. 콜백(다음 단계) 디스패치는 이것과 무관하게 **user_data 레지스트리**로 간다 —
  콜레이션이 sorter(손자 VT, 스코프 밖)에서 호출될 수 있어 스레드 스코프는 그쪽엔 틀린 도구 (대화 기록).
- 검증: 전 스위트 50+386 그린 — 위임 0(위조 spawn 의 one-shot 소비)과 sorter 위임 ≥1(스코프 안 진짜
  spawn 의 route A 낙하) 양방향 회귀 포함.

### §11.2 콜백 13종 (UDF/콜레이션/훅) — 코퍼스 422 그린 (2026-06-11 저녁)

§10 ① 의 실행 — **재링크 2호**: helpers.c 에 sqlite4j sqlite3_helpers.c 와 같은 이름의 env import 13종
(xFunc/xStep/xFinal/xValue/xInverse/xDestroy/xCompare/xDestroyCollation/xBusy/xProgress/xCommit/
xRollback/xUpdate) + 주소 취하는 *Ptr() export. LLD 가 import 에 타입드 테이블 슬롯을 등재한다.

**배선 (modules/sqlite):**
- `JvmCallbacks` (런타임당 1): kind 별 레지스트리, 키 = AtomicInteger(1부터 — NULL 회피). 디스패치
  키가 **user_data** 인 이유: 콜레이션은 sorter VT(워커 ScopedValue 스코프 밖)에서도 호출 — 스레드
  무관 통로는 sqlite 가 끼워 주는 user_data 뿐 (대화 분석 그대로).
- `CallbackEnv`: **호출 인스턴스**의 exports/mem 파사드 (value_*/result_*/문자열 디코드, chicory 비노출).
  콜백 안의 value/result 는 wasm step 한가운데서의 **재진입 export 호출** — C 호출 규약상
  __stack_pointer 유효(중첩 C 호출과 동일), sqlite4j 가 같은 방식으로 실증. **큐 제출 금지** (자기
  큐 = 데드락). 인스턴스→Env 는 WeakHashMap 캐시 (워커 churn).
- 등록은 port 워커 태스크 (create_function_v2/window/collation_v2/busy/progress/hook — cbPtrs 슬롯 전달).
  xDestroy/xDestroyCollation 가 sqlite 의 destructor 채널 → 교체/close 시 레지스트리 자동 해제.

**WorkerDB**: UdfAdapter(xerial Function 에 context/value/args 주입 — WasmDB 디스패치 미러), value_* 는
activeEnv.deref(argv+4i) 경유, destroy 는 같은 name+nArgs 로 NULL 재등록(저장한 nArgs 사용 — sqlite4j 의
nArgs=0 고정보다 정확). 훅 키는 _close 가 해제+0 (WasmDBHelper 가시성 계약).

**잡은 함정 2개:**
1. **DB 모니터 데드락** (ListenerTest 4건 hang): caller 가 `DB.execute`(synchronized) 안에서 step 결과를
   기다리는 동안, 워커의 xUpdate/xCommit 콜백이 `onUpdate/onCommit` 의 synchronized(this) 복사에서 대기.
   sqlite4j 는 단일 스레드(콜백 = 호출 스레드, 재진입 모니터)라 안전했던 코드 — 우리는 모니터 보유자 ≠
   콜백 스레드. → 벤더링 패치: 리스너 집합 CopyOnWriteArraySet + 무모니터 순회. **"caller 가 DB 모니터를
   쥔 채 워커를 기다리는 동안 콜백이 그 모니터를 원하면 죽는다"** — 콜백 경로에 DB synchronized 금지가
   우리 아키텍처의 신규 불변식.
2. 훅 키 수명: conn.close 후 키 0 계약(코퍼스 helper 검증) + setHandler(null) = 해제 처리.

**검증**: 코퍼스 8파일 복원(UDF/UDFCustomError/Collation/BusyHandler/Progress/Listener + WasmDBHelper
캐스트 패치) + SQLiteJDBCLoaderTest.function @Disabled 해제 → **422 테스트 0 실패** (skip 21 = 업스트림
자체 + 저널 시맨틱 2). :modules:sqlite 50 그린 (스파이크 자체 배선에 env 스텁 13종 추가).

남은 미지원: backup/restore/serialize/deserialize — **콜백 불필요** (sqlite3_backup_*/serialize 직접
API + JVM 쪽 관찰자), BackupTest/SerializeTest 2파일이 수용 테스트. 그 외 xerial 표면 전부 동작.

### §11.3 backup/restore/serialize — 코퍼스 437 그린, xerial 표면 완전 동등 (2026-06-12)

콜백 불필요 그룹의 구현 + 디버깅 기록. BackupTest 4 + SerializeTest 11 복원 → **코퍼스 437 테스트 0 실패**.

**구현 (포트, 전부 직접 API):**
- backup/restore: 게스트는 preopen 밖을 못 보므로 **guest "/tmp" 중계 + 호스트 복사** (sqlite4j 가
  ZeroFs↔호스트로 하던 것과 동형). :memory: 글로벌 런타임에 "/tmp" preopen 신설 (xerial 시맨틱:
  메모리 커넥션도 파일로 backup 가능해야 함). 루프는 WasmDB 미러 (step(pagesPerStep) → observer →
  BUSY/LOCKED 재시도 nTimeout → finish → 최종 rc = extended errcode).
- **backup dest 는 DELETE 저널로 변환**: 우리 워커가 소스를 WAL 강제 → backup 이 헤더까지 복사해
  dest 도 WAL 플래그 → WAL 이미지는 sqlite3_deserialize 불가. finish 후 PRAGMA journal_mode=DELETE.
- open 시맨틱 정합: backup dest 부모 미존재 = CANTOPEN (mkdir 안 함 — sqlite3_open 동일),
  restore 소스 미존재 = CANTOPEN (생성 금지). prepare 실패도 SqliteNativeException(extended rc)로
  — 손상 DB 의 [SQLITE_NOTADB] 매핑 (testErrorCorrupt).

**함정 4 (신규 — 힙 오염 디버깅): malloc 계열 혼합 금지.**
- 증상: SerializeTest 가 통째로 행 — caller 는 dlmalloc 안에서 RUNNABLE 무한 스핀, 워커는
  sqlite3_finalize→sqlite3Realloc→mem0 뮤텍스 futex 대기. 보유자 없는 유령 락처럼 보임.
- 진단 경로: jcmd Thread.dump_to_file (가상 스레드 포함) → wasm 프레임 func 인덱스를
  wasm-objdump 이름으로 역해석 (func_2761=dlmalloc, func_124=sqlite3Realloc, func_94=pthreadMutexEnter).
- **근원**: deserialize 의 FREEONCLOSE 버퍼를 libc malloc 으로 할당 — sqlite 는 sqlite3_free 로
  해제하는데, 이 빌드의 sqlite3_malloc 은 8바이트 크기 헤더를 앞에 두므로 sqlite3_free(p) 가
  **p-8 을 해제** → dlmalloc 청크 메타데이터 오염 → 이후 free-list 포인터가 정적 데이터(락 워드)까지
  scribble → "보유자 없는 락" + malloc 무한 스핀. serialize(flags=0) 역방향(sqlite3_malloc 버퍼를
  dlfree)도 동일 버그의 거울상. 첫 오염은 testErrorCorrupt 의 {1,2,3} deserialize — 폭발은 한참 뒤
  (전형적 힙 오염 지연 발화).
- **수정 = 경계 규율**: sqlite 에 소유권을 넘기는 버퍼는 sqlite3_malloc64, sqlite 가 준 버퍼는
  sqlite3_free. **불변식: wasm 힙에서 libc malloc/free 와 sqlite3_malloc/free 를 절대 섞지 않는다.**
- 비고: sqlite4j 도 lib.malloc 으로 같은 패턴인데 무사한 이유는 빌드 플래그 차이로 추정
  (HAVE_MALLOC_USABLE_SIZE 면 헤더 없음) — 업스트림 확인 가치. 우리는 export 가 양쪽 다 있으므로
  올바른 짝을 쓰는 것으로 종결.

**검증**: SerializeTest 11(행 → 29s 그린)/BackupTest 4 + 전체 코퍼스 437 + :modules:sqlite 50 그린.
**이로써 xerial DB 추상 표면의 전 메서드가 동작** — 남은 스텁 0.

## §9.2 park-carrier 실행 모델 — 큐 마샬링 제거 (2026-06-12, 제안·1차 구현: 사용자)

워커 실행 모델 교체. **wasm pthread 는 잠든 정체성 캐리어이고, export 호출은 caller 스레드가 직접 한다.**

**핵심 통찰**: wasm pthread 의 정체성(스택/TLS/tid)은 **인스턴스의 globals**(__stack_pointer/__tls_base)
에 있지 JVM 스레드에 있지 않다. park 된 wasi_thread_start 프레임은 정지 상태라, caller 가 push 하는
프레임은 그 아래에 쌓였다 정확히 복원된다 — child 당 직렬화(per-child ReentrantLock)만 지키면
어느 JVM 스레드든 child exports 를 직접 부를 수 있다.

**프로토콜 (SpawnWorkerHandle 상태기계):**
- Phase1 (route A — caller 스레드, pthread_create 안): child **eager 생성** + 트램펄린 패치 + exports
  를 caller 에 회신, VT 예약. **무예외 구간** — libc 가 __tl_lock 보유 중이라 Java 예외가 wasm 을
  관통하면 락 고아 (실패 = 음수 반환 → pthread_create 가 EAGAIN). eager 생성이 안전한 이유:
  인스턴스 생성 = 자기 전용 globals/table 초기화뿐 (공유메모리 passive segment 는 1회 가드).
- Phase2 (route B — 워커 VT, call_indirect slot 0): pthread_detach(self) → childStarted 회신 →
  **shutdown 까지 park 루프** (spurious wakeup 안전 — park 1회는 가짜 기상 = 워커 무작위 사망).
- 진짜 spawn(sorter): 스코프 미바인딩 → lazy 생성 (Phase2 VT 는 park 중이라 spawn 불가,
  caller 실행 중 sqlite 발 spawn 은 바인딩 없음 — 상태기계가 구분 종결).
- **불변식: eager 생성 OK / eager 호출 금지** — wasi_thread_start 전엔 child 의 SP/TLS 가 모듈
  기본값이라 exports 호출이 main 스택을 짓밟는다. 종료 = shutdown set → unpark → **VT join**
  (= libc detached exit 완료 — closeChild).

**SqliteWorker** = ChildModule + Session + per-child 락: `run(fn)` 이 caller 스레드에서 직접 실행.
큐/WorkerTask/future/30s 워치독/STOP 센티널/drainAfterStop 전부 삭제 — spawn 실패도 동기 전파
(래치 워치독 불필요). SqliteWal 은 reader 풀(LinkedBlockingQueue<Worker> 빌림/반납 = 워크스틸링 동치),
JDBC 포트는 submit=run. 콜백은 caller 스레드 재진입 — sqlite4j 와 같은 모니터 시맨틱이 되어
§11.2 의 "콜백 경로 DB synchronized 금지" 제약이 구조적으로 완화 (COW 패치는 그대로 둠).

**전환이 드러낸 숨은 버그 2개** (구 큐 모델에선 워커 VT uncaughtHandler 가 조용히 삼킴):
1. WorkerDB._close 가 훅 키를 sqlite3_close **전에** 해제 — close 의 정당한 롤백 훅(열린 tx)이
   "미등록 key". → 순서 교정: guest close 후 키 해제.
2. close 유발 롤백이 리스너에 전달되고 있었음 — 업스트림 WasmDB._close 는 close 전에 리스너
   집합을 비운다 (close-rollback 은 무전달 시맨틱). → 미러.

**성능 (자연 벤치, 코퍼스)**: SerializeTest(100k INSERT 루프 — fine-grained 핫케이스) 28.8s → **8.4s
(3.4×)**. per-op 마샬링(~µs park/unpark+future)이 락 1회(~ns)로.

**후속 (같은 날, 제안: 사용자) — 핸드셰이크를 future 3개로**: latch/Consumer/Thread/unpark 배관을
`Phase1{exports(@Volatile, route A 동기 회신), started(CF), shutdown(CF), lifetime(CF=runAsync)}` 로 대체.
- Phase2 의 잠들기 = `shutdown.join()` — **CF 는 가짜 완료가 없어 spurious wakeup 면역이 구조화**
  (park 루프/AtomicBoolean/Thread 참조 소멸).
- `lifetime`(VT 수명 future) 이 **워커 VT 예외의 전파 채널**: closeChild 의 get 으로 표면화 +
  whenComplete 로 started 대기자에게도 실패 전달 (이전엔 uncaughtExceptionHandler 옆길).
- closeChild = shutdown.complete → lifetime.get(10s) (= guest exit 완료 보장 동일).

검증: :modules:sqlite 50 + :modules:sqlite-jdbc 437 전부 그린 (SerializeTest 8.4s 유지).
UnixExclSpike 는 park-carrier 표현으로 이식 (@Disabled 보존). 다음 후보: SqliteWal 풀의 차주(빌림)
API 공개 / mainLock 도메인 재검토 (spawn/interrupt 만 잔존).

## §12. 성능 트랙 — SQLITE_DEFAULT_MEMSTATUS=0 (2026-06-12, 후보 ① 첫 항목)

§10 ⑦ 빌드 플래그 후보의 실행. THREADSAFE=1 빌드는 sqlite3_malloc/free 마다 **전 인스턴스 공유
static mem0 mutex**(SQLITE_MUTEX_STATIC_MEM)를 잡아 메모리 통계(used/highwater/soft heap limit)를
갱신한다. park-carrier 에서 reader 워커들은 서로 다른 caller 스레드 위에서 공유 linear memory 로
동시 진입하므로(per-child 락은 child 별), 이 mem0 mutex 가 **진짜 cross-worker 경합점** — §11.3 함정 4
의 행(hang) 스택에도 `sqlite3Realloc → pthreadMutexEnter(mem0)`가 있었다.

- **변경**: build.sh FEATURES 에 `-DSQLITE_DEFAULT_MEMSTATUS=0` 1줄. 양 빌드(non-threads/threads)에
  공통 적용 — non-threads 엔 뮤텍스가 없지만 통계 갱신 산술도 사라져 무손실. 바이너리 검증:
  `strings sqlite3-jvmvfs.wasm | grep MEMSTATUS` → `DEFAULT_MEMSTATUS=0` (sqlite 가 기본값 1 일 때만
  compile_options 에 안 찍는 규약 — 0 이면 노출).
- **무손실 근거**: 우리 코드/JDBC 코퍼스가 memory_used/soft_heap_limit/sqlite3_status/release_memory 를
  전혀 안 씀(grep 0). 통계가 필요하면 init 전에 sqlite3_config(SQLITE_CONFIG_MEMSTATUS,1)로 되살릴 수 있음.
- **실측 (MemstatusBenchTest, @Disabled 보존 — best-of-3, JEP491 JVM)**:
  - bench A (단일 워커 50k INSERT, uncontended mem0 enter/leave): 371ms → **348ms (-6.2%)**.
  - bench B (8 reader 동시 GROUP BY/ORDER BY = sorter/ephemeral malloc 경합): 15861ms → **15243ms (-3.9%)**.
  - 작지만 두 방향 일관. **결론: mem0 mutex 는 핫패스 비용의 작은 일부 — 지배 비용은 여전히 wasm 실행**
    (BUILD.md 성능 가설 재확인). 다음 성능 레버는 실행 엔진 자체 = chicory-redline 트랙(네이티브 코드).
- **회귀**: :modules:sqlite 활성 46 (52−벤치2−스파이크4) + :modules:sqlite-jdbc 437, 실패 0.

## §13. 크래시 복구 테스트 (2026-06-12, 후보 ②)

**크래시-임계 불변식**: wal-index 는 프로세스 사유 linear memory(ShmArena)라 크래시 시 **소멸**하고
-wal 파일만 디스크에 영속(-shm 디스크 파일 없음 — os_jvm 의 xShmMap 은 인메모리 arena). 따라서 비정상
종료 후 새 프로세스가 DB 를 열면 **빈 wal-index 를 디스크 -wal 프레임에서 재구성(표준 walIndexRecover)**
해야 하고, 이 경로가 우리 VFS(파일 I/O = WASI 실파일 위임) 위에서 도는지가 핵심.

`CrashRecoveryTest` 4종 (+ `CrashSubprocessMain` 자식 진입점) — 전부 활성·그린:
- **wal-index 소멸 후 -wal 복구**: autocheckpoint=0 + 500 커밋 → db+wal 디스크 스냅샷(크래시 순간 모사)
  → 새 런타임 오픈 → integrity ok + 500행 + 재쓰기 가능. 스냅샷에 -wal 존재를 assert 해 "체크포인트로
  사라져 복구가 무의미해지는" 거짓 통과를 차단.
- **찢어진 마지막 프레임(파워로스 모사)**: -wal 꼬리 13B 절단 → 체크섬이 불완전 프레임 거부, 직전 유효
  커밋까지 복구 → integrity ok(코럽션 아님) + 첫 커밋 생존 + 재쓰기 가능.
- **stale 소유락 사이드카**: 크래시한 프로세스가 못 지운 `.jvmlock` 잔재 → 재오픈 시 DbOwnerLock 의
  tryLock(죽은 프로세스라 OS 가 락 해제) + 토큰 왕복이 통과해 안 막힘.
- **진짜 kill-9 (end-to-end)**: 자식 JVM(`CrashSubprocessMain`)이 DB 열고(=OS 파일락 보유) 커밋 후
  "READY" → 부모가 `destroyForcibly()`(SIGKILL, shutdown hook/정리 없이 즉사) → 부모가 같은 디렉터리
  재오픈 → **OS 가 자식 파일락을 해제했음 + -wal 복구**를 한 번에 검증 (integrity ok + 300행).
  자식 classpath = `java.class.path`, JVM = `java.home`(JEP491 동일 JDK). 스냅샷이 못 잡는
  "살아있다 죽은 프로세스의 락 해제 + 진짜 프로세스 사유 메모리 소멸"을 커버.

**미커버(의도적)**: 같은 JVM 내 런타임 abandon 은 DbOwnerLock 의 FileChannel 락이
OverlappingFileLockException 으로 잡혀 in-JVM 재오픈 불가 — 진짜 "프로세스 사망"은 파일 스냅샷/자식
프로세스로만 충실히 모사된다(그래서 위 두 경로로 분리). 체크포인트-중-크래시는 별도 미작성(checkpoint
는 멱등 — 재오픈이 -wal 전체로 다시 복구하므로 같은 경로로 수렴, 저위험).
