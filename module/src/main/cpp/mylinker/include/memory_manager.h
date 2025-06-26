#pragma once

#include "common.h"

class MemoryManager {
public:
    MemoryManager();
    ~MemoryManager();

    bool ReserveAddressSpace(const ElfW(Phdr)* phdr_table, size_t phdr_num);

    bool LoadSegments(const ElfW(Phdr)* phdr_table, size_t phdr_num,
                      void* mapped_file, size_t file_size);

    bool FindPhdr(const ElfW(Phdr)* phdr_table, size_t phdr_num);

    bool ProtectSegments(const ElfW(Phdr)* phdr_table, size_t phdr_num);

    void* GetLoadStart() const { return load_start_; }
    size_t GetLoadSize() const { return load_size_; }
    ElfW(Addr) GetLoadBias() const { return load_bias_; }
    const ElfW(Phdr)* GetLoadedPhdr() const { return loaded_phdr_; }

private:
    bool CheckPhdr(ElfW(Addr) loaded, const ElfW(Phdr)* phdr_table, size_t phdr_num);
    size_t phdr_table_get_load_size(const ElfW(Phdr)* phdr_table,
                                    size_t phdr_count,
                                    ElfW(Addr)* min_vaddr);

    void* load_start_;
    size_t load_size_;
    ElfW(Addr) load_bias_;
    const ElfW(Phdr)* loaded_phdr_;
};
