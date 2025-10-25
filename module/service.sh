#!/system/bin/sh
MODDIR=${0%/*}

# 确保路径定义
export PATH=/system/bin:/system/xbin:$PATH

# 定义日志函数
log() {
    echo "[MyInjector] $(date '+%Y-%m-%d %H:%M:%S') $1" >> /data/local/tmp/myinjector_install.log
}

# APK 文件路径
APK_PATH="$MODDIR/configapp.apk"

# 检查 APK 是否存在
if [ ! -f "$APK_PATH" ]; then
    log "APK 文件不存在: $APK_PATH"
    exit 1
fi

# 等待系统完全启动
log "等待系统启动完成"
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done
sleep 5 # 额外等待，确保服务启动完成

# 检查 pm 是否可用
log "检查 pm 命令状态"
while ! pm list packages >/dev/null 2>&1; do
    sleep 1
done

# 检查是否已安装
INSTALLED=$(pm list packages com.jiqiu.configapp 2>/dev/null)
if [ -n "$INSTALLED" ]; then
    log "ConfigApp 已安装，检查版本"
    # 可以在这里添加版本检查逻辑
else
    log "ConfigApp 未安装，开始安装"
fi

# 获取系统版本
SDK_VERSION=$(getprop ro.build.version.sdk)
log "检测到系统版本: SDK $SDK_VERSION"

# 根据系统版本选择安装方法
if [ "$SDK_VERSION" -ge 29 ]; then
    # 高版本 Android（SDK >= 29）
    log "使用高版本安装逻辑"
    {
        INSTALL_SESSION=$(pm install-create -r)
        if [ $? -ne 0 ]; then
            log "创建安装会话失败"
            exit 1
        fi
        log "安装会话创建成功: $INSTALL_SESSION"

        pm install-write "$INSTALL_SESSION" 0 "$APK_PATH"
        if [ $? -ne 0 ]; then
            log "写入 APK 文件失败"
            log "降级，使用低版本安装逻辑"
            pm install -r "$APK_PATH" >> /data/local/tmp/myinjector_install.log 2>&1
            if [ $? -ne 0 ]; then
                log "APK 安装失败"
                exit 1
            fi
            log "APK 安装完成"
            exit 0
        fi
        log "APK 写入成功"

        pm install-commit "$INSTALL_SESSION"
        if [ $? -ne 0 ]; then
            log "提交安装会话失败"
            exit 1
        fi
        log "APK 安装完成"
    } >> /data/local/tmp/myinjector_install.log 2>&1
else
    # 低版本 Android（SDK < 29）
    log "使用低版本安装逻辑"
    pm install -r "$APK_PATH" >> /data/local/tmp/myinjector_install.log 2>&1
    if [ $? -ne 0 ]; then
        log "APK 安装失败"
        exit 1
    fi
    log "APK 安装完成"
fi

# 确保模块目录权限正确
chmod -R 755 /data/adb/modules/zygisk-myinjector
chown -R root:root /data/adb/modules/zygisk-myinjector

log "ConfigApp 安装脚本执行完成"

# ==================== KPM 模块加载 ====================

# KPM 模块路径
KPM_MODULE="$MODDIR/injectHide.kpm"
KPM_CONFIG="/data/local/tmp/kpm_hide_config.txt"

log "开始加载 KPM 内核模块"

# 检查 KPM 模块文件是否存在
if [ ! -f "$KPM_MODULE" ]; then
    log "KPM 模块文件不存在: $KPM_MODULE"
else
    log "找到 KPM 模块文件: $KPM_MODULE"
    
    # 创建初始配置文件（如果不存在）
    if [ ! -f "$KPM_CONFIG" ]; then
        log "创建初始 KPM 配置文件"
        # 确保 /data/local/tmp 目录存在且权限正确
        mkdir -p /data/local/tmp
        chmod 777 /data/local/tmp
        echo "libmyinjector.so" > "$KPM_CONFIG"
        chmod 666 "$KPM_CONFIG"
    fi
    
    # 等待一段时间确保系统稳定
    sleep 3
    
    # 加载 KPM 模块
    log "正在加载 KPM 模块..."
    insmod "$KPM_MODULE" 2>&1 | while read line; do
        log "insmod: $line"
    done
    
    # 检查模块是否加载成功
    if lsmod | grep -q "hideInject"; then
        log "KPM 模块加载成功！"
    else
        log "KPM 模块加载失败，请检查日志"
    fi
fi

# 脚本完成
exit 0