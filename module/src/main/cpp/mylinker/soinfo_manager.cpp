#include "soinfo_manager.h"
#include "utils.h"
#include "memory_manager.h"
#include "elf_reader.h"

SoinfoManager::SoinfoManager() {
}

SoinfoManager::~SoinfoManager() {
}

soinfo* SoinfoManager::GetOrCreateSoinfo(const char* name) {
    auto it = soinfo_map_.find(name);
    if (it != soinfo_map_.end()) {
        return it->second.get();
    }

    auto si = std::make_unique<soinfo>();
    memset(si.get(), 0, sizeof(soinfo));
    si->name = strdup(name);

    soinfo* result = si.get();
    soinfo_map_[name] = std::move(si);

    return result;
}

bool SoinfoManager::UpdateSoinfo(soinfo* si, MemoryManager* mm, ElfReader* reader) {
    if (!si || !mm || !reader) {
        return false;
    }

    si->base = reinterpret_cast<ElfW(Addr)>(mm->GetLoadStart());
    si->size = mm->GetLoadSize();
    si->load_bias = mm->GetLoadBias();

    const ElfW(Phdr)* loaded_phdr = mm->GetLoadedPhdr();
    if (loaded_phdr != nullptr) {
        si->phdr = loaded_phdr;
    } else {
        si->phdr = reader->GetProgramHeaders();
        LOGD("Using original program headers");
    }

    si->phnum = reader->GetProgramHeaderCount();

    const ElfW(Ehdr)* header = reader->GetHeader();
    si->entry = si->load_bias + header->e_entry;

    LOGD("Updated soinfo: base=0x%llx, size=0x%zx, bias=0x%llx, entry=0x%llx, phdr=%p",
         (unsigned long long)si->base, si->size, (unsigned long long)si->load_bias, 
         (unsigned long long)si->entry, si->phdr);

    return true;
}

bool SoinfoManager::PrelinkImage(soinfo* si) {
    LOGD("Starting PrelinkImage for %s", si->name);

    if (!ParseDynamic(si)) {
        LOGE("Failed to parse dynamic section");
        return false;
    }

    if (si->strtab != nullptr && si->dynamic != nullptr) {
        for (ElfW(Dyn)* d = si->dynamic; d->d_tag != DT_NULL; ++d) {
            if (d->d_tag == DT_NEEDED && si->needed_libs.empty()) {
                const char* needed = si->strtab + d->d_un.d_val;
                si->needed_libs.push_back(needed);
                LOGD("Processing deferred DT_NEEDED: %s", needed);
            }
        }
    }

    ApplyRelaSections(si);

    LOGD("PrelinkImage complete for %s", si->name);
    return true;
}

soinfo* SoinfoManager::FindSoinfo(const char* name) {
    auto it = soinfo_map_.find(name);
    if (it != soinfo_map_.end()) {
        return it->second.get();
    }
    return nullptr;
}

soinfo* SoinfoManager::GetCurrentSoinfo() {
    return Utils::get_soinfo("libcustom_linker.so");
}

bool SoinfoManager::ParseDynamic(soinfo* si) {
    if (!si || !si->phdr) {
        LOGE("Invalid soinfo or phdr is null");
        return false;
    }

    LOGD("Starting ParseDynamic: phdr=%p, phnum=%zu", si->phdr, si->phnum);

    const ElfW(Phdr)* phdr_limit = si->phdr + si->phnum;
    bool found_dynamic = false;

    for (const ElfW(Phdr)* phdr = si->phdr; phdr < phdr_limit; ++phdr) {
        if (phdr->p_type == PT_DYNAMIC) {
            si->dynamic = reinterpret_cast<ElfW(Dyn)*>(si->load_bias + phdr->p_vaddr);
            si->dynamic_count = phdr->p_memsz / sizeof(ElfW(Dyn));
            found_dynamic = true;
            LOGD("Found PT_DYNAMIC at vaddr=0x%llx, memsz=0x%llx", 
                 (unsigned long long)phdr->p_vaddr, (unsigned long long)phdr->p_memsz);
            break;
        }
    }

    if (!found_dynamic || !si->dynamic) {
        LOGE("No PT_DYNAMIC segment found");
        return false;
    }

    LOGD("Dynamic section at %p, count=%zu", si->dynamic, si->dynamic_count);

    if (si->dynamic_count == 0 || si->dynamic_count > 1000) {
        LOGE("Invalid dynamic count: %zu", si->dynamic_count);
        return false;
    }

    size_t dyn_count = 0;
    for (ElfW(Dyn)* d = si->dynamic; d->d_tag != DT_NULL && dyn_count < si->dynamic_count; ++d, ++dyn_count) {

        LOGD("Processing dynamic entry %zu: tag=0x%llx, val/ptr=0x%llx",
             dyn_count, (unsigned long long)d->d_tag, (unsigned long long)d->d_un.d_val);

        switch (d->d_tag) {
            case DT_SYMTAB:
                si->symtab = reinterpret_cast<ElfW(Sym)*>(si->load_bias + d->d_un.d_ptr);
                LOGD("DT_SYMTAB: raw_ptr=0x%llx, final_addr=%p",
                     (unsigned long long)d->d_un.d_ptr, si->symtab);
                break;

            case DT_STRTAB:
                si->strtab = reinterpret_cast<const char*>(si->load_bias + d->d_un.d_ptr);
                LOGD("DT_STRTAB: raw_ptr=0x%llx, final_addr=%p",
                     (unsigned long long)d->d_un.d_ptr, si->strtab);
                break;

            case DT_STRSZ:
                LOGD("DT_STRSZ: %lu", (unsigned long)d->d_un.d_val);
                break;

            case DT_HASH: {
                uint32_t* hash = reinterpret_cast<uint32_t*>(si->load_bias + d->d_un.d_ptr);
                si->nbucket = hash[0];
                si->nchain = hash[1];
                si->bucket = hash + 2;
                si->chain = si->bucket + si->nbucket;
                LOGD("DT_HASH: raw_ptr=0x%llx, nbucket=%zu, nchain=%zu",
                     (unsigned long long)d->d_un.d_ptr, si->nbucket, si->nchain);
                break;
            }

            case DT_GNU_HASH: {
                uint32_t* hash = reinterpret_cast<uint32_t*>(si->load_bias + d->d_un.d_ptr);
                si->gnu_nbucket = hash[0];
                uint32_t symbias = hash[1];
                si->gnu_maskwords = hash[2];
                si->gnu_shift2 = hash[3];
                si->gnu_bloom_filter = reinterpret_cast<ElfW(Addr)*>(hash + 4);
                si->gnu_bucket = reinterpret_cast<uint32_t*>(si->gnu_bloom_filter + si->gnu_maskwords);
                si->gnu_chain = si->gnu_bucket + si->gnu_nbucket - symbias;
                LOGD("DT_GNU_HASH: raw_ptr=0x%llx, nbucket=%zu, symbias=%u",
                     (unsigned long long)d->d_un.d_ptr, si->gnu_nbucket, symbias);
                break;
            }

            case DT_JMPREL:
                si->plt_rela = reinterpret_cast<ElfW(Rela)*>(si->load_bias + d->d_un.d_ptr);
                LOGD("DT_JMPREL: raw_ptr=0x%llx, final_addr=%p",
                     (unsigned long long)d->d_un.d_ptr, si->plt_rela);
                break;

            case DT_PLTRELSZ:
                si->plt_rela_count = d->d_un.d_val / sizeof(ElfW(Rela));
                LOGD("DT_PLTRELSZ: raw_val=%lu, count=%zu",
                     (unsigned long)d->d_un.d_val, si->plt_rela_count);
                break;

            case DT_PLTREL:
                LOGD("DT_PLTREL: %lu", (unsigned long)d->d_un.d_val);
                break;

            case DT_RELA:
                si->rela = reinterpret_cast<ElfW(Rela)*>(si->load_bias + d->d_un.d_ptr);
                LOGD("DT_RELA: raw_ptr=0x%llx, final_addr=%p",
                     (unsigned long long)d->d_un.d_ptr, si->rela);
                break;

            case DT_RELASZ:
                si->rela_count = d->d_un.d_val / sizeof(ElfW(Rela));
                LOGD("DT_RELASZ: raw_val=%lu, count=%zu",
                     (unsigned long)d->d_un.d_val, si->rela_count);
                break;

            case DT_RELAENT:
                LOGD("DT_RELAENT: %lu", (unsigned long)d->d_un.d_val);
                break;

            case DT_INIT:
                si->init_func = reinterpret_cast<void (*)()>(si->load_bias + d->d_un.d_ptr);
                LOGD("DT_INIT: raw_ptr=0x%llx, final_addr=%p",
                     (unsigned long long)d->d_un.d_ptr, si->init_func);
                break;

            case DT_INIT_ARRAY:
                si->init_array = reinterpret_cast<void (**)()>(si->load_bias + d->d_un.d_ptr);
                LOGD("DT_INIT_ARRAY: raw_ptr=0x%llx, final_addr=%p",
                     (unsigned long long)d->d_un.d_ptr, si->init_array);
                break;

            case DT_INIT_ARRAYSZ:
                si->init_array_count = d->d_un.d_val / sizeof(void*);
                LOGD("DT_INIT_ARRAYSZ: raw_val=%lu, count=%zu",
                     (unsigned long)d->d_un.d_val, si->init_array_count);
                break;

            case DT_FINI:
                LOGD("DT_FINI: 0x%llx", (unsigned long long)d->d_un.d_ptr);
                break;

            case DT_FINI_ARRAY:
                si->fini_array = reinterpret_cast<void (**)()>(si->load_bias + d->d_un.d_ptr);
                LOGD("DT_FINI_ARRAY: raw_ptr=0x%llx, final_addr=%p",
                     (unsigned long long)d->d_un.d_ptr, si->fini_array);
                break;

            case DT_FINI_ARRAYSZ:
                si->fini_array_count = d->d_un.d_val / sizeof(void*);
                LOGD("DT_FINI_ARRAYSZ: raw_val=%lu, count=%zu",
                     (unsigned long)d->d_un.d_val, si->fini_array_count);
                break;

            case DT_FLAGS:
                si->flags = d->d_un.d_val;
                LOGD("DT_FLAGS: 0x%x", si->flags);
                break;

            case DT_FLAGS_1:
                LOGD("DT_FLAGS_1: 0x%llx", (unsigned long long)d->d_un.d_val);
                break;

            case DT_SONAME:
                LOGD("DT_SONAME: offset=%lu", (unsigned long)d->d_un.d_val);
                break;

            case DT_RUNPATH:
                LOGD("DT_RUNPATH: offset=%lu", (unsigned long)d->d_un.d_val);
                break;

            case DT_NEEDED:
                // 跳过，稍后处理
                LOGD("DT_NEEDED: offset=%lu (deferred)", (unsigned long)d->d_un.d_val);
                break;

            default:
                LOGD("Unknown dynamic tag: 0x%llx, value=0x%llx",
                     (unsigned long long)d->d_tag, (unsigned long long)d->d_un.d_val);
                break;
        }

        // 添加安全检查，防止无限循环
        if (dyn_count > si->dynamic_count) {
            LOGE("Dynamic parsing exceeded expected count");
            break;
        }
    }

    if (si->symtab == nullptr) {
        LOGD("Warning: DT_SYMTAB not found or is null");
    }

    if (si->strtab == nullptr) {
        LOGD("Warning: DT_STRTAB not found or is null");
    }

    if (si->strtab != nullptr) {
        dyn_count = 0;
        for (ElfW(Dyn)* d = si->dynamic; d->d_tag != DT_NULL && dyn_count < si->dynamic_count; ++d, ++dyn_count) {
            if (d->d_tag == DT_NEEDED) {
                if (d->d_un.d_val < 65536) {
                    const char* needed = si->strtab + d->d_un.d_val;
                    if (strlen(needed) > 0 && strlen(needed) < 256) {
                        si->needed_libs.push_back(needed);
                        LOGD("DT_NEEDED: %s", needed);
                    } else {
                        LOGD("DT_NEEDED: invalid string at offset %lu", (unsigned long)d->d_un.d_val);
                    }
                } else {
                    LOGD("DT_NEEDED: offset too large: %lu", (unsigned long)d->d_un.d_val);
                }
            }
        }
    }

    LOGD("Dynamic parsing complete: symtab=%p, strtab=%p, needed_libs=%zu",
         si->symtab, si->strtab, si->needed_libs.size());

    return true;
}

void SoinfoManager::ApplyRelaSections(soinfo* si) {
    LOGD("RELA sections: rela_count=%zu, plt_rela_count=%zu",
         si->rela_count, si->plt_rela_count);
}