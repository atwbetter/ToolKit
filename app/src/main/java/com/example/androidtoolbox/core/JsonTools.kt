package com.example.androidtoolbox.core

/** Strict JSON parser/formatter: preserves number spelling, checks escapes, bounds nesting and input. */
object JsonTools {
    fun format(input: String, compact: Boolean = false, maxChars: Int = 256_000, checkCancelled: () -> Unit = {}): String {
        require(input.length <= maxChars) { "JSON 输入过长" }
        return Parser(input, compact, checkCancelled).parse()
    }
    private class Parser(val source: String, val compact: Boolean, val checkCancelled: () -> Unit) {
        var pos = 0
        val out = StringBuilder()
        fun fail(): Nothing = error("JSON 语法错误，位置 ${pos + 1}")
        fun ws() { while (pos < source.length && source[pos] in " \n\r\t") pos++ }
        fun indent(depth: Int) {
            if (compact) return
            require(out.length < 2_000_000) { "格式化结果过长，请使用压缩模式或分段处理" }
            out.append('\n')
            repeat(depth) { out.append("  ") }
        }
        fun parse(): String { ws(); value(0); ws(); if (pos != source.length) fail(); return out.toString() }
        fun value(depth: Int) {
            checkCancelled()
            require(depth <= 64) { "JSON 嵌套不能超过 64 层" }
            ws()
            when (source.getOrNull(pos)) {
                '{' -> container(depth, true)
                '[' -> container(depth, false)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> fail()
            }
        }
        fun literal(word: String) { if (!source.startsWith(word, pos)) fail(); pos += word.length; out.append(word) }
        fun string() {
            val start = pos++
            while (pos < source.length) {
                val c = source[pos++]
                if (c == '"') { out.append(source, start, pos); return }
                if (c < ' ') fail()
                if (c == '\\') {
                    when (source.getOrNull(pos++)) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) { if (source.getOrNull(pos++)?.digitToIntOrNull(16) == null) fail() }
                        else -> fail()
                    }
                }
            }
            fail()
        }
        fun number() {
            val start = pos
            if (source.getOrNull(pos) == '-') pos++
            if (source.getOrNull(pos) == '0') pos++ else {
                if (source.getOrNull(pos) !in '1'..'9') fail()
                while (source.getOrNull(pos) in '0'..'9') pos++
            }
            if (source.getOrNull(pos) == '.') {
                pos++; if (source.getOrNull(pos) !in '0'..'9') fail()
                while (source.getOrNull(pos) in '0'..'9') pos++
            }
            if (source.getOrNull(pos) in listOf('e', 'E')) {
                pos++; if (source.getOrNull(pos) in listOf('+', '-')) pos++
                if (source.getOrNull(pos) !in '0'..'9') fail()
                while (source.getOrNull(pos) in '0'..'9') pos++
            }
            out.append(source, start, pos)
        }
        fun container(depth: Int, obj: Boolean) {
            val end = if (obj) '}' else ']'
            out.append(source[pos++]); ws()
            if (source.getOrNull(pos) == end) { pos++; out.append(end); return }
            while (true) {
                indent(depth + 1); ws()
                if (obj) {
                    if (source.getOrNull(pos) != '"') fail()
                    string(); ws(); if (source.getOrNull(pos++) != ':') fail()
                    out.append(if (compact) ":" else ": ")
                }
                value(depth + 1); ws()
                when (source.getOrNull(pos++)) {
                    end -> { indent(depth); out.append(end); return }
                    ',' -> out.append(',')
                    else -> fail()
                }
            }
        }
    }
}
