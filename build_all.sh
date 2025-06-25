#!/bin/bash

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 模块信息
MODULE_ID="zygisk-myinjector"
MODULE_VERSION="1.0"
MODULE_VERSION_CODE="100"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Zygisk MyInjector 构建脚本${NC}"
echo -e "${GREEN}========================================${NC}"

# 清理之前的构建
echo -e "\n${YELLOW}[1/5] 清理旧构建文件...${NC}"
rm -rf build/magisk_module
rm -f build/*.zip
mkdir -p build

# 构建 ConfigApp
echo -e "\n${YELLOW}[2/5] 构建 ConfigApp...${NC}"
cd configapp
if ../gradlew assembleDebug; then
    echo -e "${GREEN}✓ ConfigApp 构建成功${NC}"
    cd ..
else
    echo -e "${RED}✗ ConfigApp 构建失败${NC}"
    cd ..
    exit 1
fi

# 构建 Magisk 模块
echo -e "\n${YELLOW}[3/5] 构建 Magisk 模块原生库...${NC}"
cd module
if ../gradlew assembleRelease; then
    echo -e "${GREEN}✓ 模块原生库构建成功${NC}"
    cd ..
else
    echo -e "${RED}✗ 模块原生库构建失败${NC}"
    cd ..
    exit 1
fi

# 准备打包
echo -e "\n${YELLOW}[4/5] 准备打包文件...${NC}"

# 创建临时目录
TEMP_DIR="build/magisk_module"
mkdir -p $TEMP_DIR

# 创建 module.prop
cat > $TEMP_DIR/module.prop << EOF
id=$MODULE_ID
name=Zygisk MyInjector
version=v$MODULE_VERSION
versionCode=$MODULE_VERSION_CODE
author=jiqiu
description=A Zygisk module for dynamic library injection with ConfigApp
EOF
echo -e "  ${GREEN}✓ 创建 module.prop${NC}"

# 复制 service.sh
if [ -f "module/service.sh" ]; then
    cp module/service.sh $TEMP_DIR/
    chmod 755 $TEMP_DIR/service.sh
    echo -e "  ${GREEN}✓ 复制 service.sh${NC}"
else
    echo -e "  ${RED}✗ 未找到 service.sh${NC}"
fi

# 创建 zygisk 目录并复制 so 文件
mkdir -p $TEMP_DIR/zygisk
SO_COUNT=0

# 查找并复制 so 文件
for arch in armeabi-v7a arm64-v8a x86 x86_64; do
    SO_PATH="module/build/intermediates/stripped_native_libs/release/out/lib/$arch/libmyinjector.so"
    if [ -f "$SO_PATH" ]; then
        cp "$SO_PATH" "$TEMP_DIR/zygisk/$arch.so"
        echo -e "  ${GREEN}✓ 复制 $arch.so${NC}"
        ((SO_COUNT++))
    fi
done

if [ $SO_COUNT -eq 0 ]; then
    echo -e "  ${RED}✗ 未找到任何 SO 文件${NC}"
    exit 1
fi

# 复制 ConfigApp APK
APK_PATH="configapp/build/outputs/apk/debug/configapp-debug.apk"
if [ -f "$APK_PATH" ]; then
    cp "$APK_PATH" "$TEMP_DIR/configapp.apk"
    echo -e "  ${GREEN}✓ 复制 ConfigApp APK${NC}"
    
    # 显示 APK 信息
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo -e "    APK 大小: $APK_SIZE"
else
    echo -e "  ${RED}✗ 未找到 ConfigApp APK${NC}"
    exit 1
fi

# 创建 META-INF 目录（Magisk 需要）
mkdir -p $TEMP_DIR/META-INF/com/google/android
touch $TEMP_DIR/META-INF/com/google/android/update-binary
touch $TEMP_DIR/META-INF/com/google/android/updater-script

# 打包
echo -e "\n${YELLOW}[5/5] 打包模块...${NC}"
ZIP_NAME="${MODULE_ID}-${MODULE_VERSION}.zip"
cd $TEMP_DIR
zip -r ../$ZIP_NAME * -x "*.DS_Store" > /dev/null 2>&1
cd ../..

# 显示结果
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}✓ 构建完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "\n模块文件: ${GREEN}build/$ZIP_NAME${NC}"

# 显示模块内容
echo -e "\n模块内容:"
unzip -l build/$ZIP_NAME | grep -E "(\.so|\.apk|\.prop|\.sh)" | while read line; do
    echo -e "  $line"
done

# 显示模块大小
MODULE_SIZE=$(du -h build/$ZIP_NAME | cut -f1)
echo -e "\n模块大小: ${GREEN}$MODULE_SIZE${NC}"

# 安装说明
echo -e "\n${YELLOW}安装方法:${NC}"
echo -e "  1. 将模块传输到手机:"
echo -e "     ${GREEN}adb push build/$ZIP_NAME /sdcard/${NC}"
echo -e "  2. 在 Magisk Manager 中安装模块"
echo -e "  3. 重启手机"
echo -e "\n${YELLOW}验证安装:${NC}"
echo -e "  ${GREEN}adb shell pm list packages | grep com.jiqiu.configapp${NC}"
echo -e "  ${GREEN}adb shell cat /data/local/tmp/myinjector_install.log${NC}"

# 可选：直接安装到设备
if [ "$1" == "--install" ]; then
    echo -e "\n${YELLOW}正在安装到设备...${NC}"
    adb push build/$ZIP_NAME /data/local/tmp/
    adb shell su -c "magisk --install-module /data/local/tmp/$ZIP_NAME"
    echo -e "${GREEN}✓ 安装完成，请重启设备${NC}"
fi