#!/system/bin/sh

# 编写脚本注意事项:
    # 行尾序列必须为LF
    # 安卓原生的 /system/bin/sh 不支持 for ((i=1; i<=LOOP_TIMES; i++))，安卓 shell 识别不了，所以必须避开这种写法
    # ⚠️ 重要：toybox/mksh 对「命令替换 $(...) 嵌套在 $((算术)) 内」解析不稳定
    #        一律先把 $(rand_int) 的结果存到 shell 变量，再用变量做算术运算，否则 input swipe 会报
    #        "Invalid arguments for command: swipe" / 坐标为空 / 原地不滑动。
    # ⚠️ 重要：toybox `sleep` 在多数 ROM 上只接受整数秒，`sleep 0.1` 会直接报错。
    #        需要短间隔的地方直接省略（分段 input swipe 本身已有自然时间差）。



# =============== 固定配置 ===============
PRE_WAIT=10
BASE_WIDTH=1200
BASE_HEIGHT=2608
WAIT_MIN=3
WAIT_MAX=5

# =============== 可变配置 ===============
WaitTime=3               # 延迟时间（秒）
Task1Repeat=20          # 任务1循环次数（滑动组数）

Task1Btn=(965 1820)     # 任务1按钮坐标（基准分辨率 BASE_WIDTH×BASE_HEIGHT）
Task2Btn=(965 1070)     # 任务2按钮坐标
Task3Btn=(965 1580)     # 任务3按钮坐标

# ========================================


# =============== 固定函数 ===============
# 获取设备分辨率并计算适配参数
#   ★ AutoBOT 下优先用注入的 AUTOBOT_VD_WIDTH / AUTOBOT_VD_HEIGHT（=当前 VD 尺寸）
#     避免 wm size --display $VDID 在部分 ROM 上输出格式不同导致解析为空。
#   ★ 无 env 时 fallback 到 wm size：取 tail -n 1 最后一行（某些设备会输出
#     "Physical size: WxH \n Override size: WxH" 两行，最后一行才是生效尺寸）
if [ -n "$AUTOBOT_VD_WIDTH" ] && [ -n "$AUTOBOT_VD_HEIGHT" ] && [ "$AUTOBOT_VD_WIDTH" -gt 0 ] 2>/dev/null; then
    DEVICE_WIDTH="$AUTOBOT_VD_WIDTH"
    DEVICE_HEIGHT="$AUTOBOT_VD_HEIGHT"
    echo "📐 使用 AutoBOT 注入 VD 分辨率：${DEVICE_WIDTH}×${DEVICE_HEIGHT}"
else
    DEVICE_WIDTH=$(wm size | tail -n 1 | awk '{print $NF}' | cut -d'x' -f1)
    DEVICE_HEIGHT=$(wm size | tail -n 1 | awk '{print $NF}' | cut -d'x' -f2)
    echo "📐 使用 wm size 读取分辨率：${DEVICE_WIDTH}×${DEVICE_HEIGHT}"
fi

# 根据分辨率差异计算缩放比例（保留3位小数精度）
if [ "$DEVICE_WIDTH" -eq "$BASE_WIDTH" ] && [ "$DEVICE_HEIGHT" -eq "$BASE_HEIGHT" ]; then
    NEED_ADAPT=0
    SCALE_X=1000; SCALE_Y=1000
    echo "✅ 当前设备为基准分辨率($BASE_WIDTH×$BASE_HEIGHT)，无需适配"
else
    NEED_ADAPT=1
    SCALE_X=$(( (DEVICE_WIDTH * 1000) / BASE_WIDTH))
    SCALE_Y=$(( (DEVICE_HEIGHT * 1000) / BASE_HEIGHT))
    echo "🔄 分辨率适配：($DEVICE_WIDTH×$DEVICE_HEIGHT) → 缩放比例 X:$((SCALE_X/10))% Y:$((SCALE_Y/10))%"
fi

# 坐标缩放函数
scale_x() { echo $(( ($1 * SCALE_X) / 1000)); }
scale_y() { echo $(( ($1 * SCALE_Y) / 1000)); }

# 计算适配后的坐标
calc_pos() {
    local x
    local y
    x=$(scale_x "$1")
    y=$(scale_y "$2")
    echo "$x $y"
}


#  设备中央点
CENTER_X=$((DEVICE_WIDTH / 2))
CENTER_Y=$((DEVICE_HEIGHT / 2))


# 上滑函数（微间断持续15秒）- 已弃用，保留兼容
swipe_down_15s() {
    local start_y
    local end_y
    local duration
    local end_ts
    local swipe_x
    start_y=$((CENTER_Y + 400))
    end_y=$((CENTER_Y + 300))
    duration=200
    end_ts=$(( $(date +%s) + 15 ))
    swipe_x=$((CENTER_X - 200))
    echo "🔄 开始微间断向上滑动（持续15秒）..."
    while [ $(date +%s) -lt $end_ts ]; do
        input swipe "$swipe_x" "$start_y" "$swipe_x" "$end_y" "$duration"
        sleep 1
        input swipe "$swipe_x" "$start_y" "$swipe_x" "$end_y" "$duration"
        sleep 1
    done
    echo "✅ 微间断小幅度滑动完成"
}

# 下滑函数（微间断持续15秒）- 已弃用，保留兼容
swipe_up_15s() {
    local start_y
    local end_y
    local duration
    local end_ts
    start_y=$((CENTER_Y + 300))
    end_y=$((CENTER_Y + 500))
    duration=200
    end_ts=$(( $(date +%s) + 15 ))
    echo "🔄 开始微间断向下滑动（持续15秒）..."
    while [ $(date +%s) -lt $end_ts ]; do
        input swipe "$CENTER_X" "$start_y" "$CENTER_X" "$end_y" "$duration"
        sleep 1
        input swipe "$CENTER_X" "$start_y" "$CENTER_X" "$end_y" "$duration"
        sleep 1
    done
    echo "✅ 微间断小幅度下滑完成"
}



# 倒计时函数（兼容Android shell）- 原地更新
countdown() {
    secs=$1
    while [ $secs -ge 0 ]
    do
        echo -ne "⏳ 等待：$secs 秒\r"
        sleep 1
        secs=$(expr $secs - 1)
    done
    echo ""
}

# ============ 随机数生成（兼容Android sh） ============
# 种子基于 $RANDOM + 秒级时间戳 + PID，确保多次调用产生不同随机值
_get_seed() {
    echo $(( ($RANDOM * 32768) ^ $(date +%s) ^ $$ ))
}

# 生成指定范围的随机整数 [min, max]
rand_int() {
    local min=$1
    local max=$2
    local seed
    local range
    seed=$(_get_seed)
    range=$((max - min + 1))
    awk -v seed="$seed" -v min="$min" -v range="$range" 'BEGIN{
        srand(seed);
        print int(rand() * range) + min
    }'
}

# 生成0-5秒的随机等待时间（整数秒）
random_wait() {
    rand_int $WAIT_MIN $WAIT_MAX
}

# 获取当前时间戳（秒）
now_ts() {
    date +%s
}

# 计算时间差（秒）
elapsed_sec() {
    local start=$1
    local current
    current=$(date +%s)
    echo $((current - start))
}

# ============ 模拟下滑（4种拟人滑动模式） ============
# 说明：模拟真人浏览时的下滑手势（手指上滑使内容下滚）
#   模式1：快滑 - 短时间内滑动1/4屏幕垂直高度
#   模式2：慢滑 - 长时间内滑动1/4屏幕垂直高度
#   模式3：抛物线右上 - 3段倾斜滑动模拟抛物线（段间省略短停顿：toybox sleep 不接受小数）
#   模式4：抛物线左上 - 模式3的镜像方向
# 滑动X范围：屏幕宽度10%~90%（左右各缩10%避开边缘切换）
# 滑动Y范围：屏幕高度25%~90%（底部缩10%避开边缘切换）
# 调用：swipe_human <模式1-4>
swipe_human() {
    local mode=$1
    # X坐标范围：屏幕宽度10%~90%
    local x_min=$((DEVICE_WIDTH * 10 / 100))
    local x_max=$((DEVICE_WIDTH * 90 / 100))
    # Y坐标范围：屏幕高度25%~90%
    local y_min=$((DEVICE_HEIGHT * 25 / 100))
    local y_max=$((DEVICE_HEIGHT * 90 / 100))
    local quarter_h=$((DEVICE_HEIGHT / 4))   # 1/4屏幕高度
    # 起点Y下限：保证上滑quarter_h后终点Y≥y_min
    local sy_min=$((y_min + quarter_h))
    local sy_max=$y_max

    # 通用临时变量（声明一次减少开销）
    local dur sy ey sx ex rx ry
    local m1_x m1_y m2_x m2_y seg_dur

    case $mode in
        1)
            # 模式1：快滑（短时150-250ms，上滑1/4屏幕高度）
            # ★修复：rand_int 先落盘到变量，再进入算术运算（避免 toybox 嵌套$()解析失败）
            dur=$(rand_int 150 250)
            sy=$(rand_int $sy_min $sy_max)
            ey=$((sy - quarter_h))
            rx=$(rand_int -100 100)
            sx=$((CENTER_X + rx))
            rx=$(rand_int -50 50)
            ex=$((sx + rx))
            # X边界保护
            [ $sx -lt $x_min ] && sx=$x_min
            [ $sx -gt $x_max ] && sx=$x_max
            [ $ex -lt $x_min ] && ex=$x_min
            [ $ex -gt $x_max ] && ex=$x_max
            input swipe "$sx" "$sy" "$ex" "$ey" "$dur"
            ;;
        2)
            # 模式2：慢滑（长时800-1500ms，上滑1/4屏幕高度）
            dur=$(rand_int 800 1500)
            sy=$(rand_int $sy_min $sy_max)
            ey=$((sy - quarter_h))
            rx=$(rand_int -100 100)
            sx=$((CENTER_X + rx))
            rx=$(rand_int -50 50)
            ex=$((sx + rx))
            # X边界保护
            [ $sx -lt $x_min ] && sx=$x_min
            [ $sx -gt $x_max ] && sx=$x_max
            [ $ex -lt $x_min ] && ex=$x_min
            [ $ex -gt $x_max ] && ex=$x_max
            input swipe "$sx" "$sy" "$ex" "$ey" "$dur"
            ;;
        3)
            # 模式3：抛物线右上（3段倾斜滑动）
            # 整体方向：左下 → 右上
            # ★修复：toybox sleep 多数 ROM 不接受 `sleep 0.1`，移除段间 100ms 停顿；
            #        分段 swipe 本身的命令调用 + 内核调度已有自然微停顿，视觉仍呈"抛物线"。
            rx=$(rand_int 100 200)
            sx=$((CENTER_X - rx))
            [ $sx -lt $x_min ] && sx=$x_min
            sy=$(rand_int $sy_min $sy_max)
            rx=$(rand_int 100 200)
            ex=$((CENTER_X + rx))
            [ $ex -gt $x_max ] && ex=$x_max
            ey=$((sy - quarter_h))
            # 3段中间点（Y上凸模拟抛物线弧度）
            m1_x=$(( sx + (ex - sx) / 3 ))
            ry=$(rand_int 40 100)
            m1_y=$(( sy + (ey - sy) / 3 - ry ))
            m2_x=$(( sx + (ex - sx) * 2 / 3 ))
            ry=$(rand_int 30 80)
            m2_y=$(( sy + (ey - sy) * 2 / 3 - ry ))
            seg_dur=$(rand_int 150 300)
            input swipe "$sx" "$sy" "$m1_x" "$m1_y" "$seg_dur"
            input swipe "$m1_x" "$m1_y" "$m2_x" "$m2_y" "$seg_dur"
            input swipe "$m2_x" "$m2_y" "$ex" "$ey" "$seg_dur"
            ;;
        4)
            # 模式4：抛物线左上（模式3的镜像方向）
            # 整体方向：右下 → 左上
            rx=$(rand_int 100 200)
            sx=$((CENTER_X + rx))
            [ $sx -gt $x_max ] && sx=$x_max
            sy=$(rand_int $sy_min $sy_max)
            rx=$(rand_int 100 200)
            ex=$((CENTER_X - rx))
            [ $ex -lt $x_min ] && ex=$x_min
            ey=$((sy - quarter_h))
            m1_x=$(( sx + (ex - sx) / 3 ))
            ry=$(rand_int 40 100)
            m1_y=$(( sy + (ey - sy) / 3 - ry ))
            m2_x=$(( sx + (ex - sx) * 2 / 3 ))
            ry=$(rand_int 30 80)
            m2_y=$(( sy + (ey - sy) * 2 / 3 - ry ))
            seg_dur=$(rand_int 150 300)
            input swipe "$sx" "$sy" "$m1_x" "$m1_y" "$seg_dur"
            input swipe "$m1_x" "$m1_y" "$m2_x" "$m2_y" "$seg_dur"
            input swipe "$m2_x" "$m2_y" "$ex" "$ey" "$seg_dur"
            ;;
    esac
}

# 生成1-N的随机排列（Fisher-Yates洗牌，兼容Android sh）
rand_perm() {
    local n=$1
    local seed
    seed=$(_get_seed)
    awk -v seed="$seed" -v n="$n" 'BEGIN{
        srand(seed)
        for (i = 1; i <= n; i++) arr[i] = i
        for (i = n; i > 1; i--) {
            j = int(rand() * i) + 1
            t = arr[i]; arr[i] = arr[j]; arr[j] = t
        }
        for (i = 1; i <= n; i++) printf "%d ", arr[i]
    }'
}

# 执行一组4次随机顺序的拟人滑动（每组覆盖4种模式各1次）
swipe_group_random() {
    local order
    order=$(rand_perm 4)
    echo "🔀 本组滑动顺序：${order}"
    local i=1
    local gap
    for mode in $order; do
        swipe_human "$mode"
        # 模式之间停顿（WAIT_MIN~WAIT_MAX秒），最后一次后不停顿由外层等待处理
        if [ $i -lt 4 ]; then
            gap=$(random_wait)
            echo "   ⏳ 模式间停顿${gap}秒"
            sleep "$gap"
        fi
        i=$((i + 1))
    done
}
# ========================================




# =============== 任务初始化 ===============
echo "====================================="
echo "📱 当前设备分辨率：${DEVICE_WIDTH}px × ${DEVICE_HEIGHT}px"
echo "📐 缩放比例：X -- $((SCALE_X/10))% ，Y -- $((SCALE_Y/10))%"
echo "====================================="
echo "🔔 请在${PRE_WAIT}秒内打开目标APP并进入任务页面"
echo "====================================="
countdown $PRE_WAIT
echo ""
echo "====================================="
echo "✅ 准备完成，开始自动任务！"
echo "====================================="





# =============== 任务1执行逻辑 ===============
echo ""
echo "====================================="
echo "🎯 开始执行任务1（循环${Task1Repeat}次）"
echo "====================================="

task1_count=1
read_pause_counter=0
# 记录滑动开始时间
swipe_start_ts=$(date +%s)

while [ $task1_count -le $Task1Repeat ]; do
    echo ""
    echo "🔄 任务1第${task1_count}/${Task1Repeat}次执行"

    # 随机决定本轮行为类型
    behavior_type=$(rand_int 1 10)

    case $behavior_type in
        1|2|3|4|5|6|7)
            # 常规浏览：执行1组4次随机顺序的拟人滑动
            echo "📲 常规浏览模式"
            swipe_group_random
            ;;
        8|9)
            # 深度阅读：1组滑动 + 长停顿
            echo "📲 深度阅读模式"
            swipe_group_random
            long_pause=$(rand_int 2 4)
            sleep "$long_pause"
            ;;
        10)
            # 停顿查看：只滑动1次（随机模式）+ 长停顿
            echo "📲 停顿查看模式"
            mode_pick=$(rand_int 1 4)
            swipe_human "$mode_pick"
            short_pause=$(rand_int 1 3)
            sleep "$short_pause"
            ;;
    esac

    # 随机等待（WAIT_MIN~WAIT_MAX秒）
    rand_wait=$(random_wait)
    echo "⏳ 等待${rand_wait}秒"
    sleep "$rand_wait"

    # 每3-8轮添加一次长阅读停顿
    read_pause_counter=$((read_pause_counter + 1))
    pause_threshold=$(rand_int 3 8)
    if [ $read_pause_counter -ge $pause_threshold ]; then
        pause_time=$(rand_int 1 3)
        echo "📖 阅读停顿${pause_time}秒"
        sleep "$pause_time"
        read_pause_counter=0
    fi

    task1_count=$(expr $task1_count + 1)
done

# 记录滑动结束时间并计算总耗时
swipe_end_ts=$(date +%s)
swipe_total=$((swipe_end_ts - swipe_start_ts))

echo ""
echo "✅ 任务1执行完成"
echo "⏱️  滑动总耗时：${swipe_total}秒"
echo "====================================="


# =============== 任务3执行逻辑 ===============





# =============== 结束日志 =============== #
echo ""
echo "**************************"
echo "🎉 全部脚本执行完成！"
echo "📱 屏幕分辨率：${DEVICE_WIDTH}px × ${DEVICE_HEIGHT}px"
echo "⏱️  滑动总耗时：${swipe_total}秒"
echo "**************************"
echo ""
mode_pick=$(rand_int 1 4)
swipe_human "$mode_pick"

exit 0
