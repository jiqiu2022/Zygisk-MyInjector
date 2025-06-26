#include "memory_manager.h"

MemoryManager::MemoryManager() : load_start_(nullptr), load_size_(0),
                                 load_bias_(0), loaded_phdr_(nullptr) {
}

MemoryManager::~MemoryManager() {
}

bool MemoryManager::ReserveAddressSpace(const ElfW(Phdr)* phdr_table, size_t phdr_num) {
    ElfW(Addr) min_vaddr;
    load_size_ = phdr_table_get_load_size(phdr_table, phdr_num, &min_vaddr);

    if (load_size_ == 0) {
        LOGE("No loadable segments");
        return false;
    }

    LOGD("Load size: 0x%zx, min_vaddr: 0x%llx", load_size_, (unsigned long long)min_vaddr);

    void* start = mmap(nullptr, load_size_, PROT_NONE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (start == MAP_FAILED) {
        LOGE("Cannot reserve %zu bytes: %s", load_size_, strerror(errno));
        return false;
    }

    load_start_ = start;
    load_bias_ = reinterpret_cast<ElfW(Addr)>(start) - min_vaddr;

    LOGD("Reserved address space at %p, bias: 0x%llx", start, (unsigned long long)load_bias_);

    return true;
}

bool MemoryManager::LoadSegments(const ElfW(Phdr)* phdr_table, size_t phdr_num,
                                 void* mapped_file, size_t file_size) {
    LOGD("Starting LoadSegments: phdr_num=%zu, file_size=%zu", phdr_num, file_size);

    for (size_t i = 0; i < phdr_num; ++i) {
        const ElfW(Phdr)* phdr = &phdr_table[i];

        if (phdr->p_type != PT_LOAD) {
            continue;
        }

        LOGD("Processing LOAD segment %zu: vaddr=0x%llx, memsz=0x%llx, filesz=0x%llx, offset=0x%llx",
             i, (unsigned long long)phdr->p_vaddr, (unsigned long long)phdr->p_memsz, 
             (unsigned long long)phdr->p_filesz, (unsigned long long)phdr->p_offset);

        ElfW(Addr) seg_start = phdr->p_vaddr + load_bias_;
        ElfW(Addr) seg_end = seg_start + phdr->p_memsz;

        ElfW(Addr) seg_page_start = PAGE_START(seg_start);
        ElfW(Addr) seg_page_end = PAGE_END(seg_end);

        ElfW(Addr) seg_file_end = seg_start + phdr->p_filesz;

        ElfW(Addr) file_start = phdr->p_offset;
        ElfW(Addr) file_end = file_start + phdr->p_filesz;

        ElfW(Addr) file_page_start = PAGE_START(file_start);

        if (file_end > file_size) {
            LOGE("Invalid file size: file_end=0x%llx > file_size=0x%zx", (unsigned long long)file_end, file_size);
            return false;
        }

        if (phdr->p_filesz > 0) {
            void* seg_addr = reinterpret_cast<void*>(seg_page_start);
            size_t seg_size = seg_page_end - seg_page_start;

            if (mprotect(seg_addr, seg_size, PROT_READ | PROT_WRITE) < 0) {
                LOGE("Cannot mprotect for loading: %s", strerror(errno));
                return false;
            }

            void* src = static_cast<char*>(mapped_file) + phdr->p_offset;
            void* dst = reinterpret_cast<void*>(seg_start);

            LOGD("Copying segment %zu: src=%p (offset=0x%llx), dst=%p, size=0x%llx",
                 i, src, (unsigned long long)phdr->p_offset, dst, (unsigned long long)phdr->p_filesz);

            if (static_cast<char*>(src) + phdr->p_filesz > static_cast<char*>(mapped_file) + file_size) {
                LOGE("Source copy would exceed file bounds");
                return false;
            }

            if (reinterpret_cast<ElfW(Addr)>(dst) + phdr->p_filesz > seg_page_end) {
                LOGE("Destination copy would exceed segment bounds");
                return false;
            }

            memcpy(dst, src, phdr->p_filesz);

            LOGD("Successfully copied segment %zu", i);
        }

        if (phdr->p_memsz > phdr->p_filesz) {
            ElfW(Addr) bss_start = seg_start + phdr->p_filesz;
            ElfW(Addr) bss_end = seg_start + phdr->p_memsz;
            size_t bss_size = bss_end - bss_start;

            LOGD("Zeroing BSS: start=0x%llx, size=0x%zx", (unsigned long long)bss_start, bss_size);
            memset(reinterpret_cast<void*>(bss_start), 0, bss_size);
        }

        ElfW(Addr) aligned_file_end = PAGE_END(seg_file_end);
        if (seg_page_end > aligned_file_end) {
            size_t zeromap_size = seg_page_end - aligned_file_end;
            void* zeromap = mmap(reinterpret_cast<void*>(aligned_file_end),
                                 zeromap_size,
                                 PROT_READ | PROT_WRITE,  
                                 MAP_FIXED | MAP_ANONYMOUS | MAP_PRIVATE,
                                 -1, 0);
            if (zeromap == MAP_FAILED) {
                LOGE("Cannot zero fill gap: %s", strerror(errno));
                return false;
            }
            LOGD("Zero-filled gap: addr=%p, size=0x%zx", zeromap, zeromap_size);
        }
    }

    LOGD("LoadSegments complete");
    return true;
}

bool MemoryManager::FindPhdr(const ElfW(Phdr)* phdr_table, size_t phdr_num) {
    const ElfW(Phdr)* phdr_limit = phdr_table + phdr_num;

    for (const ElfW(Phdr)* phdr = phdr_table; phdr < phdr_limit; ++phdr) {
        if (phdr->p_type == PT_PHDR) {
            return CheckPhdr(load_bias_ + phdr->p_vaddr, phdr_table, phdr_num);
        }
    }

    for (const ElfW(Phdr)* phdr = phdr_table; phdr < phdr_limit; ++phdr) {
        if (phdr->p_type == PT_LOAD) {
            if (phdr->p_offset == 0) {
                ElfW(Addr) elf_addr = load_bias_ + phdr->p_vaddr;
                const ElfW(Ehdr)* ehdr = reinterpret_cast<const ElfW(Ehdr)*>(elf_addr);
                ElfW(Addr) offset = ehdr->e_phoff;
                return CheckPhdr(reinterpret_cast<ElfW(Addr)>(ehdr) + offset, phdr_table, phdr_num);
            }
            break;
        }
    }

    LOGD("Using original phdr_table as loaded_phdr");
    loaded_phdr_ = phdr_table;
    return true;
}

bool MemoryManager::ProtectSegments(const ElfW(Phdr)* phdr_table, size_t phdr_num) {
    for (size_t i = 0; i < phdr_num; ++i) {
        const ElfW(Phdr)* phdr = &phdr_table[i];

        if (phdr->p_type != PT_LOAD) {
            continue;
        }

        ElfW(Addr) seg_start = phdr->p_vaddr + load_bias_;
        ElfW(Addr) seg_page_start = PAGE_START(seg_start);
        ElfW(Addr) seg_page_end = PAGE_END(seg_start + phdr->p_memsz);

        int prot = PFLAGS_TO_PROT(phdr->p_flags);

        if (mprotect(reinterpret_cast<void*>(seg_page_start),
                     seg_page_end - seg_page_start, prot) < 0) {
            LOGE("Cannot protect segment %zu: %s", i, strerror(errno));
            return false;
        }

        LOGD("Protected segment %zu: 0x%llx-0x%llx, prot: %d",
             i, (unsigned long long)seg_page_start, (unsigned long long)seg_page_end, prot);
    }

    return true;
}

bool MemoryManager::CheckPhdr(ElfW(Addr) loaded, const ElfW(Phdr)* phdr_table, size_t phdr_num) {
    const ElfW(Phdr)* phdr_limit = phdr_table + phdr_num;
    ElfW(Addr) loaded_end = loaded + (phdr_num * sizeof(ElfW(Phdr)));

    for (const ElfW(Phdr)* phdr = phdr_table; phdr < phdr_limit; ++phdr) {
        if (phdr->p_type != PT_LOAD) {
            continue;
        }

        ElfW(Addr) seg_start = phdr->p_vaddr + load_bias_;
        ElfW(Addr) seg_end = phdr->p_filesz + seg_start;

        if (seg_start <= loaded && loaded_end <= seg_end) {
            loaded_phdr_ = reinterpret_cast<const ElfW(Phdr)*>(loaded);
            return true;
        }
    }

    LOGE("Loaded phdr %p not in loadable segment", reinterpret_cast<void*>(loaded));
    return false;
}

size_t MemoryManager::phdr_table_get_load_size(const ElfW(Phdr)* phdr_table,
                                               size_t phdr_count,
                                               ElfW(Addr)* min_vaddr) {
    ElfW(Addr) min_addr = UINTPTR_MAX;
    ElfW(Addr) max_addr = 0;

    bool found_pt_load = false;

    for (size_t i = 0; i < phdr_count; ++i) {
        const ElfW(Phdr)* phdr = &phdr_table[i];

        if (phdr->p_type != PT_LOAD) {
            continue;
        }

        found_pt_load = true;

        if (phdr->p_vaddr < min_addr) {
            min_addr = phdr->p_vaddr;
        }

        if (phdr->p_vaddr + phdr->p_memsz > max_addr) {
            max_addr = phdr->p_vaddr + phdr->p_memsz;
        }
    }

    if (!found_pt_load) {
        return 0;
    }

    min_addr = PAGE_START(min_addr);
    max_addr = PAGE_END(max_addr);

    if (min_vaddr != nullptr) {
        *min_vaddr = min_addr;
    }

    return max_addr - min_addr;
}