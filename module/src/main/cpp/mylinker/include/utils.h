#pragma once

#include "common.h"

namespace Utils {
    bool safe_add(off64_t* out, off64_t a, size_t b);

    soinfo* get_soinfo(const char* so_name);

    void* getMapData(int fd, off64_t base_offset, size_t elf_offset, size_t size);

    ElfW(Addr) get_export_func(const char* path, const char* func_name);

    inline size_t page_start(size_t addr) {
        return addr & ~(PAGE_SIZE - 1);
    }

    inline size_t page_offset(size_t addr) {
        return addr & (PAGE_SIZE - 1);
    }
}