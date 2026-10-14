package com.example.veilark.profile

/** Shared unchanged strict JSON lexer; semantic schemas remain separate. */
internal object CoreProfileJson {
  fun parse(input: String): Any? = StrictJson(input).parse()
    fun validUnicode(value: String): Boolean {
      var i = 0
      while (i < value.length) {
        val c = value[i++]
        if (Character.isHighSurrogate(c)) {
          if (i == value.length || !Character.isLowSurrogate(value[i++])) return false
        } else if (Character.isLowSurrogate(c)) return false
      }
      return true
    }

    class NumberToken(val text: String)

    /** Strict RFC 8259 syntax, duplicate decoded keys rejected before map insertion. */
    private class StrictJson(private val input: String) {
      private var at = 0
      fun parse(): Any? {
        val value = value(0)
        space()
        check(at == input.length)
        return value
      }
      private fun space() { while (at < input.length && input[at] in " \t\r\n") at++ }
      private fun value(depth: Int): Any? {
        check(depth <= 8)
        space()
        check(at < input.length)
        return when (input[at]) {
          '{' -> {
            at++
            val map = linkedMapOf<String, Any?>()
            space()
            if (take('}')) map else {
              do {
                space()
                val key = string()
                check(!map.containsKey(key))
                space(); check(take(':'))
                map[key] = value(depth + 1)
                space()
              } while (take(','))
              check(take('}')); map
            }
          }
          '[' -> {
            at++
            val list = mutableListOf<Any?>()
            space()
            if (take(']')) list else {
              do { list.add(value(depth + 1)); space() } while (take(','))
              check(take(']')); list
            }
          }
          '"' -> string()
          't' -> { literal("true"); true }
          'f' -> { literal("false"); false }
          'n' -> { literal("null"); null }
          else -> number()
        }
      }
      private fun take(c: Char): Boolean = if (at < input.length && input[at] == c) { at++; true } else false
      private fun literal(text: String) { check(input.startsWith(text, at)); at += text.length }
      private fun number(): NumberToken {
        val start = at
        take('-')
        if (!take('0')) { check(at < input.length && input[at] in '1'..'9'); digits() }
        if (take('.')) { val before = at; digits(); check(at > before) }
        if (take('e') || take('E')) { if (!take('+')) take('-'); val before = at; digits(); check(at > before) }
        return NumberToken(input.substring(start, at))
      }
      private fun digits() { while (at < input.length && input[at] in '0'..'9') at++ }
      private fun string(): String {
        check(take('"'))
        val out = StringBuilder()
        while (at < input.length) {
          val c = input[at++]
          if (c == '"') return out.toString().also { check(validUnicode(it)) }
          check(c >= ' ')
          if (c != '\\') out.append(c) else {
            check(at < input.length)
            out.append(when (val escape = input[at++]) {
              '"', '\\', '/' -> escape
              'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
              'u' -> { check(at + 4 <= input.length); val hex = input.substring(at, at + 4); check(hex.matches(Regex("[0-9a-fA-F]{4}"))); at += 4; hex.toInt(16).toChar() }
              else -> error("invalid")
            })
          }
        }
        error("invalid")
      }
    }
}
