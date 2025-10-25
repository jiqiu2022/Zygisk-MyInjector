#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Auto Config Script for Zygisk-MyInjector
通过交互式命令行快速配置应用注入

完整工作流程:
  1. 运行自动配置脚本（会自动检查并设置 SELinux）
     cd scripts
     ./auto_config.py
  
  2. 按提示选择设备、应用和配置（全部使用默认值即可）
     脚本会自动完成：
     - 生成配置文件
     - 推送到设备
     - 应用配置
     - 重启应用
     - 端口转发
     - 快速测试
  
  3. 如果测试成功，直接使用 Frida 连接
     frida -H 127.0.0.1:27042 Gadget -l your_script.js

快速测试:
  # 测试连接
  frida -H 127.0.0.1:27042 Gadget -e "console.log('Connected! PID:', Process.id)"
  
  # 列举模块
  frida -H 127.0.0.1:27042 Gadget -e "Process.enumerateModules().forEach(m => console.log(m.name))"
"""

import subprocess
import json
import sys
import os
import tempfile
import shutil
import argparse
from typing import List, Dict, Optional
from pathlib import Path

try:
    from prompt_toolkit import prompt
    from prompt_toolkit.completion import FuzzyWordCompleter
    from prompt_toolkit.styles import Style
except ImportError:
    print("Error: prompt_toolkit is required. Install it with:")
    print("  pip install prompt_toolkit")
    sys.exit(1)


# Define style for prompts
style = Style.from_dict({
    'prompt': 'ansicyan bold',
})

# Frida Gadget version
FRIDA_VERSION = "16.0.7"
MODULE_PATH = "/data/adb/modules/zygisk-myinjector"
SO_STORAGE_DIR = f"{MODULE_PATH}/so_files"

# Default ports
DEFAULT_PORTS = [27042, 65320]

# Local cache directory for downloaded gadgets
SCRIPT_DIR = Path(__file__).parent
CACHE_DIR = SCRIPT_DIR / '.cache' / 'frida-gadgets'


class ADBHelper:
    """ADB helper class for device and package operations"""
    
    def __init__(self, device_id: Optional[str] = None):
        self.device_id = device_id
        self.base_cmd = ['adb']
        if device_id:
            self.base_cmd.extend(['-s', device_id])
    
    def run(self, args: List[str], check=True) -> subprocess.CompletedProcess:
        """Run adb command"""
        cmd = self.base_cmd + args
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, check=check)
            return result
        except subprocess.CalledProcessError as e:
            print(f"Error running command: {' '.join(cmd)}")
            print(f"Error: {e.stderr}")
            if check:
                raise
            return e
    
    @staticmethod
    def get_devices() -> List[Dict[str, str]]:
        """Get list of connected devices"""
        result = subprocess.run(['adb', 'devices', '-l'], capture_output=True, text=True)
        lines = result.stdout.strip().split('\n')[1:]  # Skip header
        devices = []
        
        for line in lines:
            if not line.strip():
                continue
            parts = line.split()
            if len(parts) >= 2:
                device_id = parts[0]
                status = parts[1]
                
                # Parse device info
                model = ''
                product = ''
                for part in parts[2:]:
                    if part.startswith('model:'):
                        model = part.split(':', 1)[1]
                    elif part.startswith('product:'):
                        product = part.split(':', 1)[1]
                
                devices.append({
                    'id': device_id,
                    'status': status,
                    'model': model,
                    'product': product
                })
        
        return devices
    
    def get_packages(self, include_system: bool = False) -> List[str]:
        """Get list of installed packages"""
        args = ['shell', 'pm', 'list', 'packages']
        if not include_system:
            args.append('-3')  # Third-party apps only
        
        result = self.run(args)
        packages = []
        for line in result.stdout.strip().split('\n'):
            if line.startswith('package:'):
                pkg = line.split(':', 1)[1].strip()
                packages.append(pkg)
        
        return sorted(packages)
    
    def push_file(self, local_path: str, remote_path: str) -> bool:
        """Push file to device"""
        result = self.run(['push', local_path, remote_path], check=False)
        return result.returncode == 0
    
    def send_broadcast(self, action: str, component: str, extras: Dict[str, str]) -> bool:
        """Send broadcast with extras"""
        args = ['shell', 'am', 'broadcast', '-n', component, '-a', action]
        
        for key, value in extras.items():
            args.extend(['--es', key, value])
        
        result = self.run(args, check=False)
        if result.returncode == 0:
            print(f"Broadcast sent successfully")
            print(result.stdout)
            return True
        else:
            print(f"Failed to send broadcast")
            print(result.stderr)
            return False
    
    def get_arch(self) -> str:
        """Get device CPU architecture"""
        result = self.run(['shell', 'getprop', 'ro.product.cpu.abi'])
        arch = result.stdout.strip()
        return arch
    
    def file_exists(self, path: str) -> bool:
        """Check if file exists on device"""
        result = self.run(['shell', f'su -c "test -f {path} && echo exists"'], check=False)
        return 'exists' in result.stdout


def select_device() -> Optional[str]:
    """Interactive device selection"""
    devices = ADBHelper.get_devices()
    
    if not devices:
        print("Error: No devices found. Please connect a device and enable USB debugging.")
        return None
    
    if len(devices) == 1:
        device = devices[0]
        print(f"Using device: {device['id']} ({device['model'] or device['product']})")
        return device['id']
    
    print("\n=== Connected Devices ===")
    for idx, device in enumerate(devices, 1):
        model_info = device['model'] or device['product'] or 'Unknown'
        print(f"{idx}. {device['id']} - {model_info} [{device['status']}]")
    
    while True:
        try:
            choice = input(f"\nSelect device (1-{len(devices)}): ").strip()
            idx = int(choice) - 1
            if 0 <= idx < len(devices):
                selected = devices[idx]
                print(f"Selected: {selected['id']} ({selected['model'] or selected['product']})")
                return selected['id']
            else:
                print(f"Invalid choice. Please enter 1-{len(devices)}")
        except (ValueError, KeyboardInterrupt):
            print("\nDevice selection cancelled")
            return None


def select_package(adb: ADBHelper) -> Optional[str]:
    """Interactive package selection with fuzzy completion"""
    print("\n=== Loading app packages ===")
    
    # Ask if include system apps
    while True:
        choice = input("Include system apps? (y/N): ").strip().lower()
        if choice in ['', 'n', 'no']:
            include_system = False
            break
        elif choice in ['y', 'yes']:
            include_system = True
            break
        else:
            print("Please enter 'y' or 'n'")
    
    packages = adb.get_packages(include_system=include_system)
    
    if not packages:
        print("Error: No packages found")
        return None
    
    print(f"Found {len(packages)} packages")
    
    # Create fuzzy completer
    completer = FuzzyWordCompleter(packages)
    
    print("\n=== Select Target App ===")
    print("Tip: Use Tab for auto-completion, type to filter")
    
    try:
        package = prompt(
            'Package name: ',
            completer=completer,
            style=style
        ).strip()
        
        if package and package in packages:
            print(f"Selected: {package}")
            return package
        elif package:
            print(f"Warning: '{package}' not found in package list, using anyway")
            return package
        else:
            print("No package selected")
            return None
    except KeyboardInterrupt:
        print("\nSelection cancelled")
        return None


def configure_gadget(preset_port: Optional[int] = None) -> Optional[Dict]:
    """Interactive gadget configuration"""
    print("\n=== Gadget Configuration ===")
    
    gadget_config = {}
    
    # Gadget name
    gadget_name = input("Gadget SO name (default: libgadget.so): ").strip()
    gadget_config['gadgetName'] = gadget_name or 'libgadget.so'
    
    # Mode selection
    print("\nSelect mode:")
    print("1. Server mode (listen for connections)")
    print("2. Script mode (execute script)")
    
    while True:
        choice = input("Mode (1/2, default: 1): ").strip()
        if choice in ['', '1']:
            gadget_config['mode'] = 'server'
            break
        elif choice == '2':
            gadget_config['mode'] = 'script'
            break
        else:
            print("Invalid choice. Please enter 1 or 2")
    
    if gadget_config['mode'] == 'server':
        # Server mode configuration
        address = input("Listen address (default: 0.0.0.0): ").strip()
        gadget_config['address'] = address or '0.0.0.0'
        
        # Use preset port if provided
        if preset_port:
            gadget_config['port'] = preset_port
            print(f"\nUsing preset port: {preset_port}")
        else:
            print(f"\nAvailable ports: {', '.join(map(str, DEFAULT_PORTS))}")
            port = input(f"Listen port (default: {DEFAULT_PORTS[0]}): ").strip()
            try:
                gadget_config['port'] = int(port) if port else DEFAULT_PORTS[0]
            except ValueError:
                print(f"Invalid port, using default {DEFAULT_PORTS[0]}")
                gadget_config['port'] = DEFAULT_PORTS[0]
        
        print("\nPort conflict behavior:")
        print("1. fail - Exit if port is in use")
        print("2. ignore - Continue anyway")
        print("3. close - Close existing connection")
        conflict = input("On port conflict (1/2/3, default: 1): ").strip()
        conflict_map = {'1': 'fail', '2': 'ignore', '3': 'close', '': 'fail'}
        gadget_config['onPortConflict'] = conflict_map.get(conflict, 'fail')
        
        print("\nOn load behavior:")
        print("1. resume - Continue immediately (recommended)")
        print("2. wait - Wait for connection (for debugging)")
        load = input("On load (1/2, default: 1): ").strip()
        load_map = {'1': 'resume', '2': 'wait', '': 'resume'}
        gadget_config['onLoad'] = load_map.get(load, 'resume')
        
    else:
        # Script mode configuration
        script_path = input("Script path (default: /data/local/tmp/script.js): ").strip()
        gadget_config['scriptPath'] = script_path or '/data/local/tmp/script.js'
    
    return gadget_config


def download_frida_gadget(arch: str) -> Optional[str]:
    """Download Frida Gadget for specified architecture (with local caching)"""
    # Map Android arch to Frida naming
    arch_map = {
        'arm64-v8a': 'arm64',
        'armeabi-v7a': 'arm',
        'x86': 'x86',
        'x86_64': 'x86_64'
    }
    
    frida_arch = arch_map.get(arch)
    if not frida_arch:
        print(f"Unsupported architecture: {arch}")
        return None
    
    # Check local cache first
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cached_file = CACHE_DIR / f"frida-gadget-{FRIDA_VERSION}-android-{frida_arch}.so"
    
    if cached_file.exists():
        print(f"\n✓ Using cached Frida Gadget {FRIDA_VERSION} for {arch}")
        print(f"  Cache: {cached_file}")
        # Copy to temp location for consistent behavior
        temp_dir = tempfile.mkdtemp(prefix='frida_gadget_')
        temp_file = os.path.join(temp_dir, 'libgadget.so')
        shutil.copy2(str(cached_file), temp_file)
        return temp_file
    
    # Download if not in cache
    url = f"https://github.com/frida/frida/releases/download/{FRIDA_VERSION}/frida-gadget-{FRIDA_VERSION}-android-{frida_arch}.so.xz"
    
    print(f"\nDownloading Frida Gadget {FRIDA_VERSION} for {arch}...")
    print(f"URL: {url}")
    
    # Create temp directory
    temp_dir = tempfile.mkdtemp(prefix='frida_gadget_')
    xz_file = os.path.join(temp_dir, f'frida-gadget.so.xz')
    so_file = os.path.join(temp_dir, 'libgadget.so')
    
    try:
        # Download
        result = subprocess.run(['curl', '-L', '-o', xz_file, url], 
                              capture_output=True, text=True, check=False)
        if result.returncode != 0:
            print(f"Failed to download: {result.stderr}")
            shutil.rmtree(temp_dir)
            return None
        
        print("✓ Downloaded")
        
        # Decompress
        print("Decompressing...")
        result = subprocess.run(['xz', '-d', xz_file], 
                              capture_output=True, text=True, check=False)
        if result.returncode != 0:
            print(f"Failed to decompress: {result.stderr}")
            shutil.rmtree(temp_dir)
            return None
        
        # Rename
        decompressed = xz_file.replace('.xz', '')
        os.rename(decompressed, so_file)
        
        print("✓ Decompressed")
        
        # Save to cache for future use
        try:
            shutil.copy2(so_file, str(cached_file))
            print(f"✓ Cached for future use: {cached_file}")
        except Exception as e:
            print(f"Warning: Failed to cache gadget: {e}")
        
        return so_file
        
    except Exception as e:
        print(f"Error downloading Frida Gadget: {e}")
        if os.path.exists(temp_dir):
            shutil.rmtree(temp_dir)
        return None


def ensure_gadget_installed(adb: ADBHelper, gadget_name: str = 'libgadget.so') -> bool:
    """Ensure Frida Gadget is installed on device"""
    gadget_path = f"{SO_STORAGE_DIR}/{gadget_name}"
    
    print(f"\n=== Checking Frida Gadget ===")
    
    # Check if gadget already exists
    if adb.file_exists(gadget_path):
        print(f"✓ Gadget found: {gadget_path}")
        return True
    
    print(f"Gadget not found in: {gadget_path}")
    print("Need to download and install Frida Gadget")
    
    # Get device architecture
    arch = adb.get_arch()
    print(f"Device architecture: {arch}")
    
    # Download gadget
    local_gadget = download_frida_gadget(arch)
    if not local_gadget:
        print("Failed to download Frida Gadget")
        return False
    
    try:
        # Push to device temp location
        print("\nPushing to device...")
        if not adb.push_file(local_gadget, '/data/local/tmp/libgadget.so'):
            print("Failed to push gadget to device")
            return False
        
        print("✓ Pushed to device")
        
        # Copy to SO storage with root
        print(f"Installing to {gadget_path}...")
        result = adb.run(['shell', f'su -c "mkdir -p {SO_STORAGE_DIR}"'], check=False)
        result = adb.run(['shell', 
                         f'su -c "cp /data/local/tmp/libgadget.so {gadget_path}"'], 
                        check=False)
        
        if result.returncode != 0:
            print(f"Failed to install gadget: {result.stderr}")
            return False
        
        # Set permissions
        adb.run(['shell', f'su -c "chmod 755 {gadget_path}"'], check=False)
        
        # Verify
        if adb.file_exists(gadget_path):
            print(f"✓ Gadget installed successfully: {gadget_path}")
            # Clean up temp file on device
            adb.run(['shell', 'rm -f /data/local/tmp/libgadget.so'], check=False)
            return True
        else:
            print("Failed to verify gadget installation")
            return False
            
    finally:
        # Clean up local temp file
        temp_dir = os.path.dirname(local_gadget)
        if os.path.exists(temp_dir):
            shutil.rmtree(temp_dir)


def generate_config_files(package_name: str, gadget_config: Dict) -> tuple:
    """Generate config.json and gadget config content"""
    
    # Prepare SO file reference for gadget
    gadget_name = gadget_config['gadgetName']
    gadget_so_ref = {
        "name": gadget_name,
        "storedPath": f"{SO_STORAGE_DIR}/{gadget_name}",
        "originalPath": f"{SO_STORAGE_DIR}/{gadget_name}"
    }
    
    # Generate main module config.json
    module_config = {
        "enabled": True,
        "hideInjection": False,
        "injectionDelay": 2,
        "globalSoFiles": [],
        "perAppConfig": {
            package_name: {
                "enabled": True,
                "soFiles": [gadget_so_ref],  # Include gadget SO file
                "injectionMethod": "standard",
                "gadgetConfig": gadget_config,
                "useGlobalGadget": False
            }
        },
        "globalGadgetConfig": None
    }
    
    # Generate gadget config content based on mode
    if gadget_config['mode'] == 'server':
        gadget_config_content = {
            "interaction": {
                "type": "listen",
                "address": gadget_config['address'],
                "port": gadget_config['port'],
                "on_port_conflict": gadget_config['onPortConflict'],
                "on_load": gadget_config['onLoad']
            }
        }
    else:  # script mode
        gadget_config_content = {
            "interaction": {
                "type": "script",
                "path": gadget_config['scriptPath']
            }
        }
    
    return (
        json.dumps(module_config, indent=2, ensure_ascii=False),
        json.dumps(gadget_config_content, indent=2, ensure_ascii=False)
    )


def quick_test(adb: ADBHelper, port: int = 27042):
    """Quick test connectivity after configuration"""
    print("\n=== Quick Test ===")
    print("Testing Frida connectivity...\n")
    
    # Check if frida is installed
    result = subprocess.run(['which', 'frida'], capture_output=True, text=True)
    if result.returncode != 0:
        print("⚠️  Frida CLI not found. Please install it with:")
        print("    pip install frida-tools")
        return False
    
    # Test 1: Basic connection test
    print("Test 1: Basic connection...")
    # 使用 stdin 输入命令和 exit，避免交互式 REPL 导致超时
    test_input = "console.log('Connected! PID:', Process.id)\nexit\n"
    test_cmd = ['frida', '-H', f'127.0.0.1:{port}', 'Gadget']
    
    result = subprocess.run(test_cmd, input=test_input, capture_output=True, text=True, timeout=10)
    if result.returncode == 0 and 'Connected!' in result.stdout:
        print("✓ Connection successful!")
        # 提取并显示 PID
        for line in result.stdout.split('\n'):
            if 'Connected! PID:' in line:
                print(f"  {line.strip()}")
                break
    else:
        print("✗ Connection failed")
        print(f"  Error: {result.stderr.strip() if result.stderr else 'Unknown error'}")
        print("\nTroubleshooting:")
        print("  1. Check if the target app is running")
        print("  2. Verify port forwarding: adb forward tcp:{} tcp:{}".format(port, port))
        print("  3. Check logcat for errors: adb logcat -s Gadget:*")
        return False
    
    # Test 2: Enumerate modules
    print("\nTest 2: Enumerate loaded modules...")
    test_input = "Process.enumerateModules().slice(0, 5).forEach(m => console.log('  -', m.name))\nexit\n"
    test_cmd = ['frida', '-H', f'127.0.0.1:{port}', 'Gadget']
    
    result = subprocess.run(test_cmd, input=test_input, capture_output=True, text=True, timeout=10)
    if result.returncode == 0:
        print("✓ Modules enumerated:")
        # 提取并显示模块列表
        in_output = False
        for line in result.stdout.split('\n'):
            if '  -' in line:
                in_output = True
                print(line)
            elif in_output and line.strip() == '':
                break
    else:
        print("✗ Failed to enumerate modules")
        print(f"  Error: {result.stderr.strip()}")
    
    return True


def setup_port_forward(adb: ADBHelper, port: int) -> bool:
    """Setup ADB port forwarding"""
    print(f"\n=== Setting up port forwarding ===")
    print(f"Forwarding local port {port} to device port {port}...")
    
    result = adb.run(['forward', f'tcp:{port}', f'tcp:{port}'], check=False)
    if result.returncode == 0:
        print(f"✓ Port forwarding established: tcp:{port} -> tcp:{port}")
        return True
    else:
        print(f"✗ Failed to setup port forwarding")
        print(f"  Error: {result.stderr}")
        return False


def check_and_set_selinux(adb: ADBHelper) -> bool:
    """Check SELinux status and set to Permissive if needed"""
    print("\n=== Checking SELinux Status ===")
    
    # Check current SELinux status
    result = adb.run(['shell', 'getenforce'], check=False)
    if result.returncode != 0:
        print("⚠️  Failed to check SELinux status")
        return True  # Continue anyway
    
    status = result.stdout.strip()
    print(f"Current SELinux mode: {status}")
    
    if status == 'Permissive':
        print("✓ SELinux is already in Permissive mode")
        return True
    elif status == 'Enforcing':
        print("\n⚠️  SELinux is in Enforcing mode")
        print("   Zygisk modules cannot read config files when SELinux is Enforcing.")
        print("   We need to set it to Permissive mode.")
        
        # Ask user for confirmation
        while True:
            choice = input("\nSet SELinux to Permissive? (Y/n): ").strip().lower()
            if choice in ['', 'y', 'yes']:
                break
            elif choice in ['n', 'no']:
                print("\nWarning: Continuing with SELinux Enforcing may cause injection to fail.")
                print("You can manually set it later with: adb shell \"su -c 'setenforce 0'\"")
                return True
            else:
                print("Please enter 'y' or 'n'")
        
        # Set SELinux to Permissive
        print("\nSetting SELinux to Permissive...")
        result = adb.run(['shell', 'su', '-c', 'setenforce 0'], check=False)
        
        if result.returncode == 0:
            print("✓ SELinux set to Permissive mode")
            print("  Note: This setting will reset after reboot")
            return True
        else:
            print("✗ Failed to set SELinux to Permissive")
            print(f"  Error: {result.stderr.strip()}")
            print("\nPlease manually run: adb shell \"su -c 'setenforce 0'\"")
            return False
    else:
        print(f"Unknown SELinux status: {status}")
        return True


def restart_app(adb: ADBHelper, package_name: str):
    """Restart the target application"""
    print(f"\n=== Restarting Application ===")
    
    # Force stop
    print(f"Stopping {package_name}...")
    result = adb.run(['shell', 'am', 'force-stop', package_name], check=False)
    if result.returncode == 0:
        print("✓ App stopped")
    else:
        print("⚠️  Failed to stop app")
    
    # Get main activity
    print(f"\nGetting launch activity...")
    result = adb.run(['shell', 'pm', 'dump', package_name, '|', 'grep', '-A', '1', 'android.intent.action.MAIN'], check=False)
    
    # Try to start the app
    print(f"Starting {package_name}...")
    result = adb.run(['shell', 'monkey', '-p', package_name, '-c', 'android.intent.category.LAUNCHER', '1'], check=False)
    
    if result.returncode == 0:
        print("✓ App started")
        print("  Note: The app should now load with Frida Gadget injected")
        return True
    else:
        print("⚠️  Failed to start app automatically")
        print("  Please start the app manually from the device")
        return False


def main():
    """Main entry point"""
    # Parse command line arguments
    parser = argparse.ArgumentParser(
        description='Zygisk-MyInjector Auto Config Tool',
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        '-p', '--port',
        type=int,
        choices=DEFAULT_PORTS,
        help=f'Preset Gadget port (choices: {", ".join(map(str, DEFAULT_PORTS))})'
    )
    args = parser.parse_args()
    
    print("=" * 60)
    print("Zygisk-MyInjector Auto Config Tool")
    print("=" * 60)
    
    # Step 1: Select device
    device_id = select_device()
    if not device_id:
        sys.exit(1)
    
    adb = ADBHelper(device_id)
    
    # Step 2: Check and set SELinux
    if not check_and_set_selinux(adb):
        print("\nError: Failed to configure SELinux")
        print("The injection may fail. Please fix SELinux manually and try again.")
        sys.exit(1)
    
    # Step 3: Select package
    package_name = select_package(adb)
    if not package_name:
        sys.exit(1)
    
    # Step 4: Configure gadget
    gadget_config = configure_gadget(preset_port=args.port)
    if not gadget_config:
        sys.exit(1)
    
    # Step 5: Ensure Frida Gadget is installed
    if not ensure_gadget_installed(adb, gadget_config['gadgetName']):
        print("\nError: Failed to install Frida Gadget")
        print("Please manually download and install libgadget.so")
        sys.exit(1)
    
    # Step 6: Generate config files
    print("\n=== Generating Configuration Files ===")
    config_json, gadget_config_json = generate_config_files(package_name, gadget_config)
    
    print("\nGenerated config.json:")
    print(config_json)
    print("\nGenerated gadget config:")
    print(gadget_config_json)
    
    # Step 7: Save to temp files
    temp_dir = tempfile.mkdtemp(prefix='frida_gadget_config_')
    
    config_file = os.path.join(temp_dir, 'config.json')
    gadget_name = gadget_config['gadgetName'].replace('.so', '.config.so')
    gadget_config_file = os.path.join(temp_dir, gadget_name)
    
    with open(config_file, 'w', encoding='utf-8') as f:
        f.write(config_json)
    
    with open(gadget_config_file, 'w', encoding='utf-8') as f:
        f.write(gadget_config_json)
    
    print(f"\nConfig files saved to: {temp_dir}")
    
    # Step 8: Push to device
    print("\n=== Pushing Files to Device ===")
    
    remote_config = '/data/local/tmp/zygisk_config.json'
    remote_gadget_config = f'/data/local/tmp/{gadget_name}'
    
    if not adb.push_file(config_file, remote_config):
        print("Error: Failed to push config.json")
        sys.exit(1)
    print(f"✓ Pushed config.json -> {remote_config}")
    
    if not adb.push_file(gadget_config_file, remote_gadget_config):
        print("Error: Failed to push gadget config")
        sys.exit(1)
    print(f"✓ Pushed gadget config -> {remote_gadget_config}")
    
    # Step 9: Send broadcast
    print("\n=== Sending Broadcast to Apply Config ===")
    
    success = adb.send_broadcast(
        action='com.jiqiu.configapp.APPLY_CONFIG',
        component='com.jiqiu.configapp/.ConfigApplyReceiver',
        extras={
            'package_name': package_name,
            'tmp_config_path': remote_config,
            'tmp_gadget_config_path': remote_gadget_config
        }
    )
    
    if success:
        print("\n✓ Configuration applied successfully!")
        print(f"\nThe app '{package_name}' has been configured.")
        
        # 自动完成工作流程
        print("\n=== Completing Workflow ===")
        
        # Step 1: Restart app
        restart_app(adb, package_name)
        
        # Step 2: Setup port forwarding
        port = gadget_config.get('port', 27042)
        if setup_port_forward(adb, port):
            # Step 3: Quick test
            import time
            print("\nWaiting 3 seconds for app to initialize...")
            time.sleep(3)
            
            try:
                test_success = quick_test(adb, port)
                if test_success:
                    # 彩色打印 frida 命令
                    print("\n" + "="*60)
                    print("\033[1;32m✓ All tests passed!\033[0m\n")
                    print("\033[1;36mYou can now use Frida with the following commands:\033[0m\n")
                    
                    # Interactive mode
                    print("\033[1;33m# Interactive REPL:\033[0m")
                    print(f"\033[1;32mfrida -H 127.0.0.1:{port} Gadget\033[0m\n")
                    
                    # Execute script
                    print("\033[1;33m# Execute JavaScript code:\033[0m")
                    print(f"\033[1;32mfrida -H 127.0.0.1:{port} Gadget -e 'YOUR_CODE_HERE'\033[0m\n")
                    
                    # Load script file
                    print("\033[1;33m# Load script file:\033[0m")
                    print(f"\033[1;32mfrida -H 127.0.0.1:{port} Gadget -l your_script.js\033[0m\n")
                    
                    print("="*60)
            except Exception as e:
                print(f"\n⚠️  Test failed: {e}")
                print("\nYou can manually test with:")
                if gadget_config['mode'] == 'server':
                    print(f"\033[1;32mfrida -H 127.0.0.1:{port} Gadget -l your_script.js\033[0m")
    else:
        print("\n✗ Failed to apply configuration")
        print("Please check logcat for details:")
        print(f"  adb -s {device_id} logcat -s ConfigApplyReceiver:* ConfigManager:*")
        sys.exit(1)
    
    # Clean up temp directory
    try:
        shutil.rmtree(temp_dir)
    except Exception:
        pass  # Ignore cleanup errors


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\n\nOperation cancelled by user")
        sys.exit(130)
