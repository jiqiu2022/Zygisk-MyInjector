#pragma once

#include "common.h"
#include "soinfo_manager.h"

class Relocator {
public:
    Relocator();
    ~Relocator();

    bool RelocateImage(soinfo* si);

    bool LinkImage(soinfo* si);

    uint32_t gnu_hash(const char* name);
    unsigned elf_hash(const char* name);

    ElfW(Sym)* gnu_lookup(uint32_t hash, const char* name, soinfo* si);
    ElfW(Sym)* elf_lookup(unsigned hash, const char* name, soinfo* si);

private:
    bool ProcessRelaRelocation(soinfo* si, const ElfW(Rela)* rela);

    ElfW(Addr) FindSymbolAddress(const char* name, soinfo* si);
};