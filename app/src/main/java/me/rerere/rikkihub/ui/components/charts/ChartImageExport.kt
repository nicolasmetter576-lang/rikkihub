package me.rerere.rikkihub.ui.components.charts

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.local.ChartSpec
import me.rerere.rikkihub.data.ai.tools.local.ChartStyle
import me.rerere.rikkihub.utils.exportImage
import me.rerere.rikkihub.utils.getActivity
import java.io.FileOutputStream
import java.util.UUID

private const val IMAGE_WIDTH = 1080
private const val IMAGE_HEIGHT = 900

/** Explicit user action only. No HTML, WebView, BitmapComposer or tool-execution IO. */
internal suspend fun exportChartImage(context: Context, spec: ChartSpec, names: List<String>, fallbackTitle: String) {
    val activity = context.getActivity() ?: error("No activity available for image export")
    val filename = "chart-${UUID.randomUUID()}.png"
    withContext(Dispatchers.IO) {
        val file = context.appTempFolder.resolve(filename)
        exportChartResource(
            capture = { drawChartBitmap(spec, names, fallbackTitle) },
            encode = { bitmap ->
                FileOutputStream(file).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
                }
            },
            gallery = { bitmap ->
                // Existing strict API returns only after save/encoding; permission requests are failures.
                withContext(Dispatchers.Main) {
                    context.exportImage(activity, bitmap, filename, throwOnFailure = true)
                }
            },
            share = {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(filename, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent,
                        context.getString(R.string.chat_page_export_share_via)))
                }
            },
            recycle = { it.recycle() },
            cleanupFailedFile = { file.delete() },
        )
    }
}

/** Native drawing uses exactly the same bounded scales/ticks/points/layout as Compose. */
internal fun drawChartBitmap(spec: ChartSpec, names: List<String>, fallbackTitle: String): Bitmap {
    val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(label: String, x: Float, y: Float, width: Float, size: Float = 22f, centered: Boolean = false) {
            paint.color = 0xFF333333.toInt()
            paint.style = Paint.Style.FILL
            paint.textSize = size
            val bounded = label.take(240)
            val available = width.coerceAtLeast(1f)
            val count = paint.breakText(bounded, true, available, null)
            val display = if (count < bounded.length) bounded.take((count - 1).coerceAtLeast(0)) + "…" else bounded
            val left = if (centered) x - paint.measureText(display) / 2 else x
            canvas.drawText(display, left.coerceIn(0f, (IMAGE_WIDTH - paint.measureText(display)).coerceAtLeast(0f)), y, paint)
        }
        text(spec.title?.takeIf { it.isNotBlank() } ?: fallbackTitle, 32f, 48f, IMAGE_WIDTH - 64f, 32f)
        spec.yAxis.title?.let { text(it, 32f, 82f, IMAGE_WIDTH - 64f) }
        val geometry = spec.buildChartGeometry()
        val plotHeight = 620f
        val plotTop = 96f
        val plot = chartPlotBounds(IMAGE_WIDTH.toFloat(), plotHeight)
        fun px(x: Double) = plot.x(x)
        fun py(y: Double) = plotTop + plot.y(y)
        geometry.yTicks.forEach { tick ->
            val y = py(1.0 - tick.fraction)
            paint.color = 0xFFDDDDDD.toInt()
            paint.strokeWidth = 1f
            canvas.drawLine(plot.left, y, plot.right, y, paint)
            text(tick.label, 16f, y + 7f, plot.left - 24f)
        }
        geometry.xTicks.forEach { tick ->
            text(tick.label, px(tick.fraction), plotTop + plot.bottom + 30f,
                IMAGE_WIDTH.toFloat() / geometry.xTicks.size.coerceAtLeast(1), centered = true)
        }
        paint.color = 0xFF777777.toInt()
        paint.strokeWidth = 2f
        canvas.drawLine(plot.left, py(0.0), plot.left, py(1.0), paint)
        canvas.drawLine(plot.left, py(1.0), plot.right, py(1.0), paint)
        canvas.save()
        try {
            canvas.clipRect(plot.left, py(0.0), plot.right, py(1.0))
            geometry.series.forEach { series ->
                paint.color = series.color
                paint.strokeWidth = 3f
                paint.style = Paint.Style.FILL
                when (geometry.style) {
                    ChartStyle.Bar -> series.bars.forEach { bar ->
                        canvas.drawRect(RectF(px(bar.left), py(bar.top), px(bar.right), py(bar.bottom)), paint)
                    }
                    ChartStyle.Scatter -> series.points.forEach { p ->
                        if (p != null) canvas.drawCircle(px(p.x), py(p.y), 5f, paint)
                    }
                    ChartStyle.Line -> {
                        series.lineSegments.forEach { segment ->
                            canvas.drawLine(px(segment.start.x), py(segment.start.y),
                                px(segment.end.x), py(segment.end.y), paint)
                        }
                        if (series.points.size <= 100) series.points.forEach { p ->
                            if (p != null) canvas.drawCircle(px(p.x), py(p.y), 4f, paint)
                        }
                    }
                }
            }
        } finally { canvas.restore() }
        spec.xAxis.title?.let { text(it, IMAGE_WIDTH / 2f, 690f, IMAGE_WIDTH - 64f, centered = true) }
        // Fixed 3-column, 4-row legend accommodates all 12 series without growing the bitmap.
        geometry.series.forEachIndexed { index, s ->
            val left = 32f + (index % 3) * 350f
            val top = 744f + (index / 3) * 34f
            paint.color = s.color
            canvas.drawRect(left, top - 16f, left + 16f, top, paint)
            text(names.getOrNull(index).orEmpty(), left + 24f, top, 310f, 20f)
        }
        return bitmap
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    }
}
