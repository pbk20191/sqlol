/* JVM 보조 커스텀 VFS.
 * 기본(WASI) VFS 를 복사해 파일 I/O 는 그대로 위임하고, 락(xLock/xUnlock/xCheckReservedLock)과
 * 공유메모리(xShmMap/xShmLock/xShmBarrier/xShmUnmap)만 JVM import 로 빼낸다.
 * → 다중 커넥션 WAL 을 JVM 인메모리 락 + 공유 linear memory 의 wal-index 로 구현.
 *
 * import (module "vfs"): 파일 식별은 파일명 문자열 포인터로 한다(JVM 이 정규화/intern).
 */
#include <string.h>
#include <stdint.h>
#include "sqlite3.h"

/* conn = 커넥션(파일 핸들) 식별자(고유), z = 파일명(같은 파일 묶기). */
#define VFSIMPORT(n) __attribute__((import_module("vfs"), import_name(n)))
VFSIMPORT("lock")           extern int  jvm_lock(int conn, const char *z, int level);
VFSIMPORT("unlock")         extern int  jvm_unlock(int conn, const char *z, int level);
VFSIMPORT("check_reserved") extern int  jvm_check_reserved(int conn, const char *z);
VFSIMPORT("shm_map")        extern int  jvm_shm_map(const char *z, int iRegion, int szRegion, int bExtend);
VFSIMPORT("shm_lock")       extern int  jvm_shm_lock(int conn, const char *z, int offset, int n, int flags);
VFSIMPORT("shm_unmap")      extern int  jvm_shm_unmap(int conn, const char *z, int deleteFlag);

static sqlite3_vfs *gOrig;          /* 위임 대상(기본 WASI VFS) */
static sqlite3_vfs  gJvmVfs;

typedef struct JvmFile {
    sqlite3_file base;              /* 우리 io_methods */
    sqlite3_file *real;             /* 위임 대상 파일 핸들 */
    char zName[1024];               /* 락/shm 키 */
} JvmFile;

#define REAL(f) (((JvmFile *)(f))->real)
#define NAME(f) (((JvmFile *)(f))->zName)
#define CONN(f) ((int)(intptr_t)(f))     /* 커넥션 식별자 = 파일 핸들 포인터 */

static int    jClose(sqlite3_file *f){ int rc=REAL(f)->pMethods->xClose(REAL(f)); sqlite3_free(REAL(f)); return rc; }
static int    jRead(sqlite3_file *f,void *b,int n,sqlite3_int64 o){ return REAL(f)->pMethods->xRead(REAL(f),b,n,o); }
static int    jWrite(sqlite3_file *f,const void *b,int n,sqlite3_int64 o){ return REAL(f)->pMethods->xWrite(REAL(f),b,n,o); }
static int    jTruncate(sqlite3_file *f,sqlite3_int64 sz){ return REAL(f)->pMethods->xTruncate(REAL(f),sz); }
static int    jSync(sqlite3_file *f,int flags){ return REAL(f)->pMethods->xSync(REAL(f),flags); }
static int    jFileSize(sqlite3_file *f,sqlite3_int64 *p){ return REAL(f)->pMethods->xFileSize(REAL(f),p); }
static int    jFileControl(sqlite3_file *f,int op,void *a){ sqlite3_io_methods const*m=REAL(f)->pMethods; return m->xFileControl? m->xFileControl(REAL(f),op,a):SQLITE_NOTFOUND; }
static int    jSectorSize(sqlite3_file *f){ sqlite3_io_methods const*m=REAL(f)->pMethods; return m->xSectorSize? m->xSectorSize(REAL(f)):4096; }
static int    jDeviceChar(sqlite3_file *f){ sqlite3_io_methods const*m=REAL(f)->pMethods; return m->xDeviceCharacteristics? m->xDeviceCharacteristics(REAL(f)):0; }

/* --- 락: JVM 인메모리 --- */
static int    jLock(sqlite3_file *f,int level){ return jvm_lock(CONN(f),NAME(f),level); }
static int    jUnlock(sqlite3_file *f,int level){ return jvm_unlock(CONN(f),NAME(f),level); }
static int    jCheckReserved(sqlite3_file *f,int *pOut){ *pOut=jvm_check_reserved(CONN(f),NAME(f)); return SQLITE_OK; }

/* --- shm: 바이트는 공유 linear memory, 조율은 JVM --- */
static int    jShmMap(sqlite3_file *f,int iRegion,int szRegion,int bExtend,void volatile **pp){
    int ptr=jvm_shm_map(NAME(f),iRegion,szRegion,bExtend);
    *pp=(void *)ptr;
    return ptr ? SQLITE_OK : (bExtend ? SQLITE_IOERR_SHMMAP : SQLITE_OK);
}
static int    jShmLock(sqlite3_file *f,int offset,int n,int flags){ return jvm_shm_lock(CONN(f),NAME(f),offset,n,flags); }
static void   jShmBarrier(sqlite3_file *f){ __atomic_thread_fence(__ATOMIC_SEQ_CST); }
static int    jShmUnmap(sqlite3_file *f,int deleteFlag){ return jvm_shm_unmap(CONN(f),NAME(f),deleteFlag); }

static const sqlite3_io_methods JVM_IO = {
    2,                                   /* iVersion (2 = shm 지원) */
    jClose, jRead, jWrite, jTruncate, jSync, jFileSize,
    jLock, jUnlock, jCheckReserved, jFileControl, jSectorSize, jDeviceChar,
    jShmMap, jShmLock, jShmBarrier, jShmUnmap
};

static int jOpen(sqlite3_vfs *v,const char *zName,sqlite3_file *pFile,int flags,int *pOut){
    JvmFile *p=(JvmFile *)pFile;
    memset(p,0,sizeof(JvmFile));
    p->real=(sqlite3_file *)sqlite3_malloc(gOrig->szOsFile);
    if(!p->real) return SQLITE_NOMEM;
    int rc=gOrig->xOpen(gOrig,zName,p->real,flags,pOut);
    if(rc!=SQLITE_OK){ sqlite3_free(p->real); return rc; }
    if(zName) strncpy(p->zName,zName,sizeof(p->zName)-1);
    p->base.pMethods=&JVM_IO;
    return SQLITE_OK;
}

/* JVM 이 _initialize 후 1회 호출: jvmvfs 를 기본 VFS 로 등록 */
__attribute__((export_name("install_jvm_vfs")))
int install_jvm_vfs(void){
    gOrig=sqlite3_vfs_find(0);
    if(!gOrig) return 1;
    gJvmVfs=*gOrig;                       /* 나머지 vfs 메서드(delete/access/randomness/time…)는 그대로 위임 */
    gJvmVfs.zName="jvmvfs";
    gJvmVfs.szOsFile=sizeof(JvmFile);
    gJvmVfs.pNext=0;
    gJvmVfs.xOpen=jOpen;
    return sqlite3_vfs_register(&gJvmVfs, 1 /*makeDefault*/);
}
