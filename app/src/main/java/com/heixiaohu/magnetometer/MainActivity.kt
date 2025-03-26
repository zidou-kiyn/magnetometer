package com.heixiaohu.magnetometer

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.Utils
import com.heixiaohu.magnetometer.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

class MainActivity : AppCompatActivity(), SensorEventListener, OnChartValueSelectedListener {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var magneticSensor: Sensor? = null
    
    private var isRecording = false
    private var startTime = System.currentTimeMillis()
    private val entries = ArrayList<Entry>()
    private var lineDataSet: LineDataSet? = null
    private var lineData: LineData? = null
    
    // 用于暂停和继续功能
    private var pausedTime: Long = 0
    private var totalPausedTime: Long = 0
    
    // 峰值检测参数
    private val peaks = ArrayList<Pair<Float, Long>>() // 值和时间戳
    private var lastPeakTime: Long = 0
    private var lastPeakValue: Float = 0f
    private var isAscending = true
    private var lastValue: Float = 0f
    private var thresholdValue: Float = 60.0f // 默认阈值为60.0μT
    private var initialValue: Float = 0f // 记录初始值
    private var hasInitialValue: Boolean = false // 是否已记录初始值
    
    // 单摆峰值检测参数
    private var inPeakRegion = false // 是否在峰值区域内
    private var potentialPeak: Float? = null // 潜在峰值
    private var potentialPeakTime: Long = 0 // 潜在峰值时间
    
    // 记录最大和最小磁场强度值，用于动态调整Y轴范围
    private var maxMagneticValue: Float = 100f
    private var minMagneticValue: Float = -100f
    
    // 峰值对标记 - 记录两个相关的峰值
    private data class PeakPair(
        val firstPeakTime: Float,
        val secondPeakTime: Float,
        val timeDiff: Double,
        val acceleration: Double
    )
    
    // 连续数据监测
    private val valueHistory = ArrayList<Float>() // 存储最近的值用于平滑检测
    private val historySize = 24 // 减小历史数据窗口大小
    private val initialValueSampleSize = 20 // 用于计算初始值的样本数量
    private val peakWindowSize = 3 // 减小峰值检测窗口大小
    
    // 增加用于记录峰值区域所有点的列表
    private val peakRegionValues = ArrayList<Pair<Float, Long>>() // 值和时间戳
    
    // 记录上一个峰值的方向（正偏差或负偏差）
    private var lastPeakDirection = 0 // 0: 未知, 1: 正偏差(大于初始值), -1: 负偏差(小于初始值)
    
    // 增加峰值历史记录
    private val peakHistoryList = ArrayList<PeakHistoryData>()
    
    // 单摆长度 (cm)
    private var pendulumLength: Double = 10.0
    
    // 记录模式相关变量
    private var isAutoStopMode = false
    private var targetCycleCount = 30 // 默认目标周期数
    private var completedCycleCount = 0 // 已完成的周期数
    
    // 选中的点信息
    private var selectedPointInfo: TextView? = null
    
    // 数据模型类
    data class PeakHistoryData(
        val timestamp: Long, 
        val value: Float, 
        val timeDiff: Double, 
        val acceleration: Double,
        val elapsedSeconds: Float, // 第二个峰值时间点
        val lastPeakSeconds: Float = 0f, // 第一个峰值时间点
        val lastPeakValue: Float = 0f, // 第一个峰值值
        val period: Double = 0.0 // 计算的周期
    )
    
    private val previousPeakTimes = ArrayList<Long>() // 存储峰值时间戳
    private val previousPeakValues = ArrayList<Float>() // 存储峰值值
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // 设置应用整体主题颜色
        window.statusBarColor = Color.parseColor("#336699")
        
        // 初始化传感器
        initSensor()
        
        // 安全地预加载传感器
        magneticSensor?.let {
            try {
                // 注册传感器监听器
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            } catch (e: Exception) {
                // 捕获任何可能的异常
                Toast.makeText(this, "传感器初始化失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        setupChart()
        setupUI()
        
        // 初始化阈值显示
        binding.tvThreshold.text = "当前峰值检测阈值: ${thresholdValue} μT"
        
        // 初始化单摆长度显示
        binding.tvPendulumLength.text = "单摆长度: ${pendulumLength} cm"
    }
    
    private fun initSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        if (magneticSensor == null) {
            Toast.makeText(this, "设备不支持磁力计传感器", Toast.LENGTH_SHORT).show()
            binding.btnStartStop.isEnabled = false
            binding.btnPause.isEnabled = false
        }
    }
    
    private fun setupChart() {
        val chart = binding.chart
        
        // 初始化图表
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(true)
        chart.setBackgroundColor(Color.parseColor("#F5F5F5"))
        chart.setNoDataText("点击开始记录按钮采集数据")
        chart.setNoDataTextColor(Color.parseColor("#336699"))
        
        // 设置图表边距
        chart.setExtraOffsets(10f, 10f, 10f, 10f)
        
        // 设置X轴
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.textColor = Color.BLACK
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = Color.LTGRAY
        xAxis.axisLineColor = Color.DKGRAY
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()}秒"
            }
        }
        
        // 设置左Y轴 - 动态范围
        val leftAxis = chart.axisLeft
        leftAxis.textColor = Color.BLACK
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = Color.LTGRAY
        leftAxis.axisLineColor = Color.DKGRAY
        leftAxis.setAxisMinimum(minMagneticValue) // 初始Y轴范围
        leftAxis.setAxisMaximum(maxMagneticValue)
        leftAxis.labelCount = 10
        
        // 禁用右Y轴
        chart.axisRight.isEnabled = false
        
        // 设置图表图例
        val legend = chart.legend
        legend.form = Legend.LegendForm.LINE
        legend.textColor = Color.BLACK
        legend.verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
        legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
        legend.orientation = Legend.LegendOrientation.HORIZONTAL
        legend.setDrawInside(false)
        
        // 设置点击监听
        chart.setOnChartValueSelectedListener(this)
        
        // 添加选中点信息显示
        selectedPointInfo = TextView(this)
        selectedPointInfo?.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(16, 8, 16, 8)
            }
            setBackgroundResource(R.drawable.selected_point_background)
            setPadding(16, 8, 16, 8)
            setTextColor(Color.BLACK)
            visibility = android.view.View.GONE
            textSize = 14f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        
        // 添加到图表布局中
        val chartParent = binding.chart.parent as LinearLayout
        chartParent.addView(selectedPointInfo, 1)
        
        // 创建空数据集
        createEmptyDataSet()
    }
    
    private fun createEmptyDataSet() {
        lineDataSet = LineDataSet(entries, "Z轴磁场强度 (μT)")
        lineDataSet?.apply {
            color = Color.parseColor("#336699") // 更改线条颜色
            lineWidth = 2.5f
            setDrawCircles(true) // 显示数据点
            circleRadius = 3f
            setCircleColor(Color.parseColor("#336699"))
            setDrawCircleHole(true)
            circleHoleRadius = 1.5f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f
            
            // 设置高亮
            setDrawHighlightIndicators(true)
            setHighLightColor(Color.RED)
            highlightLineWidth = 1.5f
            
            // 填充颜色
            setDrawFilled(true)
            fillColor = Color.parseColor("#80D6F9FF")
            fillAlpha = 100
        }
        
        // 创建LineData对象
        lineData = LineData(lineDataSet)
        binding.chart.data = lineData
        binding.chart.invalidate() // 刷新图表
    }
    
    private fun setupUI() {
        // 美化UI元素
        with(binding) {
            // 应用圆角和阴影给按钮
            btnStartStop.elevation = 8f
            btnPause.elevation = 8f
            btnCancel.elevation = 8f
            btnSetLength.elevation = 8f
            btnSetThreshold.elevation = 8f
            btnAutoMode.elevation = 8f
            
            // 美化卡片视图
            cardInfo.elevation = 8f
            cardChart.elevation = 8f
            cardPeaks.elevation = 8f
            
            // 添加峰值历史记录点击功能
            btnShowHistory.setOnClickListener {
                showPeakHistoryDialog()
            }
            
            // 添加设置单摆长度功能
            btnSetLength.setOnClickListener {
                showSetPendulumLengthDialog()
            }
            
            // 添加设置峰值检测阈值功能
            btnSetThreshold.setOnClickListener {
                showSetThresholdDialog()
            }
            
            // 添加自动停止模式切换功能
            btnAutoMode.setOnClickListener {
                showAutoModeSettingDialog()
            }
            
            // 更新自动模式按钮文本
            updateAutoModeButtonText()
        }
        
        // 设置开始/停止按钮
        binding.btnStartStop.setOnClickListener {
            if (!isRecording) {
                // 开始记录
                startRecording()
                isRecording = true
            }
        }
        
        // 设置停止按钮
        binding.btnCancel.setOnClickListener {
            if (isRecording) {
                // 停止记录
                stopRecording()
            }
        }
        
        // 设置暂停按钮
        binding.btnPause.setOnClickListener {
            if (isRecording) {
                // 暂停记录
                sensorManager.unregisterListener(this)
                binding.btnPause.text = "继续记录"
                binding.btnPause.setBackgroundTintList(getColorStateList(android.R.color.holo_blue_dark))
                isRecording = false
                
                // 记录暂停时间
                pausedTime = System.currentTimeMillis()
                
                // 显示提示
                Toast.makeText(this, "暂停记录数据", Toast.LENGTH_SHORT).show()
            } else {
                // 计算暂停的总时间
                if (pausedTime > 0) {
                    totalPausedTime += System.currentTimeMillis() - pausedTime
                }
                
                // 继续记录
                continueRecording()
                binding.btnPause.text = "暂停记录"
                binding.btnPause.setBackgroundTintList(getColorStateList(android.R.color.holo_orange_dark))
                isRecording = true
            }
        }
    }
    
    // 更新自动模式按钮文本
    private fun updateAutoModeButtonText() {
        if (isAutoStopMode) {
            binding.btnAutoMode.text = "自动停止: ${targetCycleCount}周期"
        } else {
            binding.btnAutoMode.text = "手动模式"
        }
    }
    
    // 显示自动模式设置对话框
    private fun showAutoModeSettingDialog() {
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
        dialogBuilder.setTitle("记录模式设置")
        
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(30, 30, 30, 30)
        
        // 创建单选按钮组
        val radioGroup = android.widget.RadioGroup(this)
        radioGroup.orientation = android.widget.RadioGroup.VERTICAL
        
        val manualModeButton = android.widget.RadioButton(this)
        manualModeButton.id = android.view.View.generateViewId()
        manualModeButton.text = "手动模式（一直记录直到手动停止）"
        manualModeButton.isChecked = !isAutoStopMode
        
        val autoModeButton = android.widget.RadioButton(this)
        autoModeButton.id = android.view.View.generateViewId()
        autoModeButton.text = "自动停止模式（记录指定周期数后自动停止）"
        autoModeButton.isChecked = isAutoStopMode
        
        radioGroup.addView(manualModeButton)
        radioGroup.addView(autoModeButton)
        
        // 创建输入框和说明文本
        val cycleCountLayout = LinearLayout(this)
        cycleCountLayout.orientation = LinearLayout.HORIZONTAL
        cycleCountLayout.gravity = Gravity.CENTER_VERTICAL
        
        val cycleCountLabel = TextView(this)
        cycleCountLabel.text = "周期数量: "
        cycleCountLabel.setPadding(0, 20, 0, 0)
        
        val cycleCountInput = EditText(this)
        cycleCountInput.inputType = InputType.TYPE_CLASS_NUMBER
        cycleCountInput.setText(targetCycleCount.toString())
        
        // 设置布局权重
        val params = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        )
        cycleCountInput.layoutParams = params
        
        cycleCountLayout.addView(cycleCountLabel)
        cycleCountLayout.addView(cycleCountInput)
        
        // 添加布局到主布局
        layout.addView(radioGroup)
        layout.addView(cycleCountLayout)
        
        // 根据选择的模式启用/禁用周期输入
        cycleCountInput.isEnabled = isAutoStopMode
        
        // 监听单选按钮的变化
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            cycleCountInput.isEnabled = (checkedId == autoModeButton.id)
        }
        
        dialogBuilder.setView(layout)
        
        dialogBuilder.setPositiveButton("确定") { dialog, _ ->
            // 获取选中的模式
            isAutoStopMode = (radioGroup.checkedRadioButtonId == autoModeButton.id)
            
            // 如果是自动模式，获取目标周期数
            if (isAutoStopMode) {
                try {
                    val count = cycleCountInput.text.toString().toInt()
                    if (count > 0) {
                        targetCycleCount = count
                    } else {
                        Toast.makeText(this, "周期数必须大于0，已设为默认值30", Toast.LENGTH_SHORT).show()
                        targetCycleCount = 30
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "无效的周期数，已设为默认值30", Toast.LENGTH_SHORT).show()
                    targetCycleCount = 30
                }
            }
            
            // 更新按钮文本
            updateAutoModeButtonText()
            
            // 提示用户当前模式
            if (isAutoStopMode) {
                Toast.makeText(this, "已设置为自动停止模式，将在记录${targetCycleCount}个周期后自动停止", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "已设置为手动模式，将一直记录直到手动停止", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialogBuilder.setNegativeButton("取消") { dialog, _ -> dialog.cancel() }
        
        dialogBuilder.show()
    }
    
    private fun resetData() {
        // 重置所有数据和计时器
        entries.clear()
        peaks.clear()
        valueHistory.clear()
        peakHistoryList.clear()
        previousPeakTimes.clear()
        previousPeakValues.clear()
        lastPeakTime = 0
        lastPeakValue = 0f
        isAscending = true
        lastValue = 0f
        pausedTime = 0
        totalPausedTime = 0
        completedCycleCount = 0
        
        // 重置磁场强度范围
        maxMagneticValue = 100f
        minMagneticValue = -100f
        
        // 更新图表Y轴范围
        val leftAxis = binding.chart.axisLeft
        leftAxis.setAxisMinimum(minMagneticValue)
        leftAxis.setAxisMaximum(maxMagneticValue)
        
        // 重置开始时间
        startTime = System.currentTimeMillis()
        
        // 更新UI
        binding.tvZValue.text = "Z轴: 0.0 μT"
        binding.tvPeakInfo.text = "平均周期: - 秒    平均加速度: - m/s²"
        
        // 隐藏选中点信息
        selectedPointInfo?.visibility = android.view.View.GONE
        
        // 重置按钮状态
        binding.btnPause.text = "暂停记录"
        binding.btnPause.setBackgroundTintList(getColorStateList(android.R.color.holo_orange_dark))
        
        // 刷新图表
        createEmptyDataSet()
        binding.chart.notifyDataSetChanged()
        binding.chart.invalidate()
    }
    
    private fun startRecording() {
        // 清除之前的数据
        resetData()
        // 重置初始值记录
        hasInitialValue = false
        initialValue = 0f
        // 显示提示
        Toast.makeText(this, "开始记录数据", Toast.LENGTH_SHORT).show()
        
        // 隐藏初始按钮，显示操作按钮
        binding.btnAutoMode.visibility = android.view.View.GONE
        binding.btnStartStop.visibility = android.view.View.GONE
        binding.settingsButtonsLayout.visibility = android.view.View.GONE
        
        // 获取LinearLayout并设置为可见
        val recordingButtonsRow = findViewById<LinearLayout>(R.id.recordingButtonsRow)
        recordingButtonsRow.visibility = android.view.View.VISIBLE
        
        // 注册传感器监听器
        try {
            magneticSensor?.let {
                // 先取消之前的注册，避免重复
                sensorManager.unregisterListener(this)
                // 重新注册
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "传感器注册失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopRecording() {
        try {
            sensorManager.unregisterListener(this)
            // 显示提示
            Toast.makeText(this, "停止记录数据", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "停止记录失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        
        // 重置暂停按钮文本和颜色
        binding.btnPause.text = "暂停记录"
        binding.btnPause.setBackgroundTintList(getColorStateList(android.R.color.holo_orange_dark))
        
        // 隐藏操作按钮，显示初始按钮
        val recordingButtonsRow = findViewById<LinearLayout>(R.id.recordingButtonsRow)
        recordingButtonsRow.visibility = android.view.View.GONE
        
        binding.btnAutoMode.visibility = android.view.View.VISIBLE
        binding.btnStartStop.visibility = android.view.View.VISIBLE
        binding.settingsButtonsLayout.visibility = android.view.View.VISIBLE
        
        isRecording = false
        // 重置暂停时间
        pausedTime = 0
        totalPausedTime = 0
    }
    
    private fun continueRecording() {
        // 注册传感器监听器继续记录
        try {
            magneticSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            // 显示提示
            Toast.makeText(this, "继续记录数据", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "继续记录失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD && isRecording) {
            val zValue = event.values[2]
            val currentTime = System.currentTimeMillis()
            
            // 添加数据到历史记录
            addToHistory(zValue)
            
            // 记录初始值（使用前20个采样点的平均值）
            if (!hasInitialValue && valueHistory.size >= initialValueSampleSize) {
                initialValue = valueHistory.average().toFloat()
                hasInitialValue = true
                Toast.makeText(this, "初始值已记录: ${String.format("%.2f", initialValue)} μT", Toast.LENGTH_SHORT).show()
                // 添加初始值标记到图表
                addMarkerToChart(0f, initialValue, "初始值")
            }
            
            // 计算实际的经过时间，减去暂停的总时间
            val elapsedTime = (currentTime - startTime - totalPausedTime) / 1000f
            
            // 更新Z轴显示值
            binding.tvZValue.text = "Z轴: ${String.format("%.2f", zValue)} μT"
            
            // 动态调整Y轴范围
            updateAxisRange(zValue)
            
            // 添加数据到图表
            addEntry(elapsedTime, zValue)
            
            // 峰值检测
            detectPeaks(zValue, currentTime)
        }
    }
    
    private fun addEntry(time: Float, value: Float) {
        // 添加数据点到entries集合
        entries.add(Entry(time, value))
        
        // 通知数据集和图表更新
        lineDataSet?.notifyDataSetChanged()
        lineData?.notifyDataChanged()
        binding.chart.notifyDataSetChanged()
        
        // 设置可见范围，显示最近15秒的数据
        binding.chart.setVisibleXRangeMaximum(15f)
        
        // 移动视图到最新数据
        binding.chart.moveViewToX(time)
        
        // 刷新图表
        binding.chart.invalidate()
    }
    
    private fun addToHistory(value: Float) {
        valueHistory.add(value)
        if (valueHistory.size > historySize) {
            valueHistory.removeAt(0)
        }
    }
    
    private fun getSmoothedValue(): Float {
        if (valueHistory.isEmpty()) return 0f
        
        // 使用加权平均，越近的数据权重越大
        var sum = 0f
        var weightSum = 0f
        
        for (i in valueHistory.indices) {
            val weight = i + 1.0f // 权重从1递增
            sum += valueHistory[i] * weight
            weightSum += weight
        }
        
        return if (weightSum > 0) sum / weightSum else 0f
    }
    
    private fun detectPeaks(value: Float, timestamp: Long) {
        // 使用原始值进行峰值检测，不再使用平滑值
        val currentValue = value
        
        // 只有在已记录初始值时才进行峰值检测
        if (hasInitialValue) {
            // 计算相对于初始值的偏差
            val deviation = abs(currentValue - initialValue)
            
            // 检测进入峰值区域 - 只有偏差超过阈值才进入峰值区域
            if (!inPeakRegion && deviation > thresholdValue) {
                inPeakRegion = true
                // 清空峰值区域记录
                peakRegionValues.clear()
                // 添加当前点到峰值区域记录
                peakRegionValues.add(Pair(currentValue, timestamp))
                
                // 调试信息
                if (isRecording) {
                    Log.d("PeakDetection", "进入峰值区域: 值=$currentValue, 偏差=$deviation")
                }
            } 
            // 在峰值区域内，只记录偏差超过阈值的点
            else if (inPeakRegion) {
                // 只添加偏差超过阈值的点到峰值区域记录
                if (deviation > thresholdValue) {
                    peakRegionValues.add(Pair(currentValue, timestamp))
                    
                    // 调试信息输出更详细的信息便于排查
                    if (isRecording) {
                        Log.d("PeakDetection", "峰值区域内点: 值=$currentValue, 偏差=$deviation, 初始值=$initialValue")
                    }
                }
                
                // 回到接近初始值区域，找出最大偏差点并记录峰值
                if (deviation < thresholdValue * 0.5) {  // 使用较小阈值确认回归
                    inPeakRegion = false
                    
                    // 寻找最大偏差点
                    var maxDeviationValue = 0f
                    var maxDeviationTime = 0L
                    var maxDeviation = 0f
                    
                    for (pair in peakRegionValues) {
                        val pointValue = pair.first
                        val pointTime = pair.second
                        val pointDeviation = abs(pointValue - initialValue)
                        
                        if (pointDeviation > maxDeviation) {
                            maxDeviation = pointDeviation
                            maxDeviationValue = pointValue
                            maxDeviationTime = pointTime
                        }
                    }
                    
                    // 如果找到了有效的最大偏差点
                    if (maxDeviation > 0 && isValidPeak(maxDeviationValue, maxDeviationTime)) {
                        // 确定这个峰值的方向（大于或小于初始值）
                        val currentDirection = if (maxDeviationValue > initialValue) 1 else -1
                        
                        // 记录找到的最大偏差点作为峰值
                        recordPeak(maxDeviationValue, maxDeviationTime, currentDirection)
                        
                        // 调试信息
                        if (isRecording) {
                            Log.d("PeakDetection", "记录峰值: 值=$maxDeviationValue, 时间=${maxDeviationTime}, 偏差=$maxDeviation, 方向=$currentDirection, 峰值区域点数=${peakRegionValues.size}")
                        }
                    }
                    
                    // 清空峰值区域记录
                    peakRegionValues.clear()
                }
            }
        }
        
        // 更新最后的值
        lastValue = currentValue
    }
    
    // 简化的局部最大值判断函数 - 不再需要此方法，但保留以避免其他地方引用错误
    private fun isSimpleLocalMaximum(value: Float): Boolean {
        return true
    }
    
    // 简化的局部最小值判断函数 - 不再需要此方法，但保留以避免其他地方引用错误
    private fun isSimpleLocalMinimum(value: Float): Boolean {
        return true
    }
    
    // 验证峰值是否有效 - 只验证时间间隔
    private fun isValidPeak(value: Float, timestamp: Long): Boolean {
        // 检查时间间隔 - 单摆周期通常不会太短
        if (lastPeakTime > 0) {
            val timeDiff = timestamp - lastPeakTime
            if (timeDiff < 300) { // 最小间隔0.3秒
                return false
            }
        }
        
        // 对于单摆应用，不再验证相邻峰值的方向关系
        return true
    }
    
    // 记录峰值方法
    private fun recordPeak(peakValue: Float, timestamp: Long, direction: Int) {
        // 添加峰值标记到图表
        val elapsedSeconds = (timestamp - startTime - totalPausedTime) / 1000f

        // 显示标签文本，仅第一个峰值显示标签文本
        val labelText = if (peaks.isEmpty()) "峰值" else ""

        // 添加峰值标记到图表
        addMarkerToChart(elapsedSeconds, peakValue, labelText)
        
        // 将当前峰值添加到历史记录
        previousPeakTimes.add(timestamp)
        previousPeakValues.add(peakValue)
        peaks.add(Pair(peakValue, timestamp))
        
        // 计算当前峰值的序号（从0开始）
        val currentPeakIndex = peaks.size - 1
        
        // 如果是第三个或以后的峰值（索引从0开始，所以索引>=2表示第三个或之后的峰值）
        if (currentPeakIndex >= 2 && currentPeakIndex % 2 == 0) {
            // 获取两个峰值前的峰值索引
            val correspondingPeakIndex = currentPeakIndex - 2
            
            // 获取对应峰值的时间戳和值
            val correspondingPeakTime = previousPeakTimes[correspondingPeakIndex]
            val correspondingPeakValue = previousPeakValues[correspondingPeakIndex]
            
            val timeDiffMs = timestamp - correspondingPeakTime
            val timeDiffSeconds = timeDiffMs / 1000.0
            
            // 计算自开始记录以来的秒数，减去暂停的总时间
            val elapsedSeconds = (timestamp - startTime - totalPausedTime) / 1000f
            val correspondingElapsedSeconds = (correspondingPeakTime - startTime - totalPausedTime) / 1000f
            
            // 计算周期 - 直接使用当前峰值与对应峰值的时间差作为全周期
            val period = timeDiffSeconds
            
            // 注意：将cm转换为m进行计算
            val pendulumLengthInMeters = pendulumLength / 100.0
            val acceleration = 4 * Math.PI.pow(2.0) * pendulumLengthInMeters / period.pow(2.0)
            
            // 添加到历史记录
            peakHistoryList.add(
                PeakHistoryData(
                    timestamp,
                    peakValue,
                    timeDiffSeconds,
                    acceleration,
                    elapsedSeconds,
                    correspondingElapsedSeconds,
                    correspondingPeakValue,
                    period // 添加周期到历史记录
                )
            )
            
            // 增加已完成周期计数
            completedCycleCount++
            
            // 计算平均值并显示
            updateAverageValues()
            
            // 调试信息
            Log.d("PeakDetection", "计算结果: 峰值#${currentPeakIndex+1} 与 峰值#${correspondingPeakIndex+1} 之间, " + 
                    "时间间隔=${String.format("%.2f", timeDiffSeconds)}秒, " +
                    "周期=${String.format("%.2f", period)}秒, " +
                    "加速度=${String.format("%.4f", acceleration)}m/s², " +
                    "已完成周期数=$completedCycleCount/${if(isAutoStopMode) targetCycleCount else "∞"}")
                    
            // 自动停止模式检查
            if (isAutoStopMode && completedCycleCount >= targetCycleCount) {
                // 使用Handler在主线程上停止记录
                android.os.Handler(mainLooper).post {
                    if (isRecording) {
                        stopRecording()
                        Toast.makeText(
                            this, 
                            "已记录完成${targetCycleCount}个周期，自动停止记录", 
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        
        // 更新最后的峰值记录
        lastPeakTime = timestamp
        lastPeakValue = peakValue
        lastPeakDirection = direction
    }
    
    // 计算并更新平均值显示
    private fun updateAverageValues() {
        if (peakHistoryList.isEmpty()) return
        
        var totalTimeDiff = 0.0
        var totalAcceleration = 0.0
        
        peakHistoryList.forEach { 
            totalTimeDiff += it.timeDiff
            totalAcceleration += it.acceleration
        }
        
        val avgTimeDiff = totalTimeDiff / peakHistoryList.size
        val avgAcceleration = totalAcceleration / peakHistoryList.size
        
        // 更新主界面上的峰值信息为平均值
        binding.tvPeakInfo.text = "平均周期: ${String.format("%.2f", avgTimeDiff)} 秒    " +
                "平均加速度: ${String.format("%.4f", avgAcceleration)} m/s²"
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 传感器精度变化时调用
    }
    
    override fun onResume() {
        super.onResume()
        if (isRecording) {
            magneticSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        if (isRecording) {
            sensorManager.unregisterListener(this)
        }
    }
    
    // 显示设置单摆长度对话框
    private fun showSetPendulumLengthDialog() {
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
        dialogBuilder.setTitle("设置单摆长度")
        
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(pendulumLength.toString())
        
        dialogBuilder.setView(input)
        
        dialogBuilder.setPositiveButton("确定") { dialog, _ ->
            try {
                val newLength = input.text.toString().toDouble()
                if (newLength > 0) {
                    pendulumLength = newLength
                    binding.tvPendulumLength.text = "单摆长度: ${pendulumLength} cm"
                    
                    // 重新计算所有历史记录的加速度
                    recalculateAccelerationValues()
                    
                    Toast.makeText(this, "单摆长度已设置为 ${pendulumLength} cm", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "请输入大于0的数值", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "请输入有效的数值", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialogBuilder.setNegativeButton("取消") { dialog, _ -> dialog.cancel() }
        
        dialogBuilder.show()
    }
    
    // 显示设置峰值检测阈值对话框
    private fun showSetThresholdDialog() {
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
        dialogBuilder.setTitle("设置峰值检测阈值")
        
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(thresholdValue.toString())
        
        val hintText = TextView(this)
        hintText.text = "提示：阈值表示相对于初始值的偏差阈值。\n" +
                "值越大，检测到的峰值越少但质量更高；\n" +
                "值越小，检测到的峰值越多但可能包含噪声。\n\n" +
                "建议值：40-60 μT"
        hintText.setPadding(16, 16, 16, 16)
        
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(input)
        layout.addView(hintText)
        
        dialogBuilder.setView(layout)
        
        dialogBuilder.setPositiveButton("确定") { dialog, _ ->
            try {
                val newThreshold = input.text.toString().toFloat()
                if (newThreshold > 0) {
                    thresholdValue = newThreshold
                    binding.tvThreshold.text = "当前峰值检测阈值: ${thresholdValue} μT"
                    
                    // 提供适当的反馈
                    if (thresholdValue < 20) {
                        Toast.makeText(this, "峰值检测阈值已设置为 ${thresholdValue} μT (较低，可能包含较多噪声)", Toast.LENGTH_LONG).show()
                    } else if (thresholdValue > 80) {
                        Toast.makeText(this, "峰值检测阈值已设置为 ${thresholdValue} μT (较高，可能错过部分峰值)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "峰值检测阈值已设置为 ${thresholdValue} μT", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "请输入大于0的数值", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "请输入有效的数值", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialogBuilder.setNegativeButton("取消") { dialog, _ -> dialog.cancel() }
        
        dialogBuilder.show()
    }
    
    // 重新计算所有加速度值
    private fun recalculateAccelerationValues() {
        if (peakHistoryList.isEmpty()) return
        
        val updatedList = ArrayList<PeakHistoryData>()
        
        for (data in peakHistoryList) {
            // 使用存储的周期值进行计算
            val period = data.period
            
            // 注意：将cm转换为m进行计算
            val pendulumLengthInMeters = pendulumLength / 100.0
            val acceleration = 4 * Math.PI.pow(2.0) * pendulumLengthInMeters / period.pow(2.0)
            
            updatedList.add(
                PeakHistoryData(
                    data.timestamp,
                    data.value,
                    data.timeDiff,
                    acceleration,
                    data.elapsedSeconds,
                    data.lastPeakSeconds,
                    data.lastPeakValue,
                    data.period
                )
            )
        }
        
        peakHistoryList.clear()
        peakHistoryList.addAll(updatedList)
        
        // 更新平均值显示
        updateAverageValues()
    }
    
    // 动态调整Y轴范围
    private fun updateAxisRange(value: Float) {
        var needUpdate = false
        
        // 如果当前值超出了已知范围，扩展范围并留出一定余量
        if (value > maxMagneticValue) {
            maxMagneticValue = value * 1.2f
            needUpdate = true
        }
        
        if (value < minMagneticValue) {
            minMagneticValue = value * 1.2f
            needUpdate = true
        }
        
        // 如果需要更新范围
        if (needUpdate) {
            val leftAxis = binding.chart.axisLeft
            leftAxis.setAxisMinimum(minMagneticValue)
            leftAxis.setAxisMaximum(maxMagneticValue)
            binding.chart.invalidate()
        }
    }
    
    // 添加峰值历史记录对话框
    private fun showPeakHistoryDialog() {
        if (peakHistoryList.isEmpty()) {
            Toast.makeText(this, "暂无峰值历史记录", Toast.LENGTH_SHORT).show()
            return
        }
        
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
        dialogBuilder.setTitle("峰值历史记录")
        
        val historyView = layoutInflater.inflate(R.layout.dialog_peak_history, null)
        val recyclerView = historyView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewPeaks)
        
        // 设置历史记录列表
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val adapter = PeakHistoryAdapter(peakHistoryList, pendulumLength)
        recyclerView.adapter = adapter
        
        dialogBuilder.setView(historyView)
        dialogBuilder.setPositiveButton("关闭") { dialog, _ -> dialog.dismiss() }
        
        val dialog = dialogBuilder.create()
        dialog.show()
    }
    
    // 图表点击事件
    override fun onValueSelected(e: Entry?, h: Highlight?) {
        e?.let { entry ->
            val timestamp = entry.x
            val value = entry.y
            
            // 更新并显示选中点信息
            selectedPointInfo?.apply {
                text = "时间：${String.format("%.2f", timestamp)} 秒  磁场强度：${String.format("%.2f", value)} μT"
                visibility = android.view.View.VISIBLE
            }
        }
    }
    
    override fun onNothingSelected() {
        // 隐藏选中点信息
        selectedPointInfo?.visibility = android.view.View.GONE
    }
    
    // 添加标记到图表
    private fun addMarkerToChart(x: Float, y: Float, text: String) {
        // 在图表上对应位置创建一个Entry对象，配置不同颜色
        val markerEntry = Entry(x, y)
        
        // 创建一个单独的数据集用于标记
        val markerDataSet = LineDataSet(listOf(markerEntry), text)
        markerDataSet.apply {
            // 峰值点统一使用红色标记，不论是否有文本标签
            color = if (text.isEmpty() || text.contains("峰值")) Color.RED else Color.BLUE
            setCircleColor(color)
            circleRadius = 5f
            setDrawCircleHole(false)
            lineWidth = 0f // 不绘制线
            setDrawValues(!text.isEmpty()) // 只有有标签文本时才绘制值
            valueTextSize = 10f
            valueTextColor = color
            valueFormatter = object : ValueFormatter() {
                override fun getPointLabel(entry: Entry?): String {
                    return text
                }
            }
        }
        
        // 将标记数据集添加到图表
        if (binding.chart.data != null) {
            binding.chart.data.addDataSet(markerDataSet)
            binding.chart.invalidate()
        }
    }
}

// 峰值历史记录适配器
class PeakHistoryAdapter(
    private val dataList: List<MainActivity.PeakHistoryData>,
    private val pendulumLength: Double
) : androidx.recyclerview.widget.RecyclerView.Adapter<PeakHistoryAdapter.ViewHolder>() {
    
    class ViewHolder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        val tvPeakTime: android.widget.TextView = itemView.findViewById(R.id.tvPeakTime)
        val tvInterval: android.widget.TextView = itemView.findViewById(R.id.tvPeakInterval)
        val tvAcceleration: android.widget.TextView = itemView.findViewById(R.id.tvPeakAcceleration)
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peak_history, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val data = dataList[position]
        
        // 显示两个峰值时间点和值
        holder.tvPeakTime.text = "峰值对: ${String.format("%.2f", data.lastPeakSeconds)}秒(${String.format("%.2f", data.lastPeakValue)}μT) → " +
                "${String.format("%.2f", data.elapsedSeconds)}秒(${String.format("%.2f", data.value)}μT)"
        holder.tvInterval.text = "全周期: ${String.format("%.2f", data.timeDiff)} 秒"
        holder.tvAcceleration.text = "加速度: ${String.format("%.4f", data.acceleration)} m/s²"
        
        // 添加点击事件显示计算过程
        holder.itemView.setOnClickListener {
            val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(holder.itemView.context)
            dialogBuilder.setTitle("加速度计算过程")
            
            // 使用存储的周期值
            val period = data.period
            // 计算时需将cm转换为m
            val pendulumLengthInMeters = pendulumLength / 100.0
            
            val message = """
                【计算过程】
                
                1. 测量数据：
                   单摆长度(L) = ${String.format("%.2f", pendulumLength)} cm = ${String.format("%.2f", pendulumLengthInMeters)} m
                   相隔两个峰值的时间间隔(t) = ${String.format("%.2f", data.timeDiff)} s
                
                2. 计算周期：
                   单摆全周期(T) = ${String.format("%.2f", period)} s
                   （第一个和第三个峰值点之间，第三个和第五个峰值点之间的时间差为一个全周期）
                
                3. 根据单摆公式：
                   T = 2π√(L/g)
                   
                4. 变换得到重力加速度：
                   g = 4π²L/T²
                   
                5. 代入数据：
                   g = 4π² × ${String.format("%.2f", pendulumLengthInMeters)} ÷ (${String.format("%.2f", period)})²
                   g = ${String.format("%.4f", data.acceleration)} m/s²
                
                注：此处计算的加速度即为单摆运动中的重力加速度g
            """.trimIndent()
            
            dialogBuilder.setMessage(message)
            dialogBuilder.setPositiveButton("关闭") { dialog, _ -> dialog.dismiss() }
            
            dialogBuilder.show()
        }
    }
    
    override fun getItemCount(): Int = dataList.size
}