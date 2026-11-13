package com.codetool.app.util

import org.json.JSONObject

/**
 * JSONC（JSON with comments）容错解析。
 *
 * MiMo Code 的主配置 `mimocode.jsonc` 允许行注释、块注释和尾逗号，
 * 直接用 `JSONObject` 解析会失败——之前会把整个文件跳过，导致 MCP 页看不到
 * 用户在 MiMo 里已经添加的服务。
 *
 * 这里先按原样解析；失败时去掉注释与尾逗号再试，并用 needsRewrite 标记
 * "原文件不是严格 JSON"，让调用方在写回前备份原文件。
 */
object Jsonc {

    data class Parsed(val obj: JSONObject, val needsRewrite: Boolean)

    fun parse(raw: String): Parsed? {
        val text = raw.trimStart('\uFEFF')
        runCatching { JSONObject(text) }.getOrNull()?.let { return Parsed(it, false) }
        val cleaned = stripTrailingCommas(stripComments(text))
        val obj = runCatching { JSONObject(cleaned) }.getOrNull() ?: return null
        return Parsed(obj, true)
    }

    /** 去掉字符串外的行注释与块注释，字符串内容原样保留。 */
    internal fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inStr = false
        var esc = false
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                sb.append(c)
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                i++
                continue
            }
            when {
                c == '"' -> { inStr = true; sb.append(c); i++ }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** 去掉对象/数组末尾的逗号（字符串内的逗号不动）。 */
    internal fun stripTrailingCommas(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inStr = false
        var esc = false
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                sb.append(c)
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                i++
                continue
            }
            if (c == '"') { inStr = true; sb.append(c); i++; continue }
            if (c == ',') {
                var j = i + 1
                while (j < src.length && src[j].isWhitespace()) j++
                if (j < src.length && (src[j] == '}' || src[j] == ']')) { i++; continue }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
