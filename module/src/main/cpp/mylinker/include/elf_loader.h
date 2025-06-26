#pragma once

#include "common.h"
#include "elf_reader.h"
#include "memory_manager.h"
#include "soinfo_manager.h"
#include "relocator.h"

class ElfLoader {
public:
    ElfLoader();
    ~ElfLoader();

    bool LoadLibrary(const char* path);

    void CallConstructors();

    void* GetSymbol(const char* name);

private:
    std::unique_ptr<ElfReader> reader_;
    std::unique_ptr<MemoryManager> memory_manager_;
    std::unique_ptr<SoinfoManager> soinfo_manager_;
    std::unique_ptr<Relocator> relocator_;

    soinfo* loaded_si_;
};