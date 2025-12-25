package com.jiqiu.configapp;

import android.content.Context;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConfigManager {
    private static final String TAG = "ConfigManager";

    public static final String MODULE_PATH = "/data/adb/modules/zygisk-myinjector";
    public static final String ENABLED_APPS_FILE = MODULE_PATH + "/enabled_apps.txt";
    public static final String TEST_SO_IN_MODULE = MODULE_PATH + "/test.so";

    private final Context context;
    private final Set<String> enabledApps = new HashSet<>();

    static {
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR | Shell.FLAG_MOUNT_MASTER)
                .setTimeout(30));
    }

    public ConfigManager(Context context) {
        this.context = context.getApplicationContext();
        Shell.getShell();
        reload();
    }

    public boolean isRootAvailable() {
        return Shell.getShell().isRoot();
    }

    public void ensureModuleDirectories() {
        if (!isRootAvailable()) {
            Log.e(TAG, "Root access not available!");
            return;
        }
        Shell.cmd(
                "mkdir -p \"" + MODULE_PATH + "\"",
                "chmod 755 \"" + MODULE_PATH + "\""
        ).exec();
    }

    public void reload() {
        enabledApps.clear();
        if (!isRootAvailable()) {
            return;
        }

        Shell.Result result = Shell.cmd("cat \"" + ENABLED_APPS_FILE + "\" 2>/dev/null").exec();
        if (!result.isSuccess()) {
            return;
        }

        for (String line : result.getOut()) {
            String pkg = line.trim();
            if (pkg.isEmpty() || pkg.startsWith("#")) {
                continue;
            }
            enabledApps.add(pkg);
        }
    }

    public boolean isAppEnabled(String packageName) {
        return enabledApps.contains(packageName);
    }

    public void setAppEnabled(String packageName, boolean enabled) {
        if (!isRootAvailable()) {
            Log.e(TAG, "Root access not available, cannot update config");
            return;
        }

        if (enabled) {
            enabledApps.add(packageName);
        } else {
            enabledApps.remove(packageName);
        }

        saveEnabledApps();

        if (enabled) {
            deployTestSoToApp(packageName);
        } else {
            cleanupTestSoFromApp(packageName);
        }
    }

    private void saveEnabledApps() {
        File tempFile = new File(context.getCacheDir(), "enabled_apps.txt");
        try (FileWriter writer = new FileWriter(tempFile, false)) {
            List<String> pkgs = new ArrayList<>(enabledApps);
            Collections.sort(pkgs);
            for (String pkg : pkgs) {
                writer.write(pkg);
                writer.write('\n');
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write temp enabled apps file", e);
            return;
        }

        Shell.cmd(
                "cp \"" + tempFile.getAbsolutePath() + "\" \"" + ENABLED_APPS_FILE + "\"",
                "chmod 644 \"" + ENABLED_APPS_FILE + "\""
        ).exec();

        //noinspection ResultOfMethodCallIgnored
        tempFile.delete();
    }

    private void deployTestSoToApp(String packageName) {
        if (!isRootAvailable()) {
            return;
        }

        Shell.Result checkSource = Shell.cmd("test -f \"" + TEST_SO_IN_MODULE + "\" && echo 'exists'").exec();
        if (!checkSource.isSuccess() || checkSource.getOut().isEmpty()) {
            Log.e(TAG, "test.so not found in module: " + TEST_SO_IN_MODULE);
            return;
        }

        String filesDir = "/data/data/" + packageName + "/files";
        String destPath = filesDir + "/test.so";

        Shell.cmd("mkdir -p \"" + filesDir + "\"").exec();

        Shell.Result copyResult = Shell.cmd("cp -f \"" + TEST_SO_IN_MODULE + "\" \"" + destPath + "\"").exec();
        if (!copyResult.isSuccess()) {
            Shell.Result catResult = Shell.cmd("cat \"" + TEST_SO_IN_MODULE + "\" > \"" + destPath + "\"").exec();
            if (!catResult.isSuccess()) {
                Log.e(TAG, "Failed to copy test.so: " + String.join("\n", catResult.getErr()));
                return;
            }
        }

        Shell.cmd("chmod 644 \"" + destPath + "\"").exec();

        Shell.Result uidResult = Shell.cmd("stat -c %u /data/data/" + packageName + " 2>/dev/null").exec();
        if (uidResult.isSuccess() && !uidResult.getOut().isEmpty()) {
            String uid = uidResult.getOut().get(0).trim();
            if (!uid.isEmpty()) {
                Shell.cmd("chown " + uid + ":" + uid + " \"" + destPath + "\"").exec();
            }
        }

        // Optional (may fail on some devices)
        Shell.cmd("chcon u:object_r:app_data_file:s0 \"" + destPath + "\" 2>/dev/null").exec();
    }

    private void cleanupTestSoFromApp(String packageName) {
        if (!isRootAvailable()) {
            return;
        }
        String destPath = "/data/data/" + packageName + "/files/test.so";
        Shell.cmd("rm -f \"" + destPath + "\"").exec();
    }
}

