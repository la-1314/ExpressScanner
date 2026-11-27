package com.express.scanner.util

import com.express.scanner.model.Waybill

/**
 * 全局运单数据存储（内存单例）。
 * MainActivity 提取后存入；CameraActivity 在此查询匹配并标记扫描状态。
 */
object WaybillStore {

    private val _list = mutableListOf<Waybill>()
    val list: List<Waybill> get() = _list

    fun setAll(items: List<Waybill>) {
        _list.clear()
        _list.addAll(items)
    }

    /** 实时标注用：根据条码原文查找匹配运单（不论是否已扫描，命中即高亮）。 */
    fun findByRaw(raw: String): Waybill? =
        _list.firstOrNull { WaybillExtractor.matches(raw, it.number) }

    /** 标记某个运单为已扫描 */
    fun markScanned(number: String): Waybill? {
        val target = _list.firstOrNull { WaybillExtractor.matches(number, it.number) }
        target?.let {
            it.scanned = true
            it.scanTime = System.currentTimeMillis()
        }
        return target
    }

    fun totalCount(): Int = _list.size
}
