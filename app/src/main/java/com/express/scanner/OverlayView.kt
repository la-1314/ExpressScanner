package com.express.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.express.scanner.model.Waybill

/**
 * 叠加在 PreviewView 之上的实时标注层。
 * 将 ML Kit 返回的条码 boundingBox（旋转后图像坐标系）映射到视图坐标系并绘制：
 *  - 命中目标运单：绿色粗框 + 运单号气泡
 *  - 未命中条码：浅灰细框（仅作参考，提示画面里有哪些条码）
 *
 * 与 PreviewView 的 scaleType=FIT_CENTER 严格对应：等比缩放居中，可能有黑边。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 一个被识别到的条码 + 是否命中待找运单 */
    data class Target(
        val box: Rect,            // 旋转后图像坐标系
        val rawValue: String,
        val waybill: Waybill?     // null = 不在待找列表
    )

    private var imageWidth = 1
    private var imageHeight = 1
    private var targets: List<Target> = emptyList()

    private val matchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34C759")
        style = Paint.Style.STROKE
        strokeWidth = 14f
    }
    private val matchGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6634C759")
        style = Paint.Style.STROKE
        strokeWidth = 28f
    }
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34C759")
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        isFakeBoldText = true
    }
    private val carrierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textSize = 30f
    }

    fun setImageSize(w: Int, h: Int) {
        if (imageWidth != w || imageHeight != h) {
            imageWidth = w.coerceAtLeast(1)
            imageHeight = h.coerceAtLeast(1)
        }
    }

    fun setTargets(list: List<Target>) {
        targets = list
        invalidate()
    }

    fun clearTargets() {
        if (targets.isNotEmpty()) {
            targets = emptyList()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (targets.isEmpty()) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val imgW = imageWidth.toFloat()
        val imgH = imageHeight.toFloat()
        // FIT_CENTER：等比缩放居中
        val scale = minOf(viewW / imgW, viewH / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val offsetX = (viewW - drawW) / 2f
        val offsetY = (viewH - drawH) / 2f

        // 先画未命中（底层），再画命中（顶层）
        for (t in targets) {
            if (t.waybill != null) continue
            drawBox(canvas, t.box, scale, offsetX, offsetY, normalPaint, null, null)
        }
        for (t in targets) {
            if (t.waybill == null) continue
            drawBox(
                canvas, t.box, scale, offsetX, offsetY,
                matchPaint, t.waybill.number, t.waybill.carrier, glow = matchGlowPaint
            )
        }
    }

    private fun drawBox(
        canvas: Canvas,
        box: Rect,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        paint: Paint,
        label: String?,
        carrier: String?,
        glow: Paint? = null
    ) {
        val l = offsetX + box.left * scale
        val top = offsetY + box.top * scale
        val r = offsetX + box.right * scale
        val b = offsetY + box.bottom * scale
        val rectF = RectF(l, top, r, b)
        if (glow != null) canvas.drawRoundRect(rectF, 18f, 18f, glow)
        canvas.drawRoundRect(rectF, 18f, 18f, paint)

        if (label != null) {
            val pad = 18f
            val textW = labelTextPaint.measureText(label)
            val carrierW = if (!carrier.isNullOrEmpty()) carrierPaint.measureText(carrier) else 0f
            val bubbleW = maxOf(textW, carrierW) + pad * 2
            val bubbleH = (if (!carrier.isNullOrEmpty()) 88f else 56f) + pad
            var bubbleLeft = l
            var bubbleTop = top - bubbleH - 6f
            if (bubbleTop < 0f) bubbleTop = b + 6f // 放不下就放框下方
            if (bubbleLeft + bubbleW > width) bubbleLeft = width - bubbleW
            if (bubbleLeft < 0f) bubbleLeft = 0f
            canvas.drawRoundRect(
                RectF(bubbleLeft, bubbleTop, bubbleLeft + bubbleW, bubbleTop + bubbleH),
                14f, 14f, labelBgPaint
            )
            val baseY = bubbleTop + pad + 42f
            canvas.drawText(label, bubbleLeft + pad, baseY, labelTextPaint)
            if (!carrier.isNullOrEmpty()) {
                canvas.drawText(carrier, bubbleLeft + pad, baseY + 40f, carrierPaint)
            }
        }
    }
}
