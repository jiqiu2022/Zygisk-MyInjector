//
// Created by Mac on 2024/11/15.
//
// 给riru修复了内存泄漏的问题

#include "newriruhide.h"

/**
 * Magic to hide from /proc/###/maps, the idea is from Haruue Icymoon (https://github.com/haruue)
 */


extern "C" {
int riru_hide(const char *name) ;
}

#ifdef __LP64__
#define LIB_PATH "/system/lib64/"
#else
#define LIB_PATH "/system/lib/"
#endif

struct hide_struct {
    procmaps_struct *original;
    uintptr_t backup_address;
};

static int get_prot(const procmaps_struct *procstruct) {
    int prot = 0;
    if (procstruct->is_r) {
        prot |= PROT_READ;
    }
    if (procstruct->is_w) {
        prot |= PROT_WRITE;
    }
    if (procstruct->is_x) {
        prot |= PROT_EXEC;
    }
    return prot;
}

#define FAILURE_RETURN(exp, failure_value) ({   \
    __typeof__(exp) _rc;                    \
    _rc = (exp);                            \
    if (_rc == failure_value) {             \
        PLOGE(#exp);                        \
        return 1;                           \
    }                                       \
    _rc; })

static int do_hide(hide_struct *data) {
    auto procstruct = data->original;
    auto start = (uintptr_t) procstruct->addr_start;
    auto end = (uintptr_t) procstruct->addr_end;
    auto length = end - start;
    int prot = get_prot(procstruct);

    // backup
    data->backup_address = (uintptr_t) FAILURE_RETURN(
            mmap(nullptr, length, PROT_READ | PROT_WRITE, MAP_ANONYMOUS | MAP_PRIVATE, -1, 0),
            MAP_FAILED);
    LOGD("%" PRIxPTR"-%" PRIxPTR" %s %ld %s is backup to %" PRIxPTR, start, end, procstruct->perm,
         procstruct->offset,
         procstruct->pathname, data->backup_address);

    if (procstruct->is_r || procstruct->is_x) { // If readable or executable
        LOGD("memcpy -> backup");
        memcpy((void *) data->backup_address, (void *) start, length);

        // Unmap original memory region
        LOGD("munmap original");
        FAILURE_RETURN(munmap((void *) start, length), -1);

        // Remap backup memory to original location
        LOGD("mmap original with backup");
        FAILURE_RETURN(mmap((void *) start, length, prot, MAP_FIXED | MAP_PRIVATE | MAP_ANONYMOUS, -1, 0),
                       MAP_FAILED);
    }

    return 0;
}

int riru_hide(const char *name) {
    procmaps_iterator *maps = pmparser_parse(-1);
    if (maps == nullptr) {
        LOGE("cannot parse the memory map");
        return false;
    }

    char buf[PATH_MAX];
    hide_struct *data = nullptr;
    size_t data_count = 0;
    procmaps_struct *maps_tmp;
    while ((maps_tmp = pmparser_next(maps)) != nullptr) {
        bool matched = false;
#ifdef DEBUG_APP
        matched = strstr(maps_tmp->pathname, "libriru.so");
#endif
        matched = strstr(maps_tmp->pathname, name) != nullptr;

        // Match the memory regions we want to hide
        if (!matched) continue;

        auto start = (uintptr_t) maps_tmp->addr_start;
        auto end = (uintptr_t) maps_tmp->addr_end;
        if (maps_tmp->is_r || maps_tmp->is_x) {  // If memory is readable or executable
            if (data) {
                data = (hide_struct *) realloc(data, sizeof(hide_struct) * (data_count + 1));
            } else {
                data = (hide_struct *) malloc(sizeof(hide_struct));
            }
            data[data_count].original = maps_tmp;
            data_count += 1;
        }
        LOGD("%" PRIxPTR"-%" PRIxPTR" %s %ld %s", start, end, maps_tmp->perm, maps_tmp->offset,
             maps_tmp->pathname);
    }

    for (int i = 0; i < data_count; ++i) {
        do_hide(&data[i]);
    }

    // Free backup memory to avoid leaks
    for (int i = 0; i < data_count; ++i) {
        FAILURE_RETURN(munmap((void *) data[i].backup_address,
                              (uintptr_t) data[i].original->addr_end - (uintptr_t) data[i].original->addr_start), -1);
    }


    if (data) free(data);
    pmparser_free(maps);
    return 0;
}
