#!/system/bin/sh
# AutoBOT 示例脚本 - 基础设备信息
# 说明：通过 Shizuku 执行可获得更高权限
# 用法：在 ShellExecutor.executeScript("/sdcard/xxx.sh") 中传入路径

echo "===== AutoBOT Device Info ====="
echo "执行时间: $(date)"
echo ""

# 基本设备信息
echo "[设备信息]"
echo "型号: $(getprop ro.product.model)"
echo "厂商: $(getprop ro.product.manufacturer)"
echo "系统版本: $(getprop ro.build.version.release)"
echo "SDK版本: $(getprop ro.build.version.sdk)"
echo ""

# 网络信息（需要权限）
echo "[网络信息]"
echo "IP地址: $(ip -4 addr show wlan0 2>/dev/null | grep -oP '(?<=inet\s)\d+(\.\d+){3}' || echo 'N/A')"
echo ""

# 电池信息
echo "[电池信息]"
if [ -f /sys/class/power_supply/battery/capacity ]; then
    echo "电量: $(cat /sys/class/power_supply/battery/capacity)%"
fi
if [ -f /sys/class/power_supply/battery/status ]; then
    echo "状态: $(cat /sys/class/power_supply/battery/status)"
fi
echo ""

# 屏幕分辨率
echo "[屏幕]"
echo "分辨率: $(wm size)"
echo "密度: $(wm density)"
echo ""

echo "===== 执行完成 ====="
exit 0
