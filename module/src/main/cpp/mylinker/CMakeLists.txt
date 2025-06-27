cmake_minimum_required(VERSION 3.18.1)
project(ElfLoader)

set(CMAKE_CXX_STANDARD 17)

include_directories(include)

set(SOURCES
        mylinker.cpp
        elf_loader.cpp
        elf_reader.cpp
        memory_manager.cpp
        relocator.cpp
        soinfo_manager.cpp
        utils.cpp
)

find_library(log-lib log)

# Build as static library to be linked into main module
add_library(mylinker STATIC ${SOURCES})

target_link_libraries(mylinker ${log-lib})

# Only build executable for standalone testing
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    add_executable(elf_loader_test main.cpp elf_loader.cpp elf_reader.cpp memory_manager.cpp relocator.cpp soinfo_manager.cpp utils.cpp)
    target_link_libraries(elf_loader_test ${log-lib})
endif()
