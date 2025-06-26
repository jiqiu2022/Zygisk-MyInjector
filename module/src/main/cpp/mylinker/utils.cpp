#include "utils.h"

namespace Utils {

    bool safe_add(off64_t* out, off64_t a, size_t b) {
        if (a < 0 || __builtin_add_overflow(a, b, out)) {
            return false;
        }
        return true;
    }

    soinfo* get_soinfo(const char* so_name) {
        typedef soinfo* (*FunctionPtr)(ElfW(Addr));

        char line[1024];
        ElfW(Addr) linker_base = 0;
        ElfW(Addr) so_addr = 0;

        FILE* fp = fopen("/proc/self/maps", "r");
        if (!fp) {
            LOGE("Cannot open /proc/self/maps");
            return nullptr;
        }

        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, "linker64") && !linker_base) {
                char* addr = strtok(line, "-");
                linker_base = strtoull(addr, nullptr, 16);
            } else if (strstr(line, so_name) && !so_addr) {
                char* addr = strtok(line, "-");
                so_addr = strtoull(addr, nullptr, 16);
            }

            if (linker_base && so_addr) {
                break;
            }
        }

        fclose(fp);

        if (!linker_base || !so_addr) {
            LOGE("Cannot find addresses");
            return nullptr;
        }

        ElfW(Addr) func_offset = get_export_func("/system/bin/linker64", "find_containing_library");
        if (!func_offset) {
            LOGE("Cannot find find_containing_library");
            return nullptr;
        }

        ElfW(Addr) find_containing_library_addr = linker_base + func_offset;
        FunctionPtr find_containing_library = reinterpret_cast<FunctionPtr>(find_containing_library_addr);

        return find_containing_library(so_addr);
    }

    void* getMapData(int fd, off64_t base_offset, size_t elf_offset, size_t size) {
        off64_t offset;
        if (!safe_add(&offset, base_offset, elf_offset)) {
            return nullptr;
        }

        off64_t page_min = page_start(offset);
        off64_t end_offset;
        if (!safe_add(&end_offset, offset, size)) {
            return nullptr;
        }
        if (!safe_add(&end_offset, end_offset, page_offset(offset))) {
            return nullptr;
        }

        size_t map_size = static_cast<size_t>(end_offset - page_min);

        uint8_t* map_start = static_cast<uint8_t*>(
                mmap(nullptr, map_size, PROT_READ, MAP_PRIVATE, fd, page_min));

        if (map_start == MAP_FAILED) {
            return nullptr;
        }

        return map_start + page_offset(offset);
    }

    ElfW(Addr) get_export_func(const char* path, const char* func_name) {
        struct stat sb;
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            return 0;
        }

        if (fstat(fd, &sb) < 0) {
            close(fd);
            return 0;
        }

        void* base = mmap(nullptr, sb.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (base == MAP_FAILED) {
            close(fd);
            return 0;
        }

        ElfW(Ehdr) header;
        memcpy(&header, base, sizeof(header));

        size_t shdr_size = header.e_shnum * sizeof(ElfW(Shdr));
        ElfW(Shdr)* shdr_table = static_cast<ElfW(Shdr)*>(malloc(shdr_size));
        memcpy(shdr_table, static_cast<char*>(base) + header.e_shoff, shdr_size);

        char* shstrtab = static_cast<char*>(base) + shdr_table[header.e_shstrndx].sh_offset;

        void* symtab = nullptr;
        char* strtab = nullptr;
        uint32_t symtab_size = 0;

        for (size_t i = 0; i < header.e_shnum; ++i) {
            const ElfW(Shdr)* shdr = &shdr_table[i];
            char* section_name = shstrtab + shdr->sh_name;

            if (strcmp(section_name, ".symtab") == 0) {
                symtab = static_cast<char*>(base) + shdr->sh_offset;
                symtab_size = shdr->sh_size;
            }
            if (strcmp(section_name, ".strtab") == 0) {
                strtab = static_cast<char*>(base) + shdr->sh_offset;
            }

            if (strtab && symtab) {
                break;
            }
        }

        ElfW(Addr) result = 0;

        if (symtab && strtab) {
            ElfW(Sym)* sym_table = static_cast<ElfW(Sym)*>(symtab);
            int sym_num = symtab_size / sizeof(ElfW(Sym));

            for (int i = 0; i < sym_num; i++) {
                const ElfW(Sym)* sym = &sym_table[i];
                char* sym_name = strtab + sym->st_name;

                if (strstr(sym_name, func_name)) {
                    result = sym->st_value;
                    break;
                }
            }
        }

        free(shdr_table);
        munmap(base, sb.st_size);
        close(fd);

        return result;
    }

} 