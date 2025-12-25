#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <string>
#include <sys/types.h>
#include <unistd.h>
#include <unordered_set>
#include "log.h"
#include "zygisk.hpp"

namespace {
constexpr const char *kEnabledAppsPath = "/data/adb/modules/zygisk-myinjector/enabled_apps.txt";
constexpr const char *kInjectSoName = "test.so";

static inline bool IsSpace(char ch) {
    return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';
}

static inline std::string Trim(std::string s) {
    while (!s.empty() && IsSpace(s.front())) {
        s.erase(s.begin());
    }
    while (!s.empty() && IsSpace(s.back())) {
        s.pop_back();
    }
    return s;
}

static bool ReadFileToString(const char *path, std::string *out) {
    out->clear();
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    char buf[4096];
    for (;;) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n == 0) {
            break;
        }
        if (n < 0) {
            close(fd);
            return false;
        }
        out->append(buf, static_cast<size_t>(n));
    }
    close(fd);
    return true;
}

static std::unordered_set<std::string> ReadEnabledApps() {
    std::unordered_set<std::string> apps;
    std::string content;
    if (!ReadFileToString(kEnabledAppsPath, &content)) {
        return apps;
    }

    size_t start = 0;
    while (start < content.size()) {
        size_t end = content.find('\n', start);
        if (end == std::string::npos) {
            end = content.size();
        }
        std::string line = Trim(content.substr(start, end - start));
        if (!line.empty() && line[0] != '#') {
            apps.insert(line);
        }
        start = end + 1;
    }
    return apps;
}

static std::string BasePackageName(const char *process_name) {
    if (process_name == nullptr) {
        return {};
    }
    std::string name(process_name);
    auto pos = name.find(':');
    if (pos == std::string::npos) {
        return name;
    }
    return name.substr(0, pos);
}
} // namespace
using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

class MyModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
        enabled = false;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        auto process_name = env->GetStringUTFChars(args->nice_name, nullptr);
        auto app_data_dir = env->GetStringUTFChars(args->app_data_dir, nullptr);

        const std::string pkg = BasePackageName(process_name);
        const auto enabledApps = ReadEnabledApps();
        enabled = enabledApps.find(pkg) != enabledApps.end();

        LOGI("preAppSpecialize process=%s pkg=%s enabled=%d", process_name, pkg.c_str(), enabled ? 1 : 0);

        if (enabled) {
            appDataDir = app_data_dir ? app_data_dir : "";
        } else {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }

        env->ReleaseStringUTFChars(args->nice_name, process_name);
        env->ReleaseStringUTFChars(args->app_data_dir, app_data_dir);
    }

    void postAppSpecialize(const AppSpecializeArgs *) override {
        if (!enabled) {
            return;
        }
        if (appDataDir.empty()) {
            LOGE("Injection enabled but appDataDir is empty");
            return;
        }

        std::string soPath = appDataDir + "/files/" + kInjectSoName;
        void *handle = dlopen(soPath.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle != nullptr) {
            LOGI("dlopen success: %s", soPath.c_str());
        } else {
            LOGE("dlopen failed: %s err=%s", soPath.c_str(), dlerror());
        }
    }

private:
    Api *api;
    JNIEnv *env;
    bool enabled;
    std::string appDataDir;
};

REGISTER_ZYGISK_MODULE(MyModule)
