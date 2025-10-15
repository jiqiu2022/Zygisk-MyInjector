# Auto Config Tool for Zygisk-MyInjector

自动配置工具，通过交互式命令行快速配置应用注入。

## 功能特性

- 🎯 交互式设备选择（支持多设备）
- 🔍 Tab 自动补全选择应用包名（支持模糊搜索）
- ⚙️ 交互式 Gadget 配置（Server/Script 模式）
- 📦 自动下载 Frida Gadget 16.0.7（自动检测设备架构）
- 📦 自动生成配置文件
- 🚀 一键推送并应用配置

## 安装依赖

```bash
pip install prompt_toolkit
```

## 使用方法

### 基本用法

```bash
./auto_config.py
```

### 工作流程

1. **设备选择**
   - 自动检测连接的设备
   - 单设备时自动选择
   - 多设备时交互式选择

2. **应用选择**
   - 可选是否包含系统应用（默认仅第三方应用）
   - 使用 Tab 键自动补全包名
   - 支持模糊搜索过滤

3. **Gadget 配置**
   - 指定 Gadget 名称（默认 libgadget.so）
   - **Server 模式**：监听端口等待 Frida 连接
     - 监听地址（默认 0.0.0.0）
     - 监听端口（默认 27042）
     - 端口冲突处理（fail/ignore/close，默认 fail）
     - 加载行为（resume/wait，默认 resume）
   
   - **Script 模式**：执行本地脚本
     - 脚本路径（默认 /data/local/tmp/script.js）

4. **Frida Gadget 检查与安装**
   - 自动检查设备上是否已安装 Gadget
   - 如未安装，自动下载 Frida Gadget 16.0.7
   - 根据设备架构选择正确版本（arm64/arm/x86/x86_64）
   - 自动解压并安装到模块 SO 库

5. **配置部署**
   - 自动生成 config.json 和 gadget 配置文件
   - 推送到设备 /data/local/tmp
   - 发送广播触发应用配置

## 示例会话

```
============================================================
Zygisk-MyInjector Auto Config Tool
============================================================

Using device: 192.168.1.100:5555 (OnePlus)

=== Loading app packages ===
Include system apps? (y/N): n
Found 156 packages

=== Select Target App ===
Tip: Use Tab for auto-completion, type to filter
Package name: com.example.app
Selected: com.example.app

=== Gadget Configuration ===
Gadget SO name (default: libgadget.so): 
libgadget.so

Select mode:
1. Server mode (listen for connections)
2. Script mode (execute script)
Mode (1/2, default: 1): 1

Listen address (default: 0.0.0.0): 
Listen port (default: 27042): 

Port conflict behavior:
1. fail - Exit if port is in use
2. ignore - Continue anyway
3. close - Close existing connection
On port conflict (1/2/3, default: 1): 1

On load behavior:
1. resume - Continue immediately (recommended)
2. wait - Wait for connection (for debugging)
On load (1/2, default: 1): 1

=== Generating Configuration Files ===
...

✓ Configuration applied successfully!

The app 'com.example.app' has been configured.
You can now use Frida to connect to the app:
  frida -H 0.0.0.0:27042 -n <process-name>
```

## 生成的文件

脚本会生成以下文件：

1. **config.json**
   - 模块主配置文件
   - 存储位置：`/data/adb/modules/zygisk-myinjector/config.json`

2. **gadget 配置文件**
   - 格式：`libgadget.config.so`
   - 存储位置：`/data/data/<package>/files/libgadget.config.so`

## 广播接收器

配置通过广播接收器应用：

**注意**：ConfigApplyReceiver 现在使用**动态注册**方式，第三方 app 无法通过 PackageManager 发现其存在。
同时增加了 UID 权限检查，只允许 shell (2000) 或 root (0) 发送广播。

```bash
# 手动发送广播示例
adb shell am broadcast \
  -n com.jiqiu.configapp/.ConfigApplyReceiver \
  -a com.jiqiu.configapp.APPLY_CONFIG \
  --es package_name "com.example.app" \
  --es tmp_config_path "/data/local/tmp/zygisk_config.json" \
  --es tmp_gadget_config_path "/data/local/tmp/libgadget.config.so"
```

## 调试

查看日志：

```bash
adb logcat -s ConfigApplyReceiver:* ConfigManager:*
```

## 故障排除

### prompt_toolkit 未安装

```
Error: prompt_toolkit is required. Install it with:
  pip install prompt_toolkit
```

**解决方案**：运行 `pip install prompt_toolkit`

### 没有设备连接

```
Error: No devices found. Please connect a device and enable USB debugging.
```

**解决方案**：
1. 通过 USB 或 WiFi 连接设备
2. 确保已启用 USB 调试
3. 运行 `adb devices` 确认设备已连接

### 广播发送失败

**解决方案**：
1. 确保 configapp 已安装
2. 确保设备已 root
3. 检查 logcat 日志获取详细错误信息

## 高级用法

### 仅生成配置不部署

修改广播命令添加 `deploy_only` 参数：

```bash
adb shell am broadcast \
  -n com.jiqiu.configapp/.ConfigApplyReceiver \
  -a com.jiqiu.configapp.APPLY_CONFIG \
  --es package_name "com.example.app" \
  --es tmp_config_path "/data/local/tmp/zygisk_config.json" \
  --ez deploy_only true
```

## 注意事项

1. ⚠️ 设备必须已 root
2. ⚠️ configapp 必须已安装
3. ✓ **SELinux 会自动检查并设置为 Permissive 模式**（脚本会自动处理）
4. ⚠️ 首次使用会自动下载 Frida Gadget 16.0.7（需要网络连接）
5. ⚠️ 配置完成后需要重启目标应用才能生效
6. ⚠️ 需要安装 `xz` 工具用于解压（macOS 通过 `brew install xz` 安装）

### SELinux 问题

Zygisk 模块需要读取 `/data/adb/modules/zygisk-myinjector/config.json`，但 SELinux 默认会阻止 zygote 进程访问。

**自动处理**：
`auto_config.py` 脚本会自动检查 SELinux 状态，如果处于 Enforcing 模式会提示设置为 Permissive。

**手动设置**（重启后需重新设置）：
```bash
adb shell "su -c 'setenforce 0'"
```

**永久解决方案**（需要 Magisk 模块）：
创建 SELinux 策略或修改模块实现方式。

## 完整工作流程

```bash
# 1. 运行自动配置脚本（会自动检查并设置 SELinux）
cd scripts
./auto_config.py

# 2. 按提示选择设备、应用和配置（全部使用默认值即可）
#    脚本会自动完成：
#    - 生成配置文件
#    - 推送到设备
#    - 应用配置
#    - 重启应用
#    - 端口转发
#    - 快速测试

# 3. 如果测试成功，直接使用 Frida 连接
frida -H 127.0.0.1:27042 Gadget -l your_script.js
```

### 快速测试示例

```bash
# 测试连接
frida -H 127.0.0.1:27042 Gadget -e "console.log('Connected! PID:', Process.id)"

# 列举模块
frida -H 127.0.0.1:27042 Gadget -e "Process.enumerateModules().forEach(m => console.log(m.name))"
```
