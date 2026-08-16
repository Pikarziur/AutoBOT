#!/system/bin/sh
# AutoBOT 示例脚本 - 无线 ADB 控制
# 用于开启/关闭 ADB 无线调试，以及设备连接测试
#
# 使用前必须先通过 USB 连接执行一次:
#   adb tcpip 5555
#
# 然后拔下数据线，通过 WiFi 连接:
#   adb connect <手机IP>:5555

ADB_PORT="${1:-5555}"
ACTION="${2:-status}"

case "$ACTION" in
    start)
        echo "[*] 开启无线 ADB 端口 $ADB_PORT"
        setprop service.adb.tcp.port "$ADB_PORT"
        stop adbd
        start adbd
        sleep 1
        # 确认端口状态
        PORT_STATUS=$(getprop service.adb.tcp.port)
        echo "[+] 当前 ADB TCP 端口: $PORT_STATUS"
        DEVICE_IP=$(ip -4 addr show wlan0 2>/dev/null | grep -oP '(?<=inet\s)\d+(\.\d+){3}')
        echo "[+] 连接命令: adb connect ${DEVICE_IP}:${ADB_PORT}"
        ;;
    stop)
        echo "[*] 关闭无线 ADB，恢复 USB 模式"
        setprop service.adb.tcp.port -1
        stop adbd
        start adbd
        echo "[+] 已切换为 USB 模式"
        ;;
    status)
        echo "[*] 当前 ADB 状态"
        echo "TCP 端口: $(getprop service.adb.tcp.port)"
        echo "ADB 状态: $(getprop init.svc.adbd)"
        DEVICE_IP=$(ip -4 addr show wlan0 2>/dev/null | grep -oP '(?<=inet\s)\d+(\.\d+){3}')
        echo "WiFi IP: ${DEVICE_IP:-N/A}"
        ;;
    ping)
        echo "[*] ADB 服务测试"
        if command -v adb >/dev/null 2>&1; then
            adb devices
        else
            echo "adb 命令不可用（请在 adb 环境中执行）"
        fi
        ;;
    *)
        echo "用法: $0 [端口] {start|stop|status|ping}"
        echo "  $0 5555 start    - 开启无线 ADB"
        echo "  $0 5555 stop     - 关闭无线 ADB"
        echo "  $0 5555 status   - 查看状态"
        exit 1
        ;;
esac

exit 0
