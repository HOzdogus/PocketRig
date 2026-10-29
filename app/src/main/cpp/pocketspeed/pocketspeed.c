// PocketRig fast-forward ("speed hack").
//
// Preloaded into the Wine processes of a Bionic container. It makes the monotonic clocks run
// faster by a factor the app sets at run time, and shortens sleeps to match, so the game's
// timers (QueryPerformanceCounter, GetTickCount, timeGetTime…) advance faster.
//
// The app shares the speed through a small file (POCKETRIG_SPEED_FILE) that every process maps:
//   u32 seq        even = stable, odd = being written (seqlock)
//   u32 version
//   f64 scale      1.0 = normal speed
//   i64 base_real  CLOCK_MONOTONIC ns when scale last changed
//   i64 base_virt  the game's clock (ns) at that moment
// game_time(real) = base_virt + (real - base_real) * scale. Every process uses the same numbers,
// so Wine's server and the game always agree on the time, and it never runs backwards.
//
// Wall-clock time (CLOCK_REALTIME, gettimeofday) is left alone so file dates and logins still work.

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

typedef struct {
    volatile uint32_t seq;
    uint32_t version;
    double scale;
    int64_t base_real;
    int64_t base_virt;
} speed_ctl;

static speed_ctl *ctl;
static int (*real_clock_gettime)(clockid_t, struct timespec *);
static int (*real_clock_nanosleep)(clockid_t, int, const struct timespec *, struct timespec *);
static int (*real_nanosleep)(const struct timespec *, struct timespec *);
static int (*real_usleep)(useconds_t);
static int (*real_cond_timedwait)(pthread_cond_t *, pthread_mutex_t *, const struct timespec *);

static void resolve(void) {
    if (!real_clock_gettime) real_clock_gettime = dlsym(RTLD_NEXT, "clock_gettime");
    if (!real_clock_nanosleep) real_clock_nanosleep = dlsym(RTLD_NEXT, "clock_nanosleep");
    if (!real_nanosleep) real_nanosleep = dlsym(RTLD_NEXT, "nanosleep");
    if (!real_usleep) real_usleep = dlsym(RTLD_NEXT, "usleep");
    if (!real_cond_timedwait) real_cond_timedwait = dlsym(RTLD_NEXT, "pthread_cond_timedwait");
}

__attribute__((constructor)) static void pocketspeed_init(void) {
    resolve();
    const char *path = getenv("POCKETRIG_SPEED_FILE");
    if (!path || !*path) return;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    void *m = mmap(NULL, 4096, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (m != MAP_FAILED) ctl = (speed_ctl *) m;
}

static int64_t to_ns(const struct timespec *ts) { return (int64_t) ts->tv_sec * 1000000000LL + ts->tv_nsec; }

static void from_ns(int64_t ns, struct timespec *ts) {
    if (ns < 0) ns = 0;
    ts->tv_sec = (time_t) (ns / 1000000000LL);
    ts->tv_nsec = (long) (ns % 1000000000LL);
}

/** Reads a consistent copy of the speed settings. Returns 0 when running at normal speed. */
static int read_params(double *scale, int64_t *base_real, int64_t *base_virt) {
    if (!ctl) return 0;
    for (int tries = 0; tries < 64; tries++) {
        uint32_t s1 = __atomic_load_n(&ctl->seq, __ATOMIC_ACQUIRE);
        if (s1 & 1u) continue;
        double sc = ctl->scale;
        int64_t br = ctl->base_real, bv = ctl->base_virt;
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (__atomic_load_n(&ctl->seq, __ATOMIC_ACQUIRE) != s1) continue;
        if (!(sc >= 0.25 && sc <= 16.0)) return 0;
        if (sc == 1.0 && br == bv) return 0;
        *scale = sc;
        *base_real = br;
        *base_virt = bv;
        return 1;
    }
    return 0;
}

static int is_scaled_clock(clockid_t c) {
    return c == CLOCK_MONOTONIC || c == CLOCK_MONOTONIC_RAW || c == CLOCK_MONOTONIC_COARSE || c == CLOCK_BOOTTIME;
}

/** How far the game's clock is ahead of the real one, at real monotonic time `real_mono`. */
static int64_t offset_at(int64_t real_mono, double sc, int64_t br, int64_t bv) {
    return (bv - br) + (int64_t) ((double) (real_mono - br) * (sc - 1.0));
}

static int64_t real_mono_now(void) {
    struct timespec t;
    real_clock_gettime(CLOCK_MONOTONIC, &t);
    return to_ns(&t);
}

int clock_gettime(clockid_t clk, struct timespec *ts) {
    if (!real_clock_gettime) resolve();
    int r = real_clock_gettime(clk, ts);
    if (r != 0 || !ts || !is_scaled_clock(clk)) return r;
    double sc;
    int64_t br, bv;
    if (!read_params(&sc, &br, &bv)) return r;
    int64_t mono = clk == CLOCK_MONOTONIC ? to_ns(ts) : real_mono_now();
    from_ns(to_ns(ts) + offset_at(mono, sc, br, bv), ts);
    return 0;
}

/** Real duration for a sleep the game asked for in its (faster) time. */
static void shrink(const struct timespec *in, struct timespec *out, double sc) {
    from_ns((int64_t) ((double) to_ns(in) / sc), out);
}

int nanosleep(const struct timespec *req, struct timespec *rem) {
    if (!real_nanosleep) resolve();
    double sc;
    int64_t br, bv;
    if (!req || !read_params(&sc, &br, &bv)) return real_nanosleep(req, rem);
    struct timespec shorter;
    shrink(req, &shorter, sc);
    int r = real_nanosleep(&shorter, rem);
    if (r != 0 && rem) from_ns((int64_t) ((double) to_ns(rem) * sc), rem);
    return r;
}

int usleep(useconds_t usec) {
    if (!real_usleep) resolve();
    double sc;
    int64_t br, bv;
    if (!read_params(&sc, &br, &bv)) return real_usleep(usec);
    // Sleep directly rather than through usleep, which may itself call nanosleep (scaling twice).
    struct timespec ts;
    from_ns((int64_t) ((double) usec * 1000.0 / sc), &ts);
    return real_nanosleep(&ts, NULL);
}

int clock_nanosleep(clockid_t clk, int flags, const struct timespec *req, struct timespec *rem) {
    if (!real_clock_nanosleep) resolve();
    double sc;
    int64_t br, bv;
    if (!req || !is_scaled_clock(clk) || !read_params(&sc, &br, &bv)) {
        return real_clock_nanosleep(clk, flags, req, rem);
    }
    if (flags & TIMER_ABSTIME) {
        // The deadline is in the game's time; wait for the matching real time instead.
        struct timespec now;
        clock_gettime(clk, &now);
        int64_t left = to_ns(req) - to_ns(&now);
        if (left <= 0) return 0;
        struct timespec rel;
        from_ns((int64_t) ((double) left / sc), &rel);
        return real_clock_nanosleep(clk, 0, &rel, NULL);
    }
    struct timespec shorter;
    shrink(req, &shorter, sc);
    int r = real_clock_nanosleep(clk, flags, &shorter, rem);
    if (r == EINTR && rem) from_ns((int64_t) ((double) to_ns(rem) * sc), rem);
    return r;
}

// Condition variables set to the monotonic clock get absolute deadlines computed from the game's
// clock. Wall-clock deadlines are about 1.7 billion seconds; monotonic ones are uptime, far
// smaller, so the two can be told apart and only monotonic ones are converted back to real time.
#define WALL_CLOCK_THRESHOLD_SEC 1000000000L

int pthread_cond_timedwait(pthread_cond_t *cond, pthread_mutex_t *mutex, const struct timespec *abstime) {
    if (!real_cond_timedwait) resolve();
    double sc;
    int64_t br, bv;
    if (!abstime || abstime->tv_sec >= WALL_CLOCK_THRESHOLD_SEC || !read_params(&sc, &br, &bv)) {
        return real_cond_timedwait(cond, mutex, abstime);
    }
    int64_t real_now = real_mono_now();
    int64_t game_now = real_now + offset_at(real_now, sc, br, bv);
    int64_t left = to_ns(abstime) - game_now;
    struct timespec real_deadline;
    from_ns(real_now + (left > 0 ? (int64_t) ((double) left / sc) : 0), &real_deadline);
    return real_cond_timedwait(cond, mutex, &real_deadline);
}
