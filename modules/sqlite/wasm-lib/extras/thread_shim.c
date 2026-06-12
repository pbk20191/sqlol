/* wasi-threads (pthread) 동작 검증용 shim.
 * spawn_add(x): 워커 스레드를 만들어 box(=x)에 100을 더하고 join 후 box 반환.
 * 워커가 실제로 실행되고 join 동기화가 되면 x+100 을 반환한다. */
#include <pthread.h>
#include <stdint.h>

static void *worker_fn(void *arg) {
    int *p = (int *)arg;
    *p = *p + 100;
    return (void *)(intptr_t)(*p);
}

__attribute__((export_name("spawn_add")))
int spawn_add(int x) {
    int box = x;
    pthread_t t;
    if (pthread_create(&t, NULL, worker_fn, &box) != 0) return -1;
    void *ret = 0;
    if (pthread_join(t, &ret) != 0) return -2;
    return box;  /* 워커가 돌았으면 x + 100 */
}
