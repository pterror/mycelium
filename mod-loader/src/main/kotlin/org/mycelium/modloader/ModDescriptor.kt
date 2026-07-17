package org.mycelium.modloader

/**
 * Parsed `mod.json` contents. See the mod-loader README (or the task description this
 * module was built from) for the field meanings; briefly:
 *
 * - [id]: unique mod identifier
 * - [name]: human-readable name
 * - [version]: semver string
 * - [description]: optional free text
 * - [targetRuntime]: which Truffle language this targets (e.g. "graaljs", "truffleruby",
 *   "graalpy")
 * - [targetVersion]: GraalVM version this mod was built/tested against
 * - [transformers]: fully-qualified class names implementing [ModTransformer]
 * - [priority]: numeric priority, default 1000, higher runs later (same convention as
 *   Mixin)
 */
data class ModDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val targetRuntime: String?,
    val targetVersion: String?,
    val transformers: List<String>,
    val priority: Int,
) {
    companion object {
        const val DEFAULT_PRIORITY = 1000

        fun parse(json: String): ModDescriptor {
            val root = JsonParser(json).parseObject()

            val id = (root["id"] as? String)
                ?: throw ModDescriptorException("mod.json missing required field 'id'")
            val name = (root["name"] as? String)
                ?: throw ModDescriptorException("mod.json missing required field 'name'")
            val version = (root["version"] as? String)
                ?: throw ModDescriptorException("mod.json missing required field 'version'")
            val description = root["description"] as? String
            val targetRuntime = root["targetRuntime"] as? String
            val targetVersion = root["targetVersion"] as? String
            @Suppress("UNCHECKED_CAST")
            val transformers = (root["transformers"] as? List<Any?>)
                ?.map {
                    it as? String
                        ?: throw ModDescriptorException("mod.json 'transformers' entries must be strings")
                }
                ?: emptyList()
            if (transformers.isEmpty()) {
                throw ModDescriptorException("mod.json '$id' declares no transformers")
            }
            val priority = when (val p = root["priority"]) {
                null -> DEFAULT_PRIORITY
                is Double -> p.toInt()
                is Int -> p
                else -> throw ModDescriptorException("mod.json 'priority' must be a number")
            }

            return ModDescriptor(
                id = id,
                name = name,
                version = version,
                description = description,
                targetRuntime = targetRuntime,
                targetVersion = targetVersion,
                transformers = transformers,
                priority = priority,
            )
        }
    }
}

class ModDescriptorException(message: String) : Exception(message)

/**
 * Minimal hand-rolled JSON parser, sufficient for the flat `mod.json` object shape
 * (strings, numbers, booleans, null, and a single level of string-array nesting for
 * `transformers`). Not a general-purpose JSON library — the mod-loader jar has to be a
 * self-contained fat jar loaded via `-javaagent:` before the application classpath
 * (and any real JSON dependency on it) exists, so pulling in org.json here would mean
 * shading it into this jar too. A ~150-line parser is simpler than that.
 */
private class JsonParser(private val text: String) {
    private var pos = 0

    fun parseObject(): Map<String, Any?> {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw ModDescriptorException("mod.json root must be a JSON object")
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        return when (val c = peek()) {
            '{' -> parseObjectValue()
            '[' -> parseArrayValue()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> if (c == '-' || c.isDigit()) parseNumber() else fail("unexpected character '$c'")
        }
    }

    private fun parseObjectValue(): Map<String, Any?> {
        expect('{')
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return result
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            result[key] = value
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                    continue
                }
                '}' -> {
                    pos++
                    break
                }
                else -> fail("expected ',' or '}' in object")
            }
        }
        return result
    }

    private fun parseArrayValue(): List<Any?> {
        expect('[')
        val result = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return result
        }
        while (true) {
            result.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                    continue
                }
                ']' -> {
                    pos++
                    break
                }
                else -> fail("expected ',' or ']' in array")
            }
        }
        return result
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (pos >= text.length) fail("unterminated escape")
                    when (val esc = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = text.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> fail("invalid escape '\\$esc'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): Double {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
        return text.substring(start, pos).toDouble()
    }

    private fun parseBoolean(): Boolean {
        return if (text.startsWith("true", pos)) {
            pos += 4
            true
        } else if (text.startsWith("false", pos)) {
            pos += 5
            false
        } else {
            fail("invalid literal")
        }
    }

    private fun parseNull(): Any? {
        if (text.startsWith("null", pos)) {
            pos += 4
            return null
        }
        fail("invalid literal")
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char {
        if (pos >= text.length) fail("unexpected end of input")
        return text[pos]
    }

    private fun expect(c: Char) {
        if (pos >= text.length || text[pos] != c) fail("expected '$c'")
        pos++
    }

    private fun fail(message: String): Nothing =
        throw ModDescriptorException("mod.json parse error at offset $pos: $message")
}
