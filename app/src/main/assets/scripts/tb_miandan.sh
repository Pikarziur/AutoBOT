#!/system/bin/sh

# 编写脚本注意事项:
    # 行尾序列必须为LF
    # 安卓原生的 /system/bin/sh 不支持 for ((i=1; i<=LOOP_TIMES; i++))，安卓 shell 识别不了，所以必须避开这种写法



# =============== 固定配置 ===============
PRE_WAIT=10
BASE_WIDTH=1200 
BASE_HEIGHT=2608
WAIT_MIN=3
WAIT_MAX=5

# =============== 可变配置 ===============
WaitTime=3               # 延迟时间（秒）
Task1Repeat=20          # 任务1循环次数（滑动组数）

Task1Btn=(965 1820)     # 任务1按钮坐标
Task2Btn=(965 1070)     # 任务2按钮坐标
Task3Btn=(965 1580)     # 任务3按钮坐标

# ========================================


# =============== 固定函数 ===============
# 获取设备分辨率并计算适配参数
DEVICE_WIDTH=$(wm size | awk '{print $3}' | cut -d'x' -f1)
DEVICE_HEIGHT=$(wm size | awk '{print $3}' | cut -d'x' -f2)

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
    local x=$(scale_x $1)
    local y=$(scale_y $2)
    echo "$x $y"
}


#  设备中央点
CENTER_X=$((DEVICE_WIDTH / 2))
CENTER_Y=$((DEVICE_HEIGHT / 2))


# 上滑函数（微间断持续15秒）- 已弃用，保留兼容
swipe_down_15s() {
    local start_y=$((CENTER_Y + 400))
    local end_y=$((CENTER_Y + 300))
    local duration=200
    local end_ts=$(( $(date +%s) + 15 ))
    local swipe_x=$((CENTER_X - 200))
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
    local start_y=$((CENTER_Y + 300))
    local end_y=$((CENTER_Y + 500))
    local duration=200
    local end_ts=$(( $(date +%s) + 15 ))
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
    local seed=$(_get_seed)
    local range=$((max - min + 1))
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
    local current=$(date +%s)
    echo $((current - start))
}

# ============ 模拟下滑（4种拟人滑动模式） ============
# 说明：模拟真人浏览时的下滑手势（手指上滑使内容下滚）
#   模式1：快滑 - 短时间内滑动1/4屏幕垂直高度
#   模式2：慢滑 - 长时间内滑动1/4屏幕垂直高度
#   模式3：抛物线右上 - 3段倾斜滑动模拟抛物线，2次100ms停顿
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

    case $mode in
        1)
            # 模式1：快滑（短时150-250ms，上滑1/4屏幕高度）
            local dur=$(rand_int 150 250)
            local sy=$(rand_int $sy_min $sy_max)
            local ey=$((sy - quarter_h))
            local sx=$((CENTER_X + $(rand_int -100 100)))
            local ex=$((sx + $(rand_int -50 50)))
            # X边界保护
            [ $sx -lt $x_min ] && sx=$x_min
            [ $sx -gt $x_max ] && sx=$x_max
            [ $ex -lt $x_min ] && ex=$x_min
            [ $ex -gt $x_max ] && ex=$x_max
            input swipe "$sx" "$sy" "$ex" "$ey" "$dur"
            ;;
        2)
            # 模式2：慢滑（长时800-1500ms，上滑1/4屏幕高度）
            local dur=$(rand_int 800 1500)
            local sy=$(rand_int $sy_min $sy_max)
            local ey=$((sy - quarter_h))
            local sx=$((CENTER_X + $(rand_int -100 100)))
            local ex=$((sx + $(rand_int -50 50)))
            # X边界保护
            [ $sx -lt $x_min ] && sx=$x_min
            [ $sx -gt $x_max ] && sx=$x_max
            [ $ex -lt $x_min ] && ex=$x_min
            [ $ex -gt $x_max ] && ex=$x_max
            input swipe "$sx" "$sy" "$ex" "$ey" "$dur"
            ;;
        3)
            # 模式3：抛物线右上（3段倾斜滑动，2次100ms停顿）
            # 整体方向：左下 → 右上
            local sx=$((CENTER_X - $(rand_int 100 200)))
            [ $sx -lt $x_min ] && sx=$x_min
            local sy=$(rand_int $sy_min $sy_max)
            local ex=$((CENTER_X + $(rand_int 100 200)))
            [ $ex -gt $x_max ] && ex=$x_max
            local ey=$((sy - quarter_h))
            # 3段中间点（Y上凸模拟抛物线弧度）
            local m1_x=$(( sx + (ex - sx) / 3 ))
            local m1_y=$(( sy + (ey - sy) / 3 - $(rand_int 40 100) ))
            local m2_x=$(( sx + (ex - sx) * 2 / 3 ))
            local m2_y=$(( sy + (ey - sy) * 2 / 3 - $(rand_int 30 80) ))
            local seg_dur=$(rand_int 150 300)
            input swipe "$sx" "$sy" "$m1_x" "$m1_y" "$seg_dur"
            sleep 0.1
            input swipe "$m1_x" "$m1_y" "$m2_x" "$m2_y" "$seg_dur"
            sleep 0.1
            input swipe "$m2_x" "$m2_y" "$ex" "$ey" "$seg_dur"
            ;;
        4)
            # 模式4：抛物线左上（模式3的镜像方向）
            # 整体方向：右下 → 左上
            local sx=$((CENTER_X + $(rand_int 100 200)))
            [ $sx -gt $x_max ] && sx=$x_max
            local sy=$(rand_int $sy_min $sy_max)
            local ex=$((CENTER_X - $(rand_int 100 200)))
            [ $ex -lt $x_min ] && ex=$x_min
            local ey=$((sy - quarter_h))
            local m1_x=$(( sx + (ex - sx) / 3 ))
            local m1_y=$(( sy + (ey - sy) / 3 - $(rand_int 40 100) ))
            local m2_x=$(( sx + (ex - sx) * 2 / 3 ))
            local m2_y=$(( sy + (ey - sy) * 2 / 3 - $(rand_int 30 80) ))
            local seg_dur=$(rand_int 150 300)
            input swipe "$sx" "$sy" "$m1_x" "$m1_y" "$seg_dur"
            sleep 0.1
            input swipe "$m1_x" "$m1_y" "$m2_x" "$m2_y" "$seg_dur"
            sleep 0.1
            input swipe "$m2_x" "$m2_y" "$ex" "$ey" "$seg_dur"
            ;;
    esac
}

# 生成1-N的随机排列（Fisher-Yates洗牌，兼容Android sh）
rand_perm() {
    local n=$1
    local seed=$(_get_seed)
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
    local order=$(rand_perm 4)
    echo "🔀 本组滑动顺序：${order}"
    local i=1
    for mode in $order; do
        swipe_human "$mode"
        # 模式之间停顿（WAIT_MIN~WAIT_MAX秒），最后一次后不停顿由外层等待处理
        if [ $i -lt 4 ]; then
            local gap=$(random_wait)
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
            sleep $(rand_int 2 4)
            ;;
        10)
            # 停顿查看：只滑动1次（随机模式）+ 长停顿
            echo "📲 停顿查看模式"
            swipe_human $(rand_int 1 4)
            sleep $(rand_int 1 3)
            ;;
    esac
    
    # 随机等待（0-5秒）
    rand_wait=$(random_wait)
    echo "⏳ 等待${rand_wait}秒"
    sleep "$rand_wait"
    
    # 每3-8轮添加一次长阅读停顿
    read_pause_counter=$((read_pause_counter + 1))
    if [ $read_pause_counter -ge $(rand_int 3 8) ]; then
        pause_time=$(rand_int 1 3)
        echo "📖 阅读停顿${pause_time}秒"
        sleep $pause_time
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
swipe_human $(rand_int 1 4)

exit 0