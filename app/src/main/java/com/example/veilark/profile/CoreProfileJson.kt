package com.example.veilark.profile

/** Shared unchanged strict JSON lexer; semantic schemas remain separate. */
internal object CoreProfileJson {
  fun parse(input: String): Any? = StrictJson(input).parse()
  /** Import discriminator only: validate structure without building generic config trees. */
  fun topLevelSchema(input: String): String? = StrictJson(input).topLevelSchema()
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
      fun topLevelSchema(): String? {
        space(); check(take('{'))
        var schema: String? = null
        var seenSchema = false
        space()
        if (!take('}')) {
          do {
            space(); val key = string(); space(); check(take(':')); space()
            if (key == "schema") {
              check(!seenSchema); seenSchema = true
              if (at < input.length && input[at] == '"') schema = string() else skipValue()
            } else skipValue()
            space()
          } while (take(','))
          check(take('}'))
        }
        space(); check(at == input.length)
        return schema
      }

      /** Same tokens as parse(), with iterative container states and no generic depth limit. */
      private fun skipValue() {
        // Object: 0 first key/end, 1 required key, 2 colon/value, 3 comma/end.
        // Array: 4 first value/end, 5 required value, 6 comma/end.
        val states = java.util.ArrayDeque<Int>()
        fun token() {
          space(); check(at < input.length)
          when (input[at]) {
            '{' -> { at++; states.addLast(0) }
            '[' -> { at++; states.addLast(4) }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
          }
        }
        token()
        while (states.isNotEmpty()) {
          space()
          when (val state = states.removeLast()) {
            0, 1 -> {
              if (state == 0 && take('}')) continue
              string(); states.addLast(2)
            }
            2 -> { check(take(':')); states.addLast(3); token() }
            3 -> if (take(',')) states.addLast(1) else check(take('}'))
            4, 5 -> {
              if (state == 4 && take(']')) continue
              states.addLast(6); token()
            }
            6 -> if (take(',')) states.addLast(5) else check(take(']'))
          }
        }
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
