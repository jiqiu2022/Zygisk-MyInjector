#pragma once

#include "common.h"

class ElfReader {
public:
    ElfReader();
    ~ElfReader();

    bool Open(const char* path);
    bool Read();
    void Close();

    const ElfW(Ehdr)* GetHeader() const { return &header_; }
    const ElfW(Phdr)* GetProgramHeaders() const { return phdr_table_; }
    size_t GetProgramHeaderCount() const { return phdr_num_; }

    const char* GetPath() const { return path_.c_str(); }
    int GetFd() const { return fd_; }
    size_t GetFileSize() const { return file_size_; }
    void* GetMappedAddr() const { return mapped_file_; }

private:
    bool ReadElfHeader();
    bool ReadProgramHeaders();
    bool VerifyElfHeader();

    std::string path_;
    int fd_;
    size_t file_size_;
    off64_t file_offset_;

    void* mapped_file_; 

    ElfW(Ehdr) header_;
    ElfW(Phdr)* phdr_table_;
    size_t phdr_num_;
};
