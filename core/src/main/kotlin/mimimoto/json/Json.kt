/**
 * A minimal JSON reader and writer.
 *
 * Present so the service has no runtime dependencies at all. The wire surface
 * is small — a handful of request and response shapes — and at that size a
 * few hundred readable lines cost less than a library version to track. If the
 * API grows past that, replacing this package with kotlinx-serialization is a
 * contained change.
 */
package mimimoto.json

sealed interface Json {
    data object Null : Json
    data class Bool(val value: Boolean) : Json
    data class Num(val value: Double) : Json
    data class Str(val value: String) : Json
    data class Arr(val items: List<Json>) : Json
    data class Obj(val fields: Map<String, Json>) : Json

    companion object {
        fun parse(text: String): Json = Parser(text).run {
            val v = parseValue()
            skipWhitespace()
            require(atEnd) { "trailing content at offset $pos" }
            v
        }
    }
}

// ---------- accessors ----------
//
// Deliberately forgiving on read: a missing field reads as absent rather than
// throwing, because handlers want to validate their own inputs and produce
// their own messages.

operator fun Json.get(key: String): Json? = (this as? Json.Obj)?.fields?.get(key)

fun Json?.asString(): String? = (this as? Json.Str)?.value
fun Json?.asDouble(): Double? = (this as? Json.Num)?.value
fun Json?.asInt(): Int? = (this as? Json.Num)?.value?.toInt()
fun Json?.asBool(): Boolean? = (this as? Json.Bool)?.value
fun Json?.asList(): List<Json>? = (this as? Json.Arr)?.items

fun Json.str(key: String): String? = this[key].asString()
fun Json.int(key: String): Int? = this[key].asInt()
fun Json.dbl(key: String): Double? = this[key].asDouble()
fun Json.bool(key: String): Boolean? = this[key].asBool()

// ---------- writing ----------

fun jsonOf(vararg pairs: Pair<String, Any?>): Json.Obj =
    Json.Obj(LinkedHashMap<String, Json>().apply {
        for ((k, v) in pairs) put(k, toJson(v))
    })

fun toJson(v: Any?): Json = when (v) {
    null -> Json.Null
    is Json -> v
    is Boolean -> Json.Bool(v)
    is Int -> Json.Num(v.toDouble())
    is Long -> Json.Num(v.toDouble())
    is Double -> Json.Num(v)
    is Float -> Json.Num(v.toDouble())
    is String -> Json.Str(v)
    is Enum<*> -> Json.Str(v.name.lowercase())
    is Map<*, *> -> Json.Obj(LinkedHashMap<String, Json>().apply {
        for ((k, x) in v) put(k.toString(), toJson(x))
    })
    is Iterable<*> -> Json.Arr(v.map { toJson(it) })
    else -> Json.Str(v.toString())
}

fun Json.render(): String = StringBuilder().also { write(it) }.toString()

private fun Json.write(sb: StringBuilder) {
    when (this) {
        is Json.Null -> sb.append("null")
        is Json.Bool -> sb.append(if (value) "true" else "false")
        is Json.Num -> sb.append(renderNumber(value))
        is Json.Str -> escapeInto(sb, value)
        is Json.Arr -> {
            sb.append('[')
            items.forEachIndexed { i, it -> if (i > 0) sb.append(','); it.write(sb) }
            sb.append(']')
        }
        is Json.Obj -> {
            sb.append('{')
            var first = true
            for ((k, v) in fields) {
                if (!first) sb.append(',')
                first = false
                escapeInto(sb, k)
                sb.append(':')
                v.write(sb)
            }
            sb.append('}')
        }
    }
}

/** Whole values render without a trailing ".0" so ids and counts read naturally. */
private fun renderNumber(d: Double): String = when {
    d.isNaN() || d.isInfinite() -> "null" // JSON has no way to say this
    d == d.toLong().toDouble() && kotlin.math.abs(d) < 1e15 -> d.toLong().toString()
    else -> d.toString()
}

private fun escapeInto(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
}

// ---------- parsing ----------

private class Parser(private val src: String) {
    var pos = 0
    val atEnd get() = pos >= src.length

    fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    fun parseValue(): Json {
        skipWhitespace()
        require(!atEnd) { "unexpected end of input" }
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> Json.Str(parseString())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c.isDigit()) parseNumber()
            else throw IllegalArgumentException("unexpected '$c' at offset $pos")
        }
    }

    private fun literal(word: String, value: Json): Json {
        require(src.startsWith(word, pos)) { "expected $word at offset $pos" }
        pos += word.length
        return value
    }

    private fun parseObject(): Json {
        pos++ // {
        val out = LinkedHashMap<String, Json>()
        skipWhitespace()
        if (!atEnd && src[pos] == '}') { pos++; return Json.Obj(out) }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            require(!atEnd && src[pos] == ':') { "expected ':' at offset $pos" }
            pos++
            out[key] = parseValue()
            skipWhitespace()
            require(!atEnd) { "unterminated object" }
            when (src[pos]) {
                ',' -> pos++
                '}' -> { pos++; return Json.Obj(out) }
                else -> throw IllegalArgumentException("expected ',' or '}' at offset $pos")
            }
        }
    }

    private fun parseArray(): Json {
        pos++ // [
        val out = mutableListOf<Json>()
        skipWhitespace()
        if (!atEnd && src[pos] == ']') { pos++; return Json.Arr(out) }
        while (true) {
            out += parseValue()
            skipWhitespace()
            require(!atEnd) { "unterminated array" }
            when (src[pos]) {
                ',' -> pos++
                ']' -> { pos++; return Json.Arr(out) }
                else -> throw IllegalArgumentException("expected ',' or ']' at offset $pos")
            }
        }
    }

    private fun parseString(): String {
        require(!atEnd && src[pos] == '"') { "expected string at offset $pos" }
        pos++
        val sb = StringBuilder()
        while (true) {
            require(!atEnd) { "unterminated string" }
            when (val c = src[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    require(!atEnd) { "unterminated escape" }
                    when (val e = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(pos + 4 <= src.length) { "truncated \\u escape" }
                            sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("bad escape '\\$e' at offset ${pos - 1}")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): Json {
        val start = pos
        if (!atEnd && src[pos] == '-') pos++
        while (!atEnd && (src[pos].isDigit() || src[pos] in ".eE+-")) pos++
        val text = src.substring(start, pos)
        val d = text.toDoubleOrNull()
            ?: throw IllegalArgumentException("bad number '$text' at offset $start")
        return Json.Num(d)
    }
}
