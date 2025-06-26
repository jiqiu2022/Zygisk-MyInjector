#include "elf_loader.h"

ElfLoader::ElfLoader() : loaded_si_(nullptr) {
    reader_ = std::make_unique<ElfReader>();
    memory_manager_ = std::make_unique<MemoryManager>();
    soinfo_manager_ = std::make_unique<SoinfoManager>();
    relocator_ = std::make_unique<Relocator>();
}

ElfLoader::~ElfLoader() {
}

bool ElfLoader::LoadLibrary(const char* path) {
    LOGI("Loading library: %s", path);

    if (!reader_->Open(path)) {
        LOGE("Failed to open %s", path);
        return false;
    }

    if (!reader_->Read()) {
        LOGE("Failed to read ELF file");
        return false;
    }

    if (!memory_manager_->ReserveAddressSpace(reader_->GetProgramHeaders(),
                                              reader_->GetProgramHeaderCount())) {
        LOGE("Failed to reserve address space");
        return false;
    }

    if (!memory_manager_->LoadSegments(reader_->GetProgramHeaders(),
                                       reader_->GetProgramHeaderCount(),
                                       reader_->GetMappedAddr(),
                                       reader_->GetFileSize())) {
        LOGE("Failed to load segments");
        return false;
    }

    if (!memory_manager_->FindPhdr(reader_->GetProgramHeaders(),
                                   reader_->GetProgramHeaderCount())) {
        LOGE("Failed to find program headers");
        return false;
    }

    const char* basename = strrchr(path, '/');
    basename = basename ? basename + 1 : path;
    loaded_si_ = soinfo_manager_->GetOrCreateSoinfo(basename);

    if (!soinfo_manager_->UpdateSoinfo(loaded_si_, memory_manager_.get(), reader_.get())) {
        LOGE("Failed to update soinfo");
        return false;
    }

    if (!soinfo_manager_->PrelinkImage(loaded_si_)) {
        LOGE("Failed to prelink image");
        return false;
    }

    if (!memory_manager_->ProtectSegments(reader_->GetProgramHeaders(),
                                          reader_->GetProgramHeaderCount())) {
        LOGE("Failed to protect segments");
        return false;
    }

    if (!relocator_->LinkImage(loaded_si_)) {
        LOGE("Failed to link image");
        return false;
    }

    reader_->Close();

    LOGI("Successfully loaded %s", path);
    return true;
}

void ElfLoader::CallConstructors() {
    if (loaded_si_ == nullptr) {
        return;
    }

    LOGD("Constructors already called during linking");
}

void* ElfLoader::GetSymbol(const char* name) {
    if (loaded_si_ == nullptr) {
        LOGE("loaded_si_ is null");
        return nullptr;
    }

    if (name == nullptr) {
        LOGE("Symbol name is null");
        return nullptr;
    }

    LOGD("Looking for symbol: %s", name);
    LOGD("soinfo state: symtab=%p, strtab=%p, gnu_bucket=%p, bucket=%p",
         loaded_si_->symtab, loaded_si_->strtab, loaded_si_->gnu_bucket, loaded_si_->bucket);

    if (loaded_si_->symtab != nullptr) {
        if (loaded_si_->gnu_bucket != nullptr) {
            LOGD("Trying GNU hash lookup for %s", name);
            uint32_t hash = relocator_->gnu_hash(name);
            LOGD("GNU hash for %s: 0x%x", name, hash);

            ElfW(Sym)* sym = relocator_->gnu_lookup(hash, name, loaded_si_);
            if (sym != nullptr && sym->st_shndx != SHN_UNDEF) {
                ElfW(Addr) addr = sym->st_value + loaded_si_->load_bias;
                LOGD("Found symbol %s via GNU hash: st_value=0x%llx, load_bias=0x%llx, final_addr=0x%llx",
                     name, (unsigned long long)sym->st_value, (unsigned long long)loaded_si_->load_bias, (unsigned long long)addr);

                if (addr >= loaded_si_->base && addr < loaded_si_->base + loaded_si_->size) {
                    return reinterpret_cast<void*>(addr);
                } else {
                    LOGE("Symbol %s address 0x%llx out of range [0x%llx, 0x%llx)",
                         name, (unsigned long long)addr, (unsigned long long)loaded_si_->base, 
                         (unsigned long long)(loaded_si_->base + loaded_si_->size));
                }
            } else {
                LOGD("Symbol %s not found via GNU hash", name);
            }
        }

        if (loaded_si_->bucket != nullptr) {
            LOGD("Trying ELF hash lookup for %s", name);
            unsigned hash = relocator_->elf_hash(name);
            LOGD("ELF hash for %s: 0x%x", name, hash);

            ElfW(Sym)* sym = relocator_->elf_lookup(hash, name, loaded_si_);
            if (sym != nullptr && sym->st_shndx != SHN_UNDEF) {
                ElfW(Addr) addr = sym->st_value + loaded_si_->load_bias;
                LOGD("Found symbol %s via ELF hash: st_value=0x%llx, load_bias=0x%llx, final_addr=0x%llx",
                     name, (unsigned long long)sym->st_value, (unsigned long long)loaded_si_->load_bias, (unsigned long long)addr);

                if (addr >= loaded_si_->base && addr < loaded_si_->base + loaded_si_->size) {
                    return reinterpret_cast<void*>(addr);
                } else {
                    LOGE("Symbol %s address 0x%llx out of range [0x%llx, 0x%llx)",
                         name, (unsigned long long)addr, (unsigned long long)loaded_si_->base, 
                         (unsigned long long)(loaded_si_->base + loaded_si_->size));
                }
            } else {
                LOGD("Symbol %s not found via ELF hash", name);
            }
        }

        if (loaded_si_->gnu_bucket == nullptr && loaded_si_->bucket == nullptr) {
            LOGD("No hash tables available, trying linear search");

            if (loaded_si_->strtab != nullptr) {
                size_t sym_count = 0;
                if (loaded_si_->nchain > 0) {
                    sym_count = loaded_si_->nchain;
                } else {
                    LOGD("Cannot determine symbol table size");
                    return nullptr;
                }

                LOGD("Trying linear search with %zu symbols", sym_count);
                for (size_t i = 0; i < sym_count; ++i) {
                    ElfW(Sym)* sym = &loaded_si_->symtab[i];
                    if (sym->st_name != 0 && sym->st_shndx != SHN_UNDEF) {
                        const char* sym_name = loaded_si_->strtab + sym->st_name;
                        if (strcmp(sym_name, name) == 0) {
                            ElfW(Addr) addr = sym->st_value + loaded_si_->load_bias;
                            LOGD("Found symbol %s via linear search: st_value=0x%llx, load_bias=0x%llx, final_addr=0x%llx",
                                 name, (unsigned long long)sym->st_value, (unsigned long long)loaded_si_->load_bias, (unsigned long long)addr);

                            if (addr >= loaded_si_->base && addr < loaded_si_->base + loaded_si_->size) {
                                return reinterpret_cast<void*>(addr);
                            } else {
                                LOGE("Symbol %s address 0x%llx out of range [0x%llx, 0x%llx)",
                                     name, (unsigned long long)addr, (unsigned long long)loaded_si_->base, (unsigned long long)(loaded_si_->base + loaded_si_->size));
                            }
                        }
                    }
                }
                LOGD("Symbol %s not found via linear search", name);
            }
        }
    } else {
        LOGE("Symbol table is null");
    }

    LOGE("Symbol %s not found in any method", name);
    return nullptr;
}