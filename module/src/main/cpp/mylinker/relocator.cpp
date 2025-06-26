#include "relocator.h"

// Only define if not already defined
#ifndef R_AARCH64_NONE
#define R_AARCH64_NONE            0
#endif
#ifndef R_AARCH64_ABS64
#define R_AARCH64_ABS64         257
#endif
#ifndef R_AARCH64_GLOB_DAT
#define R_AARCH64_GLOB_DAT      1025
#endif
#ifndef R_AARCH64_JUMP_SLOT
#define R_AARCH64_JUMP_SLOT     1026
#endif
#ifndef R_AARCH64_RELATIVE
#define R_AARCH64_RELATIVE      1027
#endif
#ifndef R_AARCH64_TLS_TPREL64
#define R_AARCH64_TLS_TPREL64   1030
#endif
#ifndef R_AARCH64_TLS_DTPREL32
#define R_AARCH64_TLS_DTPREL32  1031
#endif
#ifndef R_AARCH64_IRELATIVE
#define R_AARCH64_IRELATIVE     1032
#endif

Relocator::Relocator() {
}

Relocator::~Relocator() {
}

bool Relocator::RelocateImage(soinfo* si) {
    LOGD("Starting relocation for %s", si->name);

    if (!si) {
        LOGE("soinfo is null");
        return false;
    }

    if (si->rela != nullptr && si->rela_count > 0) {
        LOGD("Processing %zu RELA relocations", si->rela_count);

        if (si->rela_count > 100000) {
            LOGE("RELA count too large: %zu", si->rela_count);
            return false;
        }

        for (size_t i = 0; i < si->rela_count; ++i) {
            if (!ProcessRelaRelocation(si, &si->rela[i])) {
                LOGE("Failed to process RELA relocation %zu", i);
                // 继续处理其他重定位，不要因为一个失败就退出
                // return false;
            }
        }
    } else {
        LOGD("No RELA relocations to process");
    }

    if (si->plt_rela != nullptr && si->plt_rela_count > 0) {
        LOGD("Processing %zu PLT RELA relocations", si->plt_rela_count);

        if (si->plt_rela_count > 10000) {
            LOGE("PLT RELA count too large: %zu", si->plt_rela_count);
            return false;
        }

        for (size_t i = 0; i < si->plt_rela_count; ++i) {
            if (!ProcessRelaRelocation(si, &si->plt_rela[i])) {
                LOGE("Failed to process PLT RELA relocation %zu", i);
                // 继续处理其他重定位
                // return false;
            }
        }
    } else {
        LOGD("No PLT RELA relocations to process");
    }

    LOGD("Relocation complete for %s", si->name);
    return true;
}

bool Relocator::LinkImage(soinfo* si) {
    if (!si) {
        LOGE("soinfo is null in LinkImage");
        return false;
    }

    if (!RelocateImage(si)) {
        LOGE("Failed to relocate image");
        return false;
    }

    if (si->init_func != nullptr) {
        LOGD("Calling init function at %p", si->init_func);
        si->init_func();
    }

    if (si->init_array != nullptr && si->init_array_count > 0) {
        LOGD("Calling %zu init_array functions", si->init_array_count);

        if (si->init_array_count > 1000) {
            LOGE("init_array_count too large: %zu", si->init_array_count);
            return false;
        }

        for (size_t i = 0; i < si->init_array_count; ++i) {
            void (*func)() = si->init_array[i];
            if (func != nullptr) {
                LOGD("Calling init_array[%zu] at %p", i, func);
                func();
            }
        }
    }

    return true;
}

bool Relocator::ProcessRelaRelocation(soinfo* si, const ElfW(Rela)* rela) {
    if (!si || !rela) {
        LOGE("Invalid parameters in ProcessRelaRelocation");
        return false;
    }

    ElfW(Addr) reloc = static_cast<ElfW(Addr)>(rela->r_offset + si->load_bias);
    ElfW(Word) type = ELFW(R_TYPE)(rela->r_info);
    ElfW(Word) sym = ELFW(R_SYM)(rela->r_info);

    LOGD("Processing relocation: offset=0x%llx, type=%d, sym=%d, addend=0x%llx",
         (unsigned long long)rela->r_offset, type, sym, (long long)rela->r_addend);

    if (reloc < si->base || reloc >= si->base + si->size) {
        LOGE("Relocation address 0x%llx out of range [0x%llx, 0x%llx)",
             (unsigned long long)reloc, (unsigned long long)si->base, (unsigned long long)(si->base + si->size));
        return false;
    }

    ElfW(Addr) sym_addr = 0;
    const char* sym_name = nullptr;

    if (sym != 0) {
        if (!si->symtab) {
            LOGE("Symbol table is null");
            return false;
        }

        const ElfW(Sym)* s = &si->symtab[sym];

        if (si->strtab && s->st_name != 0) {
            sym_name = si->strtab + s->st_name;
            LOGD("Symbol name: %s", sym_name);
        }

        if (s->st_shndx != SHN_UNDEF) {
            sym_addr = s->st_value + si->load_bias;
            LOGD("Local symbol: addr=0x%llx", (unsigned long long)sym_addr);
        } else if (sym_name) {
            sym_addr = FindSymbolAddress(sym_name, si);
            if (sym_addr == 0) {
                LOGD("Cannot find symbol: %s (may be optional)", sym_name);
            }
        }
    }

    void* page_start = reinterpret_cast<void*>(PAGE_START(reloc));
    size_t page_size = PAGE_SIZE;

    int old_prot = PROT_READ | PROT_WRITE;
    if (mprotect(page_start, page_size, old_prot) != 0) {
        LOGD("mprotect failed for relocation, trying anyway: %s", strerror(errno));
    }

    switch (type) {
        case R_AARCH64_NONE:
            LOGD("R_AARCH64_NONE");
            break;

        case R_AARCH64_ABS64:
            LOGD("R_AARCH64_ABS64: writing 0x%llx to 0x%llx", 
                 (unsigned long long)(sym_addr + rela->r_addend), (unsigned long long)reloc);
            *reinterpret_cast<ElfW(Addr)*>(reloc) = sym_addr + rela->r_addend;
            break;

        case R_AARCH64_GLOB_DAT:
            LOGD("R_AARCH64_GLOB_DAT: writing 0x%llx to 0x%llx", 
                 (unsigned long long)(sym_addr + rela->r_addend), (unsigned long long)reloc);
            *reinterpret_cast<ElfW(Addr)*>(reloc) = sym_addr + rela->r_addend;
            break;

        case R_AARCH64_JUMP_SLOT:
            LOGD("R_AARCH64_JUMP_SLOT: writing 0x%llx to 0x%llx", 
                 (unsigned long long)(sym_addr + rela->r_addend), (unsigned long long)reloc);
            *reinterpret_cast<ElfW(Addr)*>(reloc) = sym_addr + rela->r_addend;
            break;

        case R_AARCH64_RELATIVE:
            LOGD("R_AARCH64_RELATIVE: writing 0x%llx to 0x%llx", 
                 (unsigned long long)(si->load_bias + rela->r_addend), (unsigned long long)reloc);
            *reinterpret_cast<ElfW(Addr)*>(reloc) = si->load_bias + rela->r_addend;
            break;

        case R_AARCH64_IRELATIVE:
        {
            ElfW(Addr) resolver = si->load_bias + rela->r_addend;
            LOGD("R_AARCH64_IRELATIVE: resolver at 0x%llx", (unsigned long long)resolver);

            if (resolver < si->base || resolver >= si->base + si->size) {
                LOGE("Invalid resolver address: 0x%llx", (unsigned long long)resolver);
                return false;
            }

            ElfW(Addr) resolved = ((ElfW(Addr) (*)())resolver)();
            *reinterpret_cast<ElfW(Addr)*>(reloc) = resolved;
            LOGD("R_AARCH64_IRELATIVE: resolved to 0x%llx", (unsigned long long)resolved);
        }
            break;

        default:
            LOGD("Unknown relocation type %d, skipping", type);
            break;
    }

    return true;
}

ElfW(Addr) Relocator::FindSymbolAddress(const char* name, soinfo* si) {
    if (!name || !si) {
        return 0;
    }

    if (si->symtab != nullptr) {
        if (si->gnu_bucket != nullptr) {
            uint32_t hash = gnu_hash(name);
            ElfW(Sym)* sym = gnu_lookup(hash, name, si);
            if (sym != nullptr && sym->st_shndx != SHN_UNDEF) {
                ElfW(Addr) addr = sym->st_value + si->load_bias;
                LOGD("Found symbol %s in current SO at 0x%llx", name, (unsigned long long)addr);
                return addr;
            }
        }

        if (si->bucket != nullptr) {
            unsigned hash = elf_hash(name);
            ElfW(Sym)* sym = elf_lookup(hash, name, si);
            if (sym != nullptr && sym->st_shndx != SHN_UNDEF) {
                ElfW(Addr) addr = sym->st_value + si->load_bias;
                LOGD("Found symbol %s in current SO at 0x%llx", name, (unsigned long long)addr);
                return addr;
            }
        }
    }

    for (const auto& lib : si->needed_libs) {
        void* handle = dlopen(lib.c_str(), RTLD_NOW | RTLD_NOLOAD);
        if (handle != nullptr) {
            void* addr = dlsym(handle, name);
            if (addr != nullptr) {
                LOGD("Found symbol %s in %s at %p", name, lib.c_str(), addr);
                dlclose(handle);
                return reinterpret_cast<ElfW(Addr)>(addr);
            }
            dlclose(handle);
        }
    }

    void* addr = dlsym(RTLD_DEFAULT, name);
    if (addr != nullptr) {
        LOGD("Found symbol %s globally at %p", name, addr);
        return reinterpret_cast<ElfW(Addr)>(addr);
    }

    LOGD("Symbol %s not found", name);
    return 0;
}

ElfW(Sym)* Relocator::gnu_lookup(uint32_t hash, const char* name, soinfo* si) {
    if (!si->gnu_bucket || !si->gnu_chain || !si->symtab || !si->strtab) {
        return nullptr;
    }

    uint32_t h2 = hash >> si->gnu_shift2;

    uint32_t bloom_mask_bits = sizeof(ElfW(Addr)) * 8;
    uint32_t word_num = (hash / bloom_mask_bits) & si->gnu_maskwords;
    ElfW(Addr) bloom_word = si->gnu_bloom_filter[word_num];

    if ((1 & (bloom_word >> (hash % bloom_mask_bits)) &
         (bloom_word >> (h2 % bloom_mask_bits))) == 0) {
        return nullptr;
    }

    uint32_t n = si->gnu_bucket[hash % si->gnu_nbucket];

    if (n == 0) {
        return nullptr;
    }

    do {
        ElfW(Sym)* s = si->symtab + n;
        if (((si->gnu_chain[n] ^ hash) >> 1) == 0 &&
            strcmp(si->strtab + s->st_name, name) == 0) {
            return s;
        }
    } while ((si->gnu_chain[n++] & 1) == 0);

    return nullptr;
}

ElfW(Sym)* Relocator::elf_lookup(unsigned hash, const char* name, soinfo* si) {
    if (!si->bucket || !si->chain || !si->symtab || !si->strtab) {
        return nullptr;
    }

    for (unsigned n = si->bucket[hash % si->nbucket]; n != 0; n = si->chain[n]) {
        ElfW(Sym)* s = si->symtab + n;
        if (s->st_name != 0 && strcmp(si->strtab + s->st_name, name) == 0) {
            return s;
        }
    }
    return nullptr;
}

uint32_t Relocator::gnu_hash(const char* name) {
    uint32_t h = 5381;
    for (const uint8_t* c = reinterpret_cast<const uint8_t*>(name); *c != '\0'; c++) {
        h += (h << 5) + *c;
    }
    return h;
}

unsigned Relocator::elf_hash(const char* name) {
    unsigned h = 0, g;
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(name); *p; p++) {
        h = (h << 4) + *p;
        g = h & 0xf0000000;
        h ^= g;
        h ^= g >> 24;
    }
    return h;
}