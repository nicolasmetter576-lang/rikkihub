package me.rerere.rikkihub.web.dto

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.local.ChartScaleType
import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.data.ai.tools.local.ChartStyle

/**
 * 图表显示。校验只在服务端做：复用 [ChartSpec.fromTool]（已执行、已批准/自动、
 * 成功标记 + 输入哈希一致、严格解析与限额），通过的才把规范化数据随消息下发；浏览器只负责画。
 * 浏览器不能自己重算哈希：kotlinx 保留数字原写法（如 `1.50`），`JSON.parse` 会改写。
 */
@Serializable
data class ChartDto(
    /** Index of the tool part in [MessageDto.parts]. */
    val partIndex: Int,
    val spec: ChartSpecDto,
)

@Serializable
data class ChartSpecDto(
    /** line | bar | scatter */
    val style: String,
    val title: String? = null,
    val xAxis: ChartAxisDto,
    val yAxis: ChartAxisDto,
    val series: List<ChartSeriesDto>,
)

@Serializable
data class ChartAxisDto(
    val title: String? = null,
    val data: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    /** linear | log */
    val scale: String = "linear",
    val format: String? = null,
)

@Serializable
data class ChartSeriesDto(
    val name: String? = null,
    val color: String? = null,
    val values: List<Double> = emptyList(),
    val points: List<ChartPointDto> = emptyList(),
)

@Serializable
data class ChartPointDto(val x: Double, val y: Double)

internal fun ChartSpec.toDto() = ChartSpecDto(
    style = when (style) {
        ChartStyle.Line -> "line"
        ChartStyle.Bar -> "bar"
        ChartStyle.Scatter -> "scatter"
    },
    title = title,
    xAxis = xAxis.let { ChartAxisDto(it.title, it.data, it.min, it.max, it.scale.wire(), it.format) },
    yAxis = yAxis.let { ChartAxisDto(it.title, it.data, it.min, it.max, it.scale.wire(), it.format) },
    series = series.map { s -> ChartSeriesDto(s.name, s.color, s.values, s.points.map { ChartPointDto(it.x, it.y) }) },
)

private fun ChartScaleType.wire() = if (this == ChartScaleType.Log) "log" else "linear"

/**
 * Small LRU of validated charts. Streaming re-serializes the growing message on every chunk;
 * without it a marked 1 MiB input would be parsed and hashed again each time.
 */
internal object WebChartProjection {
    private const val CAPACITY = 64
    private val cache = object : LinkedHashMap<String, ChartSpecDto?>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChartSpecDto?>?) = size > CAPACITY
    }

    fun chartsOf(message: UIMessage): List<ChartDto>? = message.parts.mapIndexedNotNull { index, part ->
        val tool = part as? UIMessagePart.Tool ?: return@mapIndexedNotNull null
        project(tool)?.let { ChartDto(index, it) }
    }.ifEmpty { null }

    fun project(tool: UIMessagePart.Tool): ChartSpecDto? {
        // Cheap reject before touching the input: only marked, executed results can be charts.
        val marker = (tool.output.firstOrNull() as? UIMessagePart.Text)?.metadata ?: return null
        if (!tool.isExecuted) return null
        val key = buildString {
            append(tool.toolCallId).append('|').append(marker).append('|')
            append(tool.approvalState::class.simpleName).append('|')
            append(tool.input.length).append('|').append(tool.input.hashCode())
        }
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val projected = ChartSpec.fromTool(tool)?.toDto()
        synchronized(cache) { cache[key] = projected }
        return projected
    }
}
