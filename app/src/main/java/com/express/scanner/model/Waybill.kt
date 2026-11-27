package com.express.scanner.model

/**
 * 运单号数据模型。
 * @param number 运单号字符串
 * @param carrier 推测的快递公司（可能为空）
 * @param scanned 是否已在摄像头画面中定位到
 */
data class Waybill(
    val number: String,
    val carrier: String? = null,
    var scanned: Boolean = false,
    var scanTime: Long = 0L
)
