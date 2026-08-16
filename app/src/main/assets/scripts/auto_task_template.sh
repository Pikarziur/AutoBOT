#!/system/bin/sh
# AutoBOT 示例脚本 - 自动化任务模板
# 可以在此自定义各种自动化操作，结合 input/pm/am/wm 等命令

TASK_NAME="${1:-default_task}"
RUN_COUNT="${2:-1}"

echo "===== AutoBOT Task: $TASK_NAME ====="
echo "执行次数: $RUN_COUNT"
echo "开始时间: $(date +%H:%M:%S)"
echo ""

for i in $(seq 1 "$RUN_COUNT"); do
    echo "--- 第 $i / $RUN_COUNT 轮 ---"

    # 示例 1: 点亮屏幕并解锁（简单模拟，实际需根据设备调整）
    echo "[1/3] 屏幕操作..."
    # 亮屏
    input keyevent KEYCODE_POWER 2>/dev/null
    sleep 0.5
    # 模拟上滑解锁
    input swipe 500 1500 500 500 300 2>/dev/null
    sleep 0.5

    # 示例 2: 打开计算器
    echo "[2/3] 启动计算器..."
    am start -n com.android.calculator2/.Calculator 2>/dev/null || \
    am start -n com.google.android.calculator/com.android.calculator2.Calculator 2>/dev/null || \
    echo "  ! 未找到计算器应用"
    sleep 2

    # 示例 3: 输入数字 123 + 456 =
    echo "[3/3] 模拟按键输入..."
    input tap 200 600 2>/dev/null  # 1 (位置需根据分辨率调整)
    sleep 0.2
    input tap 400 600 2>/dev/null  # 2
    sleep 0.2
    input tap 600 600 2>/dev/null  # 3
    sleep 0.2

    # 回到主页
    input keyevent KEYCODE_HOME 2>/dev/null
    sleep 1

    echo "第 $i 轮完成"
    echo ""
done

echo "结束时间: $(date +%H:%M:%S)"
echo "===== Task Done: $TASK_NAME ====="
exit 0
