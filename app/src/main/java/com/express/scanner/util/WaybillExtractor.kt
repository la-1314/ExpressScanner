package com.express.scanner.util

import com.express.scanner.model.Waybill

/**
 * 运单号提取工具。
 *
 * 支持识别国内主流快递公司的运单号格式：
 *  - 字母前缀 + 数字（如 SF1234567890123、JD1234567890、YT1234567890123）
 *  - 纯数字运单号（12~15 位，圆通/中通/韵达/申通 等多采用此格式）
 *  - 国际 EMS 格式（2 字母 + 9 数字 + 2 字母，共 13 位）
 *
 * 同时根据前缀粗略推测快递公司。
 */
object WaybillExtractor {

    /** 主正则：字母前缀(2~4位) + 数字(8~15位) */
    private const val PREFIX_PATTERN = "(?<![A-Za-z0-9])([A-Za-z]{2,4})\\d{8,15}(?![A-Za-z0-9])"

    /** 纯数字运单号：12~15 位 */
    private const val DIGIT_PATTERN = "(?<![0-9])\\d{12,15}(?![0-9])"

    /** 国际 EMS：2 字母 + 9 数字 + 2 字母 */
    private const val EMS_PATTERN = "(?<![A-Za-z0-9])([A-Za-z]{2})\\d{9}([A-Za-z]{2})(?![A-Za-z0-9])"

    private val prefixRegex = Regex(PREFIX_PATTERN, RegexOption.IGNORE_CASE)
    private val digitRegex = Regex(DIGIT_PATTERN)
    private val emsRegex = Regex(EMS_PATTERN, RegexOption.IGNORE_CASE)

    /** 已知快递公司前缀映射（用于推测） */
    private val carrierMap = mapOf(
        "SF" to "顺丰速运",
        "JD" to "京东物流",
        "YT" to "圆通速递",
        "YTO" to "圆通速递",
        "ZT" to "中通快递",
        "ZTO" to "中通快递",
        "STO" to "申通快递",
        "ST" to "申通快递",
        "YD" to "韵达快递",
        "YUNDA" to "韵达快递",
        "BS" to "百世快递",
        "BT" to "百世快递",
        "HT" to "百世快递",
        "EMS" to "邮政EMS",
        "DPD" to "德邦快递",
        "KK" to "顺丰快运",
        "PJ" to "品骏快递"
    )

    /**
     * 从任意文本中提取运单号列表（去重，保持出现顺序）。
     */
    fun extract(text: String): List<Waybill> {
        if (text.isBlank()) return emptyList()

        val result = linkedSetOf<String>()
        val upper = { s: String -> s.uppercase() }

        // 1) EMS 国际格式优先匹配（避免被纯数字规则截断）
        emsRegex.findAll(text).forEach { mr ->
            result.add(upper(mr.value))
        }
        // 2) 字母前缀 + 数字
        prefixRegex.findAll(text).forEach { mr ->
            result.add(upper(mr.value))
        }
        // 3) 纯数字 12~15 位
        digitRegex.findAll(text).forEach { mr ->
            // 跳过已作为 EMS/前缀部分被收录的纯数字片段
            val num = mr.value
            if (result.none { it.contains(num) }) {
                result.add(num)
            }
        }

        return result.map { Waybill(number = it, carrier = guessCarrier(it)) }
    }

    /** 根据前缀推测快递公司 */
    private fun guessCarrier(number: String): String? {
        // 取开头的字母前缀
        val prefix = number.takeWhile { it.isLetter() }
        if (prefix.isEmpty()) return null
        return carrierMap[prefix.uppercase()]
    }

    /**
     * 判断一个扫码结果是否与某个运单号匹配。
     * 允许大小写不同、以及条码可能只有数字部分的情况。
     */
    fun matches(scanned: String, target: String): Boolean {
        val s = scanned.trim().uppercase()
        val t = target.trim().uppercase()
        if (s == t) return true
        // 条码有时只编码数字部分
        val digits = t.filter { it.isDigit() }
        return digits.length >= 10 && s == digits
    }
}
