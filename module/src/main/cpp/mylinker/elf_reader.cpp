#include "elf_reader.h"
#include <sys/types.h>

ElfReader::ElfReader() : fd_(-1), file_size_(0), file_offset_(0),
                         mapped_file_(nullptr), phdr_table_(nullptr), phdr_num_(0) {
    memset(&header_, 0, sizeof(header_));
}

ElfReader::~ElfReader() {
    Close();
}

bool ElfReader::Open(const char* path) {
    path_ = path;

    struct stat sb;
    fd_ = open(path, O_RDONLY | O_CLOEXEC);
    if (fd_ < 0) {
        LOGE("Cannot open %s: %s", path, strerror(errno));
        return false;
    }

    if (fstat(fd_, &sb) < 0) {
        LOGE("Cannot stat %s: %s", path, strerror(errno));
        close(fd_);
        fd_ = -1;
        return false;
    }

    file_size_ = sb.st_size;

    // 映射整个文件到内存
    mapped_file_ = mmap(nullptr, file_size_, PROT_READ, MAP_PRIVATE, fd_, 0);
    if (mapped_file_ == MAP_FAILED) {
        LOGE("Cannot mmap %s: %s", path, strerror(errno));
        close(fd_);
        fd_ = -1;
        return false;
    }

    return true;
}

bool ElfReader::Read() {
    if (!ReadElfHeader()) {
        return false;
    }

    if (!VerifyElfHeader()) {
        return false;
    }

    if (!ReadProgramHeaders()) {
        return false;
    }

    return true;
}

void ElfReader::Close() {
    if (mapped_file_ != nullptr && mapped_file_ != MAP_FAILED) {
        munmap(mapped_file_, file_size_);
        mapped_file_ = nullptr;
    }

    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }

    if (phdr_table_ != nullptr) {
        free(phdr_table_);
        phdr_table_ = nullptr;
    }
}

bool ElfReader::ReadElfHeader() {
    if (file_size_ < sizeof(ElfW(Ehdr))) {
        LOGE("File too small for ELF header");
        return false;
    }

    memcpy(&header_, mapped_file_, sizeof(header_));
    return true;
}

bool ElfReader::VerifyElfHeader() {
    if (memcmp(header_.e_ident, ELFMAG, SELFMAG) != 0) {
        LOGE("Invalid ELF magic");
        return false;
    }

    if (header_.e_ident[EI_CLASS] != ELFCLASS64) {
        LOGE("Not a 64-bit ELF file");
        return false;
    }

    if (header_.e_machine != EM_AARCH64) {
        LOGE("Not an ARM64 ELF file");
        return false;
    }

    if (header_.e_version != EV_CURRENT) {
        LOGE("Invalid ELF version");
        return false;
    }

    if (header_.e_type != ET_DYN) {
        LOGE("Not a shared object");
        return false;
    }

    LOGD("ELF Header: type=%d, machine=%d, entry=0x%llx, phoff=0x%llx, phnum=%d",
         header_.e_type, header_.e_machine, (unsigned long long)header_.e_entry,
         (unsigned long long)header_.e_phoff, header_.e_phnum);

    return true;
}

bool ElfReader::ReadProgramHeaders() {
    phdr_num_ = header_.e_phnum;

    if (phdr_num_ == 0) {
        LOGE("No program headers");
        return false;
    }

    if (header_.e_phentsize != sizeof(ElfW(Phdr))) {
        LOGE("Invalid program header size");
        return false;
    }

    size_t size = phdr_num_ * sizeof(ElfW(Phdr));

    if (header_.e_phoff + size > file_size_) {
        LOGE("Program headers out of file bounds");
        return false;
    }

    phdr_table_ = static_cast<ElfW(Phdr)*>(malloc(size));
    if (phdr_table_ == nullptr) {
        LOGE("Cannot allocate memory for program headers");
        return false;
    }

    memcpy(phdr_table_, static_cast<char*>(mapped_file_) + header_.e_phoff, size);

    return true;
}
