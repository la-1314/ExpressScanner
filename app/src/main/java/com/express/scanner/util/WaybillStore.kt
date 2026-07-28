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

    fun clear() {
        _list.clear()
    }

    /** 根据扫码结果查找匹配的运单；返回匹配项（不区分大小写，兼容数字片段）。 */
    fun findMatch(scanned: String): Waybill? {
        return _list.firstOrNull { w ->
            !w.scanned && WaybillExtractor.matches(scanned, w.number)
        } ?: _list.firstOrNull { w ->
            w.scanned && WaybillExtractor.matches(scanned, w.number)
        }
    }

    /** 标记某个运单为已扫描并记录截图路径 */
    fun markScanned(number: String, screenshotPath: String): Waybill? {
        val target = _list.firstOrNull { WaybillExtractor.matches(number, it.number) }
        target?.let {
            it.scanned = true
            it.scanTime = System.currentTimeMillis()
            it.screenshotPath = screenshotPath
        }
        return target
    }

    fun remainingCount(): Int = _list.count { !it.scanned }
    fun totalCount(): Int = _list.size
}
