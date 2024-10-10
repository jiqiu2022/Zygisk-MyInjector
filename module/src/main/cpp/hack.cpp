//
// Created by Perfare on 2020/7/4.
//

#include "hack.h"
#include "log.h"
#include "xdl.h"
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <sys/system_properties.h>
#include <dlfcn.h>
#include <jni.h>
#include <thread>
#include <sys/mman.h>
#include <linux/unistd.h>
#include <array>
#include <sys/stat.h>
//#include <asm-generic/fcntl.h>
#include <fcntl.h>

void hack_start(const char *game_data_dir) {
    bool load = false;
    LOGI("hack_start %s", game_data_dir);
    // 构建 files 目录路径
    char files_dir[256];
    snprintf(files_dir, sizeof(files_dir), "%s/files", game_data_dir);

    // 检查 files 目录是否存在
    struct stat st = {0};
    if (stat(files_dir, &st) == -1) {
        LOGI("%s directory does not exist, creating...", files_dir);

        // 创建目录并赋予 0755 权限
        if (mkdir(files_dir, 0755) != 0) {
            LOGE("Failed to create directory %s: %s (errno: %d)", files_dir, strerror(errno), errno);
            return;
        } else {
            LOGI("Successfully created directory %s with 0755 permissions", files_dir);
        }
    } else {
        LOGI("Directory %s already exists", files_dir);
    }

    // 构建新文件路径
    char new_so_path[256];
    snprintf(new_so_path, sizeof(new_so_path), "%s/test.so", files_dir);

    // 复制 /sdcard/test.so 到 game_data_dir 并重命名
    const char *src_path = "/data/local/tmp/test.so";
    int src_fd = open(src_path, O_RDONLY);
    if (src_fd < 0) {
        LOGE("Failed to open %s: %s (errno: %d)", src_path, strerror(errno), errno);
        return;
    }

    int dest_fd = open(new_so_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (dest_fd < 0) {
        LOGE("Failed to open %s", new_so_path);
        close(src_fd);
        return;
    }
    // 复制文件内容
    char buffer[4096];
    ssize_t bytes;
    while ((bytes = read(src_fd, buffer, sizeof(buffer))) > 0) {
        if (write(dest_fd, buffer, bytes) != bytes) {
            LOGE("Failed to write to %s", new_so_path);
            close(src_fd);
            close(dest_fd);
            return;
        }
    }

    close(src_fd);
    close(dest_fd);
    if (chmod(new_so_path, 0755) != 0) {
        LOGE("Failed to change permissions on %s: %s (errno: %d)", new_so_path, strerror(errno), errno);
        return;
    } else {
        LOGI("Successfully changed permissions to 755 on %s", new_so_path);
    }
    JavaVM* vm;
    auto libart = dlopen("libart.so", RTLD_NOW);
    auto JNI_GetCreatedJavaVMs = (jint (*)(JavaVM **, jsize, jsize *)) dlsym(libart,
                                                                             "JNI_GetCreatedJavaVMs");
    LOGI("JNI_GetCreatedJavaVMs %p", JNI_GetCreatedJavaVMs);
    JavaVM *vms_buf[1];
    jsize num_vms;
    jint status = JNI_GetCreatedJavaVMs(vms_buf, 1, &num_vms);
    if (status == JNI_OK && num_vms > 0) {
        vm = vms_buf[0];
    } else {
        LOGE("GetCreatedJavaVMs error");
        return ;
    }

    JNIEnv *env = nullptr;
    bool needDetach = false;
    jint getEnvStat = vm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        LOGI("Thread not attached, attaching...");
        if (vm->AttachCurrentThread(&env, NULL) != 0) {
            LOGE("Failed to attach current thread");
            return;
        }
        needDetach = true;
    } else if (getEnvStat == JNI_OK) {
        LOGI("Thread already attached");
    } else if (getEnvStat == JNI_EVERSION) {
        LOGE("JNI version not supported");
        return;
    } else {
        LOGE("Failed to get the environment using GetEnv, error code: %d", getEnvStat);
        return;
    }

    if (env != nullptr) {
        jclass systemClass = env->FindClass("java/lang/System");
        if (systemClass == NULL) {
            LOGE("Failed to find java/lang/System class");
        } else {
            jmethodID loadMethod = env->GetStaticMethodID(systemClass, "load", "(Ljava/lang/String;)V");
            if (loadMethod == NULL) {
                LOGE("Failed to find System.load method");
            } else {
                jstring jLibPath = env->NewStringUTF(new_so_path);
                env->CallStaticVoidMethod(systemClass, loadMethod, jLibPath);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    LOGE("Exception occurred while calling System.load %s",new_so_path);
                    env->ExceptionClear();
                } else {
                    LOGI("Successfully loaded %s using System.load", new_so_path);
                    load = true;
                }
                env->DeleteLocalRef(jLibPath);
            }
            env->DeleteLocalRef(systemClass);
        }
    }

    if (!load) {
        LOGI("Attempting to load %s using dlopen", new_so_path);
        void * handle;
        for (int i = 0; i < 10; i++) {
            handle = dlopen(new_so_path, RTLD_NOW | RTLD_LOCAL);
            if (handle) {
                LOGI("Successfully loaded %s using dlopen", new_so_path);
                load = true;
                void (*JNI_OnLoad)(JavaVM *, void *);
                *(void **) (&JNI_OnLoad) = dlsym(handle, "JNI_OnLoad");
                if (JNI_OnLoad) {
                    LOGI("JNI_OnLoad symbol found, calling JNI_OnLoad.");
                    JNI_OnLoad(vm, NULL);
                } else {
                    LOGE("JNI_OnLoad symbol not found in %s", new_so_path);
                }
                break;
            } else {
                LOGE("Failed to load %s: %s", new_so_path, dlerror());
                sleep(1);
            }
        }
    }
    if (!load) {
        LOGI("Failed to load test.so in thread %d", gettid());
        return;
    }



}

std::string GetLibDir(JavaVM *vms) {
    JNIEnv *env = nullptr;
    vms->AttachCurrentThread(&env, nullptr);
    jclass activity_thread_clz = env->FindClass("android/app/ActivityThread");
    if (activity_thread_clz != nullptr) {
        jmethodID currentApplicationId = env->GetStaticMethodID(activity_thread_clz,
                                                                "currentApplication",
                                                                "()Landroid/app/Application;");
        if (currentApplicationId) {
            jobject application = env->CallStaticObjectMethod(activity_thread_clz,
                                                              currentApplicationId);
            jclass application_clazz = env->GetObjectClass(application);
            if (application_clazz) {
                jmethodID get_application_info = env->GetMethodID(application_clazz,
                                                                  "getApplicationInfo",
                                                                  "()Landroid/content/pm/ApplicationInfo;");
                if (get_application_info) {
                    jobject application_info = env->CallObjectMethod(application,
                                                                     get_application_info);
                    jfieldID native_library_dir_id = env->GetFieldID(
                            env->GetObjectClass(application_info), "nativeLibraryDir",
                            "Ljava/lang/String;");
                    if (native_library_dir_id) {
                        auto native_library_dir_jstring = (jstring) env->GetObjectField(
                                application_info, native_library_dir_id);
                        auto path = env->GetStringUTFChars(native_library_dir_jstring, nullptr);
                        LOGI("lib dir %s", path);
                        std::string lib_dir(path);
                        env->ReleaseStringUTFChars(native_library_dir_jstring, path);
                        return lib_dir;
                    } else {
                        LOGE("nativeLibraryDir not found");
                    }
                } else {
                    LOGE("getApplicationInfo not found");
                }
            } else {
                LOGE("application class not found");
            }
        } else {
            LOGE("currentApplication not found");
        }
    } else {
        LOGE("ActivityThread not found");
    }
    return {};
}

static std::string GetNativeBridgeLibrary() {
    auto value = std::array<char, PROP_VALUE_MAX>();
    __system_property_get("ro.dalvik.vm.native.bridge", value.data());
    return {value.data()};
}

struct NativeBridgeCallbacks {
    uint32_t version;
    void *initialize;

    void *(*loadLibrary)(const char *libpath, int flag);

    void *(*getTrampoline)(void *handle, const char *name, const char *shorty, uint32_t len);

    void *isSupported;
    void *getAppEnv;
    void *isCompatibleWith;
    void *getSignalHandler;
    void *unloadLibrary;
    void *getError;
    void *isPathSupported;
    void *initAnonymousNamespace;
    void *createNamespace;
    void *linkNamespaces;

    void *(*loadLibraryExt)(const char *libpath, int flag, void *ns);
};

bool NativeBridgeLoad(const char *game_data_dir, int api_level, void *data, size_t length) {
    //TODO 等待houdini初始化
    sleep(5);

    auto libart = dlopen("libart.so", RTLD_NOW);
    auto JNI_GetCreatedJavaVMs = (jint (*)(JavaVM **, jsize, jsize *)) dlsym(libart,
                                                                             "JNI_GetCreatedJavaVMs");
    LOGI("JNI_GetCreatedJavaVMs %p", JNI_GetCreatedJavaVMs);
    JavaVM *vms_buf[1];
    JavaVM *vms;
    jsize num_vms;
    jint status = JNI_GetCreatedJavaVMs(vms_buf, 1, &num_vms);
    if (status == JNI_OK && num_vms > 0) {
        vms = vms_buf[0];
    } else {
        LOGE("GetCreatedJavaVMs error");
        return false;
    }

    auto lib_dir = GetLibDir(vms);
    if (lib_dir.empty()) {
        LOGE("GetLibDir error");
        return false;
    }
    if (lib_dir.find("/lib/x86") != std::string::npos) {
        LOGI("no need NativeBridge");
        munmap(data, length);
        return false;
    }

    auto nb = dlopen("libhoudini.so", RTLD_NOW);
    if (!nb) {
        auto native_bridge = GetNativeBridgeLibrary();
        LOGI("native bridge: %s", native_bridge.data());
        nb = dlopen(native_bridge.data(), RTLD_NOW);
    }
    if (nb) {
        LOGI("nb %p", nb);
        auto callbacks = (NativeBridgeCallbacks *) dlsym(nb, "NativeBridgeItf");
        if (callbacks) {
            LOGI("NativeBridgeLoadLibrary %p", callbacks->loadLibrary);
            LOGI("NativeBridgeLoadLibraryExt %p", callbacks->loadLibraryExt);
            LOGI("NativeBridgeGetTrampoline %p", callbacks->getTrampoline);
            int fd = syscall(__NR_memfd_create, "anon", MFD_CLOEXEC);
            ftruncate(fd, (off_t) length);
            void *mem = mmap(nullptr, length, PROT_WRITE, MAP_SHARED, fd, 0);
            memcpy(mem, data, length);
            munmap(mem, length);
            munmap(data, length);
            char path[PATH_MAX];
            snprintf(path, PATH_MAX, "/proc/self/fd/%d", fd);
            LOGI("arm path %s", path);

            void *arm_handle;
            if (api_level >= 26) {
                arm_handle = callbacks->loadLibraryExt(path, RTLD_NOW, (void *) 3);
            } else {
                arm_handle = callbacks->loadLibrary(path, RTLD_NOW);
            }
            if (arm_handle) {
                LOGI("arm handle %p", arm_handle);
                auto init = (void (*)(JavaVM *, void *)) callbacks->getTrampoline(arm_handle,
                                                                                  "JNI_OnLoad",
                                                                                  nullptr, 0);
                LOGI("JNI_OnLoad %p", init);
                init(vms, (void *) game_data_dir);
                return true;
            }
            close(fd);
        }
    }
    return false;
}

void hack_prepare(const char *_data_dir, void *data, size_t length) {
    LOGI("hack thread: %d", gettid());
    int api_level = android_get_device_api_level();
    LOGI("api level: %d", api_level);

#if defined(__i386__) || defined(__x86_64__)
    if (!NativeBridgeLoad(_data_dir, api_level, data, length)) {
#endif
        hack_start(_data_dir);
#if defined(__i386__) || defined(__x86_64__)
    }
#endif
}

#if defined(__arm__) || defined(__aarch64__)

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    auto game_data_dir = (const char *) reserved;
    std::thread hack_thread(hack_start, game_data_dir);
    hack_thread.detach();
    return JNI_VERSION_1_6;
}

#endif