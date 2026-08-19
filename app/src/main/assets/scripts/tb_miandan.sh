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
Task1Repeat=300          # 任务1循环次数（默认50）

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

# ============ 贝塞尔曲线弧线滑动（模拟真人弧形轨迹） ============
# 原理：用二次贝塞尔曲线生成弧形轨迹，分4段执行
# 每段时长不同（慢→快→快→慢），模拟真人手指加减速
# 控制点在滑动方向垂直偏移，产生自然弧度
human_swipe() {
    local sx=$1 sy=$2 ex=$3 ey=$4
    local total_dur=${5:-800}

    # 坐标边界保护
    [ $sx -lt 0 ] && sx=0
    [ $sy -lt 0 ] && sy=0
    [ $ex -lt 0 ] && ex=0
    [ $ey -lt 0 ] && ey=0
    [ $sx -ge $DEVICE_WIDTH ] && sx=$((DEVICE_WIDTH - 1))
    [ $sy -ge $DEVICE_HEIGHT ] && sy=$((DEVICE_HEIGHT - 1))
    [ $ex -ge $DEVICE_WIDTH ] && ex=$((DEVICE_WIDTH - 1))
    [ $ey -ge $DEVICE_HEIGHT ] && ey=$((DEVICE_HEIGHT - 1))

    # 贝塞尔曲线控制点：在滑动方向垂直偏移，弧度大小和方向随机
    local mid_x=$(( (sx + ex) / 2 ))
    local mid_y=$(( (sy + ey) / 2 ))
    local dx=$((ex - sx))
    local dy=$((ey - sy))
    local curve_off=$(rand_int 60 200)
    [ $(rand_int 0 1) -eq 0 ] && curve_off=-$curve_off

    # 用awk一次性计算控制点和5个贝塞尔轨迹点（4段）
    # t值非均匀分布：0, 0.2, 0.45, 0.7, 1.0 → 模拟先慢后快再慢
    local segs=$(awk -v sx="$sx" -v sy="$sy" -v mx="$mid_x" -v my="$mid_y" \
                    -v dx="$dx" -v dy="$dy" -v off="$curve_off" \
                    -v ex="$ex" -v ey="$ey" 'BEGIN{
        # 计算控制点（垂直于滑动方向偏移）
        L = sqrt(dx*dx + dy*dy)
        if (L < 1) L = 1
        cx = mx + (-dy/L) * off
        cy = my + (dx/L) * off
        # 生成5个贝塞尔曲线点
        split("0.0 0.2 0.45 0.7 1.0", ts, " ")
        for (i = 1; i <= 5; i++) {
            t = ts[i]; omt = 1 - t
            x = omt*omt*sx + 2*omt*t*cx + t*t*ex
            y = omt*omt*sy + 2*omt*t*cy + t*t*ey
            printf "%d %d ", int(x), int(y)
        }
    }')

    set -- $segs
    local x1=$1 y1=$2 x2=$3 y2=$4 x3=$5 y3=$6 x4=$7 y4=$8 x5=$9 y5=${10}

    # 各段时长（慢启动→快速→快速→慢停，模拟手指加减速）
    local d1=$(( total_dur * 30 / 100 ))
    local d2=$(( total_dur * 20 / 100 ))
    local d3=$(( total_dur * 20 / 100 ))
    local d4=$(( total_dur * 30 / 100 ))
    [ $d1 -lt 50 ] && d1=50
    [ $d2 -lt 50 ] && d2=50
    [ $d3 -lt 50 ] && d3=50
    [ $d4 -lt 50 ] && d4=50

    # 执行4段弧线滑动
    input swipe "$x1" "$y1" "$x2" "$y2" "$d1"
    input swipe "$x2" "$y2" "$x3" "$y3" "$d2"
    input swipe "$x3" "$y3" "$x4" "$y4" "$d3"
    input swipe "$x4" "$y4" "$x5" "$y5" "$d4"
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

while [ $task1_count -le $Task1Repeat ]; do
    echo ""
    echo "🔄 任务1第${task1_count}/${Task1Repeat}次执行"
    
    # 随机决定本轮行为类型
    behavior_type=$(rand_int 1 10)
    
    case $behavior_type in
        1)
            # 类型1: 快速浏览（只滑动1-2次）
            echo "📲 快速浏览模式"
            swipe_times=$(rand_int 1 2)
            swipe_i=1
            while [ $swipe_i -le $swipe_times ]; do
                # 随机滑动
                dir=$(rand_int 1 3)
                case $dir in
                    1) # 向上滑动
                        sx=$((CENTER_X + $(rand_int -100 100)))
                        sy=$((CENTER_Y + $(rand_int 100 400)))
                        ex=$((sx + $(rand_int -150 150)))
                        ey=$((sy - $(rand_int 200 400)))
                        ;;
                    2) # 向下滑动
                        sx=$((CENTER_X + $(rand_int -100 100)))
                        sy=$((CENTER_Y - $(rand_int 0 100)))
                        ex=$((sx + $(rand_int -150 150)))
                        ey=$((sy + $(rand_int 200 400)))
                        ;;
                    3) # 斜向滑动
                        sx=$((CENTER_X + $(rand_int -150 150)))
                        sy=$((CENTER_Y + $(rand_int 50 300)))
                        ex=$((sx + $(rand_int -200 200)))
                        ey=$((sy + $(rand_int -250 250)))
                        ;;
                esac
                dur=$(rand_int 300 800)
                human_swipe "$sx" "$sy" "$ex" "$ey" "$dur"
                sleep $(rand_int 0 1)
                swipe_i=$((swipe_i + 1))
            done
            ;;
            
        2|3)
            # 类型2-3: 正常浏览（持续滑动1-3秒）
            browse_time=$(rand_int 1 3)
            echo "📲 正常浏览模式（持续${browse_time}秒）"
            start_ts=$(date +%s)
            swipe_count=0
            
            while [ $(( $(date +%s) - start_ts )) -lt $browse_time ]; do
                # 随机方向
                dir=$(rand_int 1 3)
                case $dir in
                    1) # 向上滑动
                        sx=$((CENTER_X + $(rand_int -120 120)))
                        sy=$((CENTER_Y + $(rand_int 150 450)))
                        ex=$((sx + $(rand_int -180 180)))
                        ey=$((sy - $(rand_int 250 450)))
                        ;;
                    2) # 向下滑动
                        sx=$((CENTER_X + $(rand_int -120 120)))
                        sy=$((CENTER_Y - $(rand_int 50 150)))
                        ex=$((sx + $(rand_int -180 180)))
                        ey=$((sy + $(rand_int 250 450)))
                        ;;
                    3) # 斜向滑动
                        sx=$((CENTER_X + $(rand_int -180 180)))
                        sy=$((CENTER_Y + $(rand_int 100 350)))
                        ex=$((sx + $(rand_int -250 250)))
                        ey=$((sy + $(rand_int -300 300)))
                        ;;
                esac
                dur=$(rand_int 400 1000)
                human_swipe "$sx" "$sy" "$ex" "$ey" "$dur"
                swipe_count=$((swipe_count + 1))
                
                # 滑动间停顿
                if [ $(( $(date +%s) - start_ts )) -lt $browse_time ]; then
                    sleep $(rand_int 0 1)
                fi
            done
            echo "   滑动${swipe_count}次"
            ;;
            
        4|5|6|7)
            # 类型4-7: 深度阅读（持续滑动2-4秒）
            read_time=$(rand_int 2 4)
            echo "📲 深度阅读模式（持续${read_time}秒）"
            start_ts=$(date +%s)
            swipe_count=0
            
            while [ $(( $(date +%s) - start_ts )) -lt $read_time ]; do
                # 更多向上滑动（看内容更多）
                dir_pick=$(rand_int 1 10)
                if [ $dir_pick -le 6 ]; then
                    # 向上滑动（60%概率）
                    sx=$((CENTER_X + $(rand_int -100 100)))
                    sy=$((CENTER_Y + $(rand_int 200 500)))
                    ex=$((sx + $(rand_int -150 150)))
                    ey=$((sy - $(rand_int 300 500)))
                elif [ $dir_pick -le 8 ]; then
                    # 向下滑动（20%概率）
                    sx=$((CENTER_X + $(rand_int -100 100)))
                    sy=$((CENTER_Y - $(rand_int 0 100)))
                    ex=$((sx + $(rand_int -150 150)))
                    ey=$((sy + $(rand_int 300 500)))
                else
                    # 偶尔回滑（20%概率）
                    sx=$((CENTER_X + $(rand_int -80 80)))
                    sy=$((CENTER_Y + $(rand_int 100 250)))
                    ex=$((sx + $(rand_int -100 100)))
                    ey=$((sy - $(rand_int 150 300)))
                fi
                dur=$(rand_int 500 1200)
                human_swipe "$sx" "$sy" "$ex" "$ey" "$dur"
                swipe_count=$((swipe_count + 1))
                
                # 滑动间停顿 - 阅读时停顿更长
                if [ $(( $(date +%s) - start_ts )) -lt $read_time ]; then
                    pause=$(rand_int 0 2)
                    sleep $pause
                fi
            done
            echo "   滑动${swipe_count}次"
            ;;
            
        8|9|10)
            # 类型8-10: 停顿查看（偶尔的阅读停顿）
            echo "📲 停顿查看模式"
            # 缓慢滑动1次
            sx=$((CENTER_X + $(rand_int -80 80)))
            sy=$((CENTER_Y + $(rand_int 150 350)))
            ex=$((sx + $(rand_int -100 100)))
            ey=$((sy - $(rand_int 200 350)))
            dur=$(rand_int 600 1200)
            human_swipe "$sx" "$sy" "$ex" "$ey" "$dur"
            # 然后停顿1-3秒
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

echo ""
echo "✅ 任务1执行完成"
echo "====================================="


# =============== 任务3执行逻辑 ===============





# =============== 结束日志 =============== #
echo ""
echo "**************************"
echo "🎉 全部脚本执行完成！"
echo "📱 屏幕分辨率：${DEVICE_WIDTH}px × ${DEVICE_HEIGHT}px"
echo "**************************"
echo ""
human_swipe $((DEVICE_WIDTH / 2)) 0 $((DEVICE_WIDTH / 2)) $((DEVICE_HEIGHT / 2)) 300

exit 0