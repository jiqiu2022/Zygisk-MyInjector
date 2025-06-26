#include "elf_loader.h"

int (*yuuki_test_func) (int, int) = nullptr;

int main(int argc, char* argv[]) {

    if (argc < 2) {
        printf("Usage: %s <so_file_path>\n", argv[0]);
        return 1;
    }

    LOGI("Starting custom linker for: %s", argv[1]);

    // 检查文件是否存在
    if (access(argv[1], F_OK) != 0) {
        LOGE("File does not exist: %s", argv[1]);
        return 1;
    }

    if (access(argv[1], R_OK) != 0) {
        LOGE("File is not readable: %s", argv[1]);
        return 1;
    }

    ElfLoader loader;
    if (loader.LoadLibrary(argv[1])) {
        printf("Successfully loaded %s\n", argv[1]);

        void* test_func = loader.GetSymbol("yuuki_test");
        if (test_func) {
            printf("Found yuuki_test function at %p\n", test_func);
            yuuki_test_func = (int (*)(int, int)) test_func;

            // 测试函数调用
            printf("Testing function call: 1 + 1 = %d\n", yuuki_test_func(1, 1));
            printf("Testing function call: 5 + 3 = %d\n", yuuki_test_func(5, 3));
        } else {
            printf("Failed to find yuuki_test function\n");
        }

        return 0;
    } else {
        printf("Failed to load %s\n", argv[1]);
        return 1;
    }
}
// logcat | grep "CustomLinker"
// logcat | grep "TEST_SO"
// ./data/local/tmp/elf_loader /storage/emulated/0/yuuki/test.so