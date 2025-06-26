#pragma once

#include "common.h"

// soinfo结构体定义(简化版)
struct soinfo {
    const char* name;
    ElfW(Addr) base;
    size_t size;
    ElfW(Addr) load_bias;

    const ElfW(Phdr)* phdr;
    size_t phnum;

    ElfW(Addr) entry;

    // Dynamic段信息
    ElfW(Dyn)* dynamic;
    size_t dynamic_count;

    // 符号表相关
    const char* strtab;
    ElfW(Sym)* symtab;
    size_t nbucket;
    size_t nchain;
    uint32_t* bucket;
    uint32_t* chain;

    // 重定位相关
    ElfW(Rela)* plt_rela;
    size_t plt_rela_count;
    ElfW(Rela)* rela;
    size_t rela_count;

    // GNU hash
    size_t gnu_nbucket;
    uint32_t* gnu_bucket;
    uint32_t* gnu_chain;
    uint32_t gnu_maskwords;
    uint32_t gnu_shift2;
    ElfW(Addr)* gnu_bloom_filter;

    // 初始化函数
    void (*init_func)();
    void (**init_array)();
    size_t init_array_count;
    void (**fini_array)();
    size_t fini_array_count;

    // 依赖库
    std::vector<std::string> needed_libs;

    uint32_t flags;
};

class SoinfoManager {
public:
    SoinfoManager();
    ~SoinfoManager();

    soinfo* GetOrCreateSoinfo(const char* name);

    bool UpdateSoinfo(soinfo* si, MemoryManager* mm, ElfReader* reader);

    bool PrelinkImage(soinfo* si);

    soinfo* FindSoinfo(const char* name);
    soinfo* GetCurrentSoinfo();

private:
    bool ParseDynamic(soinfo* si);
    void ApplyRelaSections(soinfo* si);

    std::unordered_map<std::string, std::unique_ptr<soinfo>> soinfo_map_;
};
