#!/bin/bash
# SQLite 아말감(sqlite3.c/h) → wasm 빌드. autoconf/configure 불필요 (BUILD.md §2).
#
# 산출물 2종:
#   out/sqlite3.wasm        — 단일 커넥션용 (non-threads, THREADSAFE=0) → AOT Sqlite3Module
#   out/sqlite3-jvmvfs.wasm — 다중 커넥션 WAL (wasm32-wasi-threads + os_jvm VFS) → AOT JvmVfsModule
#
# 사용: WASI_SDK=/path/to/wasi-sdk-33+ ./build.sh
# 아말감 위치: ../sqlite-amalgamation (공식 배포본 그대로). 갱신은 https://sqlite.org/download.html 의
#   sqlite-amalgamation-XXXXXXX.zip 을 그 폴더에 풀어 교체.
#
# 주의 (BUILD.md 스냅샷의 불변 전제):
#   - --export-all 필수: pthread_create/__indirect_function_table/TLS globals export 가
#     순수 JVM pthread 부트스트랩(§9)의 기반.
#   - threads 링크 플래그(공유메모리/initial/max)는 JvmVfsRuntime 의 MemoryLimits 와 짝.
set -euxo pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
SDK="${WASI_SDK:-$HOME/Downloads/wasi-sdk-33.0-arm64-macos}"
CLANG="$SDK/bin/clang"
AMALG="$SCRIPT_DIR/../sqlite-amalgamation"
OUT="$SCRIPT_DIR/out"
mkdir -p "$OUT"

# 기능 정책 (BUILD.md §2): deprecated 비활성 + 실험/디버그 제외 + 안정 기능 전부 활성
FEATURES=(
  -DSQLITE_OMIT_DEPRECATED
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
  -DSQLITE_OMIT_LOAD_EXTENSION=1 -DSQLITE_OMIT_SHARED_CACHE
  # 성능 (BUILD.md 후보 ①): 메모리 통계 추적 끄기 → THREADSAFE=1 빌드의 mem0 static mutex 를
  # malloc/free 핫패스에서 제거 (다중 pthread 동시 할당 경합 + uncontended enter/leave 오버헤드 소거).
  # 우리 코드/JDBC 코퍼스가 memory_used/soft_heap_limit/sqlite3_status 를 안 쓰므로 무손실.
  -DSQLITE_DEFAULT_MEMSTATUS=0
)

# ── ① 단일 커넥션용 (non-threads) ─────────────────────────────────────────────
"$CLANG" --target=wasm32-wasip1 -O2 -c "$AMALG/sqlite3.c" -o "$OUT/sqlite3-full.o" \
  -I"$AMALG" -DSQLITE_THREADSAFE=0 "${FEATURES[@]}"
"$CLANG" --target=wasm32-wasip1 -O2 -mexec-model=reactor \
  -o "$OUT/sqlite3.wasm" "$OUT/sqlite3-full.o" \
  -Wl,--export-all,--allow-undefined

# ── ② 다중 커넥션 WAL (threads + os_jvm VFS + helpers keep-alive) ───────────
"$CLANG" --target=wasm32-wasi-threads -pthread -O2 -c "$AMALG/sqlite3.c" -o "$OUT/sqlite3-ts.o" \
  -I"$AMALG" -DSQLITE_THREADSAFE=1 "${FEATURES[@]}"
"$CLANG" --target=wasm32-wasi-threads -pthread -O2 -c "$SCRIPT_DIR/os_jvm.c" -o "$OUT/os_jvm.o" \
  -I"$AMALG"
"$CLANG" --target=wasm32-wasi-threads -pthread -O2 -c "$SCRIPT_DIR/helpers.c" -o "$OUT/helpers.o" \
  -I"$AMALG"
"$CLANG" --target=wasm32-wasi-threads -pthread -mexec-model=reactor \
  -o "$OUT/sqlite3-jvmvfs.wasm" "$OUT/sqlite3-ts.o" "$OUT/os_jvm.o" "$OUT/helpers.o" \
  -Wl,--shared-memory,--import-memory,--export-all,--allow-undefined,--initial-memory=33554432,--max-memory=2147483648

ls -la "$OUT"/*.wasm
echo "다음 단계: out/*.wasm 을 ../chicory-aot/ 로 복사 후 gradle 빌드(AOT 자동 재생성) + 전체 테스트"
