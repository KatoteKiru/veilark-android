package com.example.veilark.profile

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class SubscriptionParser {
  fun extractTrustTunnelLinks(payload: ByteArray): List<String> =
    extractShareLinks(decodePayload(payload))
      .filter { it.startsWith("tt://", ignoreCase = true) }
      .distinct()

  fun compile(payload: ByteArray): CompiledSubscription {
    require(payload.size <= MAX_SUBSCRIPTION_SIZE) { "Подписка больше 4 МБ" }
    val decoded = decodePayload(payload)
    compileJsonDocument(decoded)?.let { return it }
    compileClashDocument(decoded)?.let { return it }
    val links = extractShareLinks(decoded)
    require(links.isNotEmpty()) {
      "Поддерживаемые ссылки не найдены. Нужна подписка для sing-box или v2rayNG"
    }
    val trustTunnelLinks = links.filter {
      it.startsWith("tt://", ignoreCase = true)
    }
    val proxyLinks = links.filterNot {
      it.startsWith("tt://", ignoreCase = true)
    }

    val errors = mutableListOf<String>()
    val parsedOutbounds = proxyLinks.mapIndexedNotNull { index, link ->
      runCatching { parseLink(link, index) }
        .onFailure { errors += "Профиль ${index + 1}: ${it.message}" }
        .getOrNull()
    }
    require(parsedOutbounds.isNotEmpty()) {
      errors.firstOrNull() ?: "Поддерживаемые профили не найдены"
    }

    return buildSubscription(
      parsedOutbounds,
      trustTunnelLinks,
      errors.size,
      errors.take(MAX_REPORTED_ERRORS),
    )
  }

  private fun createBaseConfig(
    tags: List<String>,
    outbounds: List<JSONObject>,
  ): JSONObject = JSONObject()
      .put("log", JSONObject().put("level", "warn").put("timestamp", true))
      .put(
        "dns",
        JSONObject()
          .put(
            "servers",
            JSONArray()
              .put(
                JSONObject()
                  .put("type", "local")
                  .put("tag", "bootstrap-dns"),
              )
              .put(
                JSONObject()
                  .put("type", "https")
                  .put("tag", "secure-dns")
                  .put("server", "1.1.1.1")
                  .put("server_port", 443)
                  .put("path", "/dns-query")
                  .put(
                    "tls",
                    JSONObject()
                      .put("enabled", true)
                      .put("server_name", "cloudflare-dns.com"),
                  )
                  .put("detour", "auto"),
              ),
          )
          .put("final", "secure-dns")
          // The three production nodes have IPv4 egress. Returning AAAA records can make
          // Android applications select an unreachable IPv6 destination and appear to hang.
          .put("strategy", "ipv4_only")
          .put("reverse_mapping", true),
      )
      .put(
        "inbounds",
        JSONArray().put(
          JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("auto_route", true)
            .put("strict_route", true)
            .put("mtu", 1280)
            .put("stack", "mixed"),
        ),
      )
      .put(
        "outbounds",
        JSONArray()
          .put(
            JSONObject()
              .put("type", "urltest")
              .put("tag", "auto")
              .put("outbounds", JSONArray(tags))
              // Server selection must not depend on a service that can be selectively
              // throttled or blocked. The probe is advisory and carries no user traffic.
              .put("url", "https://cp.cloudflare.com/generate_204")
              .put("interval", "10m")
              .put("tolerance", 80)
              .put("idle_timeout", "15m")
              .put("interrupt_exist_connections", false),
          )
          .also { array -> outbounds.forEach(array::put) }
          .put(JSONObject().put("type", "direct").put("tag", "direct")),
      )
      .put(
        "route",
        JSONObject()
          .put("auto_detect_interface", true)
          .put("default_domain_resolver", "bootstrap-dns")
          .put("final", "auto"),
      )

  private fun decodePayload(payload: ByteArray): String {
    val raw = payload.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
    require(raw.isNotEmpty()) { "Подписка пуста" }
    if (raw.startsWith("{") || raw.startsWith("[")) return raw
    if (CLASH_DOCUMENT.containsMatchIn(raw)) return raw
    if (raw.lineSequence().any { it.trim().matches(SCHEME_LINE) }) return raw
    val normalized = raw.filterNot(Char::isWhitespace)
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    val decoded = runCatching {
      Base64.getDecoder().decode(padded).toString(Charsets.UTF_8)
    }.recoverCatching {
      Base64.getUrlDecoder().decode(padded).toString(Charsets.UTF_8)
    }.getOrElse { error("Не удалось декодировать подписку") }
    val decodedTrimmed = decoded.trimStart()
    require(
      decodedTrimmed.startsWith("{") ||
        decodedTrimmed.startsWith("[") ||
        CLASH_DOCUMENT.containsMatchIn(decoded) ||
        decoded.lineSequence().any { it.trim().matches(SCHEME_LINE) },
    ) {
      "В подписке нет поддерживаемых ссылок"
    }
    return decoded
  }

  /**
   * Accepts URI lists as well as the JSON envelopes used by subscription gateways.
   * Only complete supported share links are retained; arbitrary JSON values are ignored.
   */
  private fun extractShareLinks(decoded: String): List<String> {
    val links = linkedSetOf<String>()

    fun addCandidate(value: String) {
      fun addLines(text: String): Boolean {
        var matched = false
        text.lineSequence()
          .map(String::trim)
          .filter { it.matches(SCHEME_LINE) }
          .forEach {
            if (links.size < MAX_PROFILE_LINKS) links += it
            matched = true
          }
        return matched
      }

      val trimmed = value.trim()
      if (trimmed.isEmpty() || addLines(trimmed) || trimmed.length < 12) return
      decodeBase64OrNull(trimmed)?.let(::addLines)
    }

    fun visit(value: Any?, depth: Int) {
      if (depth > MAX_JSON_DEPTH || links.size >= MAX_PROFILE_LINKS) return
      when (value) {
        is String -> addCandidate(value)
        is JSONObject -> value.keys().forEachRemaining { key ->
          visit(value.opt(key), depth + 1)
        }
        is JSONArray -> for (index in 0 until value.length()) {
          visit(value.opt(index), depth + 1)
        }
      }
    }

    val trimmed = decoded.trim()
    when {
      trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }
        .getOrNull()
        ?.let { visit(it, 0) }
      trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }
        .getOrNull()
        ?.let { visit(it, 0) }
      else -> addCandidate(trimmed)
    }
    return links.toList()
  }

  private fun decodeBase64OrNull(value: String): String? {
    val normalized = value
      .filterNot(Char::isWhitespace)
      .replace('-', '+')
      .replace('_', '/')
    if (!normalized.matches(BASE64_VALUE)) return null
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    return runCatching {
      Base64.getDecoder().decode(padded).toString(Charsets.UTF_8)
    }.getOrNull()
  }

  private fun parseLink(link: String, index: Int): ParsedOutbound {
    val scheme = link.substringBefore("://", "").lowercase()
    if (scheme == "vmess") return parseVmessLink(link, index)
    if (scheme == "ss") return parseShadowsocksLink(link, index)
    val uri = ParsedShareUri.parse(link)
    val name = uri.fragment.ifBlank { "${uri.scheme.uppercase()} ${index + 1}" }
      .replace(Regex("""[\u0000-\u001F]"""), "")
      .trim()
      .take(64)
    val tag = uniqueTag(name, index)
    val json = when (uri.scheme) {
      "vless" -> parseVless(uri, tag)
      "trojan" -> parseTrojan(uri, tag)
      "hysteria2", "hy2" -> parseHysteria2(uri, tag)
      "tuic" -> parseTuic(uri, tag)
      "anytls" -> parseAnyTls(uri, tag)
      else -> error("протокол ${uri.scheme} пока не поддерживается импортом")
    }
    return ParsedOutbound(
      json = json,
      node = ConnectionNode(
        tag = tag,
        name = name,
        protocol = when (uri.scheme) {
          "hysteria2", "hy2" -> "Hysteria 2"
          "tuic" -> "TUIC"
          "anytls" -> "AnyTLS"
          "vless" -> "VLESS"
          else -> "Trojan"
        },
      ),
    )
  }

  private fun parseVmessLink(link: String, index: Int): ParsedOutbound {
    val encoded = link.substringAfter("vmess://").trim()
    val decoded = decodeBase64(encoded)
    val source = JSONObject(decoded)
    val name = source.optString("ps").ifBlank { "VMess ${index + 1}" }
      .replace(Regex("""[\u0000-\u001F]"""), "").trim().take(64)
    val tag = uniqueTag(name, index)
    val host = source.optString("add")
    val port = source.optString("port").toIntOrNull()
    val uuid = source.optString("id")
    require(host.isNotBlank() && port != null && port in 1..65535 && uuid.isNotBlank()) {
      "VMess содержит некорректный адрес, порт или UUID"
    }
    val outbound = JSONObject()
      .put("type", "vmess")
      .put("tag", tag)
      .put("server", host)
      .put("server_port", port)
      .put("uuid", uuid)
      .put("security", source.optString("scy").ifBlank { "auto" })
      .put("alter_id", source.optString("aid").toIntOrNull() ?: 0)
    val network = source.optString("net").ifBlank { "tcp" }
    val security = source.optString("tls")
    if (security.isNotBlank() && security != "none") {
      outbound.put(
        "tls",
        JSONObject()
          .put("enabled", true)
          .put("server_name", source.optString("sni").ifBlank { host })
          .put("insecure", false),
      )
    }
    when (network) {
      "ws" -> outbound.put(
        "transport",
        JSONObject()
          .put("type", "ws")
          .put("path", source.optString("path").ifBlank { "/" })
          .put(
            "headers",
            JSONObject().apply {
              source.optString("host").takeIf(String::isNotBlank)?.let { put("Host", it) }
            },
          ),
      )
      "grpc" -> outbound.put(
        "transport",
        JSONObject()
          .put("type", "grpc")
          .put("service_name", source.optString("path")),
      )
      "tcp", "" -> Unit
      else -> error("VMess transport $network пока не поддерживается")
    }
    return ParsedOutbound(outbound, ConnectionNode(tag, name, "VMess"))
  }

  private fun parseShadowsocksLink(link: String, index: Int): ParsedOutbound {
    val body = link.substringAfter("ss://")
    val fragmentIndex = body.indexOf('#')
    val fragment = if (fragmentIndex >= 0) decodeUrl(body.substring(fragmentIndex + 1)) else ""
    val withoutFragment = if (fragmentIndex >= 0) body.substring(0, fragmentIndex) else body
    val withoutQuery = withoutFragment.substringBefore('?')
    val decoded = if (withoutQuery.contains('@')) {
      val at = withoutQuery.lastIndexOf('@')
      val credentials = withoutQuery.substring(0, at)
      val clearCredentials = if (credentials.contains(':')) credentials else decodeBase64(credentials)
      "$clearCredentials@${withoutQuery.substring(at + 1)}"
    } else {
      decodeBase64(withoutQuery)
    }
    val at = decoded.lastIndexOf('@')
    require(at > 0) { "Shadowsocks содержит некорректные учётные данные" }
    val credentials = decoded.substring(0, at)
    val separator = credentials.indexOf(':')
    require(separator > 0) { "Shadowsocks method или password отсутствует" }
    val method = credentials.substring(0, separator)
    val password = credentials.substring(separator + 1)
    val hostPort = decoded.substring(at + 1)
    val (host, port) = parseHostPort(hostPort)
    val name = fragment.ifBlank { "Shadowsocks ${index + 1}" }.trim().take(64)
    val tag = uniqueTag(name, index)
    val outbound = JSONObject()
      .put("type", "shadowsocks")
      .put("tag", tag)
      .put("server", host)
      .put("server_port", port)
      .put("method", method)
      .put("password", password)
    return ParsedOutbound(outbound, ConnectionNode(tag, name, "Shadowsocks"))
  }

  private fun parseTuic(uri: ParsedShareUri, tag: String): JSONObject {
    val credentials = uri.userInfo.split(':', limit = 2)
    require(credentials.size == 2 && credentials.all(String::isNotBlank)) {
      "TUIC требует UUID и пароль"
    }
    return JSONObject()
      .put("type", "tuic")
      .put("tag", tag)
      .put("server", uri.host)
      .put("server_port", uri.singlePort())
      .put("uuid", credentials[0])
      .put("password", credentials[1])
      .put("congestion_control", uri.query["congestion_control"] ?: "bbr")
      .put("udp_relay_mode", uri.query["udp_relay_mode"] ?: "native")
      .put("tls", tls(uri))
  }

  private fun parseAnyTls(uri: ParsedShareUri, tag: String): JSONObject =
    JSONObject()
      .put("type", "anytls")
      .put("tag", tag)
      .put("server", uri.host)
      .put("server_port", uri.singlePort())
      .put("password", uri.userInfo)
      .put("tls", tls(uri))

  private fun compileJsonDocument(decoded: String): CompiledSubscription? {
    if (!decoded.trimStart().startsWith("{")) return null
    val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
    val sourceOutbounds = root.optJSONArray("outbounds") ?: return null
    if ((0 until sourceOutbounds.length()).any {
        sourceOutbounds.optJSONObject(it)?.has("protocol") == true
      }
    ) {
      return compileXrayJson(sourceOutbounds)
    }
    val supported = setOf(
      "vless", "trojan", "hysteria2", "vmess", "shadowsocks", "tuic", "anytls",
    )
    val links = mutableListOf<ParsedOutbound>()
    for (index in 0 until sourceOutbounds.length()) {
      val outbound = sourceOutbounds.optJSONObject(index) ?: continue
      val type = outbound.optString("type")
      if (type !in supported) continue
      val name = outbound.optString("tag").ifBlank { "${protocolName(type)} ${index + 1}" }
        .take(64)
      val tag = uniqueTag(name, links.size)
      outbound.put("tag", tag)
      links += ParsedOutbound(
        outbound,
        ConnectionNode(tag, name, protocolName(type)),
      )
    }
    require(links.isNotEmpty()) { "В JSON нет поддерживаемых sing-box outbounds" }
    return buildSubscription(links, emptyList(), 0)
  }

  private fun compileXrayJson(sourceOutbounds: JSONArray): CompiledSubscription {
    val supported = setOf("vless", "vmess", "trojan", "shadowsocks")
    val parsed = mutableListOf<ParsedOutbound>()
    var rejected = 0
    repeat(sourceOutbounds.length()) { outboundIndex ->
      val source = sourceOutbounds.optJSONObject(outboundIndex) ?: return@repeat
      val protocol = source.optString("protocol").lowercase()
      if (protocol !in supported) {
        rejected += 1
        return@repeat
      }
      val settings = source.optJSONObject("settings") ?: JSONObject()
      val stream = source.optJSONObject("streamSettings") ?: JSONObject()
      val endpoints = when (protocol) {
        "vless", "vmess" -> settings.optJSONArray("vnext")
        else -> settings.optJSONArray("servers")
      } ?: JSONArray()
      if (endpoints.length() == 0) {
        rejected += 1
        return@repeat
      }
      repeat(endpoints.length()) endpointLoop@{ endpointIndex ->
        val endpoint = endpoints.optJSONObject(endpointIndex) ?: run {
          rejected += 1
          return@endpointLoop
        }
        val user = endpoint.optJSONArray("users")?.optJSONObject(0)
        val name = source.optString("tag")
          .ifBlank { "${protocolName(protocol)} ${parsed.size + 1}" }
          .take(64)
        val tag = uniqueTag(name, parsed.size)
        runCatching {
          val json = JSONObject()
            .put("type", protocol)
            .put("tag", tag)
            .put("server", endpoint.getString("address"))
            .put("server_port", endpoint.getInt("port"))
          when (protocol) {
            "vless" -> {
              json.put("uuid", requireNotNull(user).getString("id"))
              user.optString("flow").takeIf(String::isNotBlank)?.let {
                json.put("flow", it)
              }
            }
            "vmess" -> {
              json.put("uuid", requireNotNull(user).getString("id"))
              json.put("security", user.optString("security").ifBlank { "auto" })
              json.put("alter_id", user.optInt("alterId", 0))
            }
            "trojan" -> json.put("password", endpoint.getString("password"))
            "shadowsocks" -> {
              json.put("method", endpoint.getString("method"))
              json.put("password", endpoint.getString("password"))
            }
          }
          xrayTls(stream)?.let { json.put("tls", it) }
          xrayTransport(stream)?.let { json.put("transport", it) }
          parsed += ParsedOutbound(
            json,
            ConnectionNode(tag, name, protocolName(protocol)),
          )
        }.onFailure {
          rejected += 1
        }
      }
    }
    require(parsed.isNotEmpty()) { "В Xray JSON нет поддерживаемых серверов" }
    return buildSubscription(parsed, emptyList(), rejected)
  }

  private fun xrayTls(stream: JSONObject): JSONObject? {
    val security = stream.optString("security").lowercase()
    if (security !in setOf("tls", "reality")) return null
    val settings = if (security == "reality") {
      stream.optJSONObject("realitySettings") ?: JSONObject()
    } else {
      stream.optJSONObject("tlsSettings") ?: JSONObject()
    }
    val tls = JSONObject()
      .put("enabled", true)
      .put("server_name", settings.optString("serverName"))
      .put("insecure", settings.optBoolean("allowInsecure", false))
    settings.optJSONArray("alpn")?.let { tls.put("alpn", it) }
    settings.optString("fingerprint").takeIf(String::isNotBlank)?.let {
      tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
    }
    if (security == "reality") {
      tls.put(
        "reality",
        JSONObject()
          .put("enabled", true)
          .put("public_key", settings.getString("publicKey"))
          .put("short_id", settings.optString("shortId")),
      )
    }
    return tls
  }

  private fun xrayTransport(stream: JSONObject): JSONObject? =
    when (val network = stream.optString("network").lowercase()) {
      "", "tcp", "raw" -> null
      "ws" -> {
        val settings = stream.optJSONObject("wsSettings") ?: JSONObject()
        JSONObject()
          .put("type", "ws")
          .put("path", settings.optString("path").ifBlank { "/" })
          .put("headers", settings.optJSONObject("headers") ?: JSONObject())
      }
      "grpc" -> {
        val settings = stream.optJSONObject("grpcSettings") ?: JSONObject()
        JSONObject()
          .put("type", "grpc")
          .put("service_name", settings.optString("serviceName"))
          .put("idle_timeout", "30s")
          .put("ping_timeout", "15s")
          .put("permit_without_stream", true)
      }
      "h2", "http" -> {
        val settings = stream.optJSONObject("httpSettings") ?: JSONObject()
        JSONObject()
          .put("type", "http")
          .put("path", settings.optString("path"))
          .put("host", settings.optJSONArray("host") ?: JSONArray())
      }
      else -> error("Xray transport $network пока не поддерживается")
    }

  private fun compileClashDocument(decoded: String): CompiledSubscription? {
    if (!CLASH_DOCUMENT.containsMatchIn(decoded)) return null
    val options = LoaderOptions().apply {
      codePointLimit = MAX_SUBSCRIPTION_SIZE
      maxAliasesForCollections = 32
      nestingDepthLimit = MAX_JSON_DEPTH
      isAllowDuplicateKeys = false
    }
    val root = runCatching {
      Yaml(SafeConstructor(options)).load<Any?>(decoded) as? Map<*, *>
    }.getOrElse { failure ->
      throw IllegalArgumentException(
        "Некорректная Clash/Mihomo YAML-подписка: ${failure.message.orEmpty().take(120)}",
        failure,
      )
    } ?: return null

    val proxyMaps = mutableListOf<Map<*, *>>()
    (root["proxies"] as? List<*>)?.mapNotNullTo(proxyMaps) { it as? Map<*, *> }
    (root["proxy-providers"] as? Map<*, *>)?.values
      ?.mapNotNull { it as? Map<*, *> }
      ?.forEach { provider ->
        (provider["payload"] as? List<*>)?.mapNotNullTo(proxyMaps) {
          it as? Map<*, *>
        }
      }
    require(proxyMaps.isNotEmpty()) { "В Clash/Mihomo YAML нет встроенных proxy-узлов" }

    val errors = mutableListOf<String>()
    val outbounds = proxyMaps.mapIndexedNotNull { index, source ->
      runCatching { parseClashProxy(source, index) }
        .onFailure { errors += "Профиль ${index + 1}: ${it.message}" }
        .getOrNull()
    }
    require(outbounds.isNotEmpty()) {
      errors.firstOrNull() ?: "В Clash/Mihomo YAML нет поддерживаемых узлов"
    }
    return buildSubscription(
      outbounds,
      emptyList(),
      errors.size,
      errors.take(MAX_REPORTED_ERRORS),
    )
  }

  private fun parseClashProxy(source: Map<*, *>, index: Int): ParsedOutbound {
    val type = source.string("type").lowercase()
    val name = source.string("name").ifBlank { "${protocolName(type)} ${index + 1}" }
      .replace(Regex("""[\u0000-\u001F]"""), "")
      .trim()
      .take(64)
    val tag = uniqueTag(name, index)
    val server = source.string("server")
    require(server.isNotBlank()) { "server отсутствует" }
    val outbound = when (type) {
      "ss", "shadowsocks" -> JSONObject()
        .put("type", "shadowsocks")
        .put("tag", tag)
        .put("server", server)
        .put("server_port", source.port())
        .put("method", source.string("cipher"))
        .put("password", source.string("password"))
        .also { json ->
          source.string("plugin").takeIf(String::isNotBlank)?.let {
            json.put("plugin", it)
            source.string("plugin-opts").takeIf(String::isNotBlank)?.let { opts ->
              json.put("plugin_opts", opts)
            }
          }
        }
      "vmess" -> JSONObject()
        .put("type", "vmess")
        .put("tag", tag)
        .put("server", server)
        .put("server_port", source.port())
        .put("uuid", source.string("uuid"))
        .put("security", source.string("cipher").ifBlank { "auto" })
        .put("alter_id", source.int("alterId", source.int("alter-id", 0)))
        .also { json ->
          clashTls(source, required = source.bool("tls")).takeIf {
            it.getBoolean("enabled")
          }?.let { json.put("tls", it) }
          clashTransport(source)?.let { json.put("transport", it) }
        }
      "vless" -> JSONObject()
        .put("type", "vless")
        .put("tag", tag)
        .put("server", server)
        .put("server_port", source.port())
        .put("uuid", source.string("uuid"))
        .also { json ->
          source.string("flow").takeIf(String::isNotBlank)?.let { json.put("flow", it) }
          json.put("tls", clashTls(source, required = true))
          clashTransport(source)?.let { json.put("transport", it) }
        }
      "trojan" -> JSONObject()
        .put("type", "trojan")
        .put("tag", tag)
        .put("server", server)
        .put("server_port", source.port())
        .put("password", source.string("password"))
        .put("tls", clashTls(source, required = true))
        .also { json -> clashTransport(source)?.let { json.put("transport", it) } }
      "hysteria2", "hy2" -> JSONObject()
        .put("type", "hysteria2")
        .put("tag", tag)
        .put("server", server)
        .put("password", source.string("password").ifBlank { source.string("auth") })
        .put("tls", clashTls(source, required = true))
        .also { json ->
          val port = source["port"]?.toString().orEmpty()
          if ('-' in port || ':' in port) {
            json.put("server_ports", JSONArray().put(port.replace('-', ':')))
          } else {
            json.put("server_port", source.port())
          }
          val obfs = source.string("obfs")
          val password = source.string("obfs-password")
          if (obfs.isNotBlank() && password.isNotBlank()) {
            json.put("obfs", JSONObject().put("type", obfs).put("password", password))
          }
        }
      "tuic" -> JSONObject()
        .put("type", "tuic")
        .put("tag", tag)
        .put("server", server)
        .put("server_port", source.port())
        .put("uuid", source.string("uuid"))
        .put("password", source.string("password"))
        .put(
          "congestion_control",
          source.string("congestion-controller").ifBlank { "bbr" },
        )
        .put(
          "udp_relay_mode",
          source.string("udp-relay-mode").ifBlank { "native" },
        )
        .put("tls", clashTls(source, required = true))
      "anytls" -> JSONObject()
        .put("type", "anytls")
        .put("tag", tag)
        .put("server", server)
        .put("server_port", source.port())
        .put("password", source.string("password"))
        .put("tls", clashTls(source, required = true))
      else -> error("тип $type пока не поддерживается")
    }
    return ParsedOutbound(
      outbound,
      ConnectionNode(tag, name, protocolName(outbound.getString("type"))),
    )
  }

  private fun clashTls(source: Map<*, *>, required: Boolean): JSONObject {
    val tls = JSONObject()
      .put("enabled", required || source.bool("tls"))
      .put(
        "server_name",
        source.string("servername")
          .ifBlank { source.string("sni") }
          .ifBlank { source.string("server") },
      )
      .put("insecure", source.bool("skip-cert-verify"))
    (source["alpn"] as? List<*>)?.mapNotNull { it?.toString() }
      ?.takeIf { it.isNotEmpty() }
      ?.let { tls.put("alpn", JSONArray(it)) }
    source.string("client-fingerprint").takeIf(String::isNotBlank)?.let {
      tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
    }
    (source["reality-opts"] as? Map<*, *>)?.let { reality ->
      val publicKey = reality.string("public-key")
      if (publicKey.isNotBlank()) {
        tls.put(
          "reality",
          JSONObject()
            .put("enabled", true)
            .put("public_key", publicKey)
            .put("short_id", reality.string("short-id")),
        )
      }
    }
    return tls
  }

  private fun clashTransport(source: Map<*, *>): JSONObject? =
    when (val network = source.string("network").lowercase()) {
      "", "tcp" -> null
      "ws" -> {
        val options = source["ws-opts"] as? Map<*, *>
        JSONObject()
          .put("type", "ws")
          .put("path", options?.string("path").orEmpty().ifBlank { "/" })
          .put(
            "headers",
            JSONObject().apply {
              (options?.get("headers") as? Map<*, *>)?.forEach { (key, value) ->
                put(key.toString(), value.toString())
              }
            },
          )
      }
      "grpc" -> {
        val options = source["grpc-opts"] as? Map<*, *>
        JSONObject()
          .put("type", "grpc")
          .put(
            "service_name",
            options?.string("grpc-service-name").orEmpty()
              .ifBlank { options?.string("service-name").orEmpty() },
          )
          .put("idle_timeout", "30s")
          .put("ping_timeout", "15s")
          .put("permit_without_stream", true)
      }
      "h2", "http" -> {
        val options = source["h2-opts"] as? Map<*, *>
        JSONObject()
          .put("type", "http")
          .put("path", options?.string("path").orEmpty())
          .put(
            "host",
            JSONArray(
              (options?.get("host") as? List<*>)?.map { it.toString() }.orEmpty(),
            ),
          )
      }
      else -> error("transport $network пока не поддерживается")
    }

  private fun Map<*, *>.string(key: String): String =
    this[key]?.toString()?.trim().orEmpty()

  private fun Map<*, *>.bool(key: String): Boolean =
    when (val value = this[key]) {
      is Boolean -> value
      is Number -> value.toInt() != 0
      else -> value?.toString()?.equals("true", ignoreCase = true) == true
    }

  private fun Map<*, *>.int(key: String, fallback: Int = 0): Int =
    (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: fallback

  private fun Map<*, *>.port(): Int =
    int("port").takeIf { it in 1..65535 } ?: error("port отсутствует или некорректен")

  private fun buildSubscription(
    parsedOutbounds: List<ParsedOutbound>,
    trustTunnelLinks: List<String>,
    rejectedCount: Int,
    rejectedReasons: List<String> = emptyList(),
  ): CompiledSubscription {
    val outbounds = parsedOutbounds.map(ParsedOutbound::json)
    val tags = outbounds.map { it.getString("tag") }
    val root = createBaseConfig(tags, outbounds)
    return CompiledSubscription(
      json = root.toString(2),
      profileCount = outbounds.size,
      rejectedCount = rejectedCount,
      displayName = "Veilark · ${outbounds.size}",
      nodes = parsedOutbounds.map(ParsedOutbound::node),
      trustTunnelLinks = trustTunnelLinks,
      rejectedReasons = rejectedReasons,
    )
  }

  private fun protocolName(type: String): String = when (type) {
    "vless" -> "VLESS"
    "trojan" -> "Trojan"
    "hysteria2" -> "Hysteria 2"
    "vmess" -> "VMess"
    "shadowsocks" -> "Shadowsocks"
    "tuic" -> "TUIC"
    "anytls" -> "AnyTLS"
    else -> type
  }

  private fun parseHostPort(value: String): Pair<String, Int> {
    val host: String
    val portText: String
    if (value.startsWith("[")) {
      val bracket = value.indexOf(']')
      require(bracket > 1 && value.getOrNull(bracket + 1) == ':') {
        "Некорректный IPv6 адрес"
      }
      host = value.substring(1, bracket)
      portText = value.substring(bracket + 2)
    } else {
      val colon = value.lastIndexOf(':')
      require(colon > 0) { "Порт отсутствует" }
      host = value.substring(0, colon)
      portText = value.substring(colon + 1)
    }
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
      ?: error("Некорректный порт")
    return host to port
  }

  private fun decodeBase64(value: String): String {
    val normalized = value.trim().replace('-', '+').replace('_', '/')
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    return Base64.getDecoder().decode(padded).toString(Charsets.UTF_8)
  }

  private fun decodeUrl(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())

  private fun parseVless(uri: ParsedShareUri, tag: String): JSONObject {
    require(uri.userInfo.isNotBlank()) { "VLESS UUID отсутствует" }
    val outbound = JSONObject()
      .put("type", "vless")
      .put("tag", tag)
      .put("server", uri.host)
      .put("server_port", uri.singlePort())
      .put("uuid", uri.userInfo)
    uri.query["flow"]?.takeIf(String::isNotBlank)?.let { outbound.put("flow", it) }
    outbound.put("tls", tls(uri))
    transport(uri)?.let { outbound.put("transport", it) }
    return outbound
  }

  private fun parseTrojan(uri: ParsedShareUri, tag: String): JSONObject {
    require(uri.userInfo.isNotBlank()) { "Trojan password отсутствует" }
    val outbound = JSONObject()
      .put("type", "trojan")
      .put("tag", tag)
      .put("server", uri.host)
      .put("server_port", uri.singlePort())
      .put("password", uri.userInfo)
      .put("tls", tls(uri))
    transport(uri)?.let { outbound.put("transport", it) }
    return outbound
  }

  private fun parseHysteria2(uri: ParsedShareUri, tag: String): JSONObject {
    require(uri.userInfo.isNotBlank()) { "Hysteria 2 password отсутствует" }
    val outbound = JSONObject()
      .put("type", "hysteria2")
      .put("tag", tag)
      .put("server", uri.host)
      .put("password", uri.userInfo)
      .put("tls", tls(uri))
      .put("hop_interval", uri.query["hop-interval"] ?: "20s")

    if (uri.portSpec.contains('-') || uri.portSpec.contains(':')) {
      outbound.put("server_ports", JSONArray().put(uri.portSpec.replace('-', ':')))
    } else {
      outbound.put("server_port", uri.singlePort())
    }

    val obfs = uri.query["obfs"]
    val obfsPassword = uri.query["obfs-password"]
    if (!obfs.isNullOrBlank() && !obfsPassword.isNullOrBlank()) {
      outbound.put(
        "obfs",
        JSONObject().put("type", obfs).put("password", obfsPassword),
      )
    }
    return outbound
  }

  private fun tls(uri: ParsedShareUri): JSONObject {
    val security = uri.query["security"].orEmpty()
    val reality = security == "reality"
    val tlsEnabled = reality || security == "tls" ||
      uri.scheme in setOf("trojan", "hysteria2", "hy2", "tuic", "anytls")
    val tls = JSONObject()
      .put("enabled", tlsEnabled)
      .put("server_name", uri.query["sni"] ?: uri.host)
      .put("insecure", false)

    uri.query["alpn"]?.takeIf(String::isNotBlank)?.let {
      tls.put("alpn", JSONArray(it.split(',')))
    }
    uri.query["fp"]?.takeIf(String::isNotBlank)?.let {
      tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
    }
    if (reality) {
      val publicKey = uri.query["pbk"] ?: error("Reality public key отсутствует")
      tls.put(
        "reality",
        JSONObject()
          .put("enabled", true)
          .put("public_key", publicKey)
          .put("short_id", uri.query["sid"].orEmpty()),
      )
    }
    return tls
  }

  private fun transport(uri: ParsedShareUri): JSONObject? = when (uri.query["type"]) {
    null, "", "tcp" -> null
    "ws" -> JSONObject()
      .put("type", "ws")
      .put("path", uri.query["path"] ?: "/")
      .put(
        "headers",
        JSONObject().apply {
          uri.query["host"]?.takeIf(String::isNotBlank)?.let { put("Host", it) }
        },
      )
    "grpc" -> JSONObject()
      .put("type", "grpc")
      .put("service_name", uri.query["serviceName"].orEmpty())
      .put("idle_timeout", "30s")
      .put("ping_timeout", "15s")
      .put("permit_without_stream", true)
    "http", "h2" -> JSONObject()
      .put("type", "http")
      .put("path", uri.query["path"].orEmpty())
      .put(
        "host",
        JSONArray(uri.query["host"]?.split(',').orEmpty()),
      )
    else -> error("transport ${uri.query["type"]} пока не поддерживается")
  }

  private fun uniqueTag(name: String, index: Int): String {
    val cleaned = name.replace(Regex("""[\u0000-\u001F]"""), "").trim().take(64)
    return "$cleaned · ${index + 1}"
  }

  private companion object {
    const val MAX_SUBSCRIPTION_SIZE = 4 * 1024 * 1024
    const val MAX_PROFILE_LINKS = 5_000
    const val MAX_JSON_DEPTH = 8
    const val MAX_REPORTED_ERRORS = 20
    val CLASH_DOCUMENT = Regex("""(?m)^\s*(proxies|proxy-providers)\s*:""")
    val BASE64_VALUE = Regex("""^[A-Za-z0-9+/=_-]+$""")
    val SCHEME_LINE = Regex(
      """(?i)^(vless|trojan|hysteria2|hy2|vmess|ss|tuic|anytls|tt)://.+""",
    )
  }
}

data class CompiledSubscription(
  val json: String,
  val profileCount: Int,
  val rejectedCount: Int,
  val displayName: String,
  val nodes: List<ConnectionNode>,
  val trustTunnelLinks: List<String>,
  val rejectedReasons: List<String> = emptyList(),
)

data class ConnectionNode(
  val tag: String,
  val name: String,
  val protocol: String,
)

private data class ParsedOutbound(
  val json: JSONObject,
  val node: ConnectionNode,
)

private data class ParsedShareUri(
  val scheme: String,
  val userInfo: String,
  val host: String,
  val portSpec: String,
  val query: Map<String, String>,
  val fragment: String,
) {
  fun singlePort(): Int = portSpec.toIntOrNull()?.takeIf { it in 1..65535 }
    ?: error("Некорректный порт")

  companion object {
    fun parse(value: String): ParsedShareUri {
      val schemeEnd = value.indexOf("://")
      require(schemeEnd > 0) { "Некорректная ссылка" }
      val scheme = value.substring(0, schemeEnd).lowercase()
      val afterScheme = value.substring(schemeEnd + 3)
      val fragmentIndex = afterScheme.indexOf('#')
      val fragmentRaw = if (fragmentIndex >= 0) afterScheme.substring(fragmentIndex + 1) else ""
      val withoutFragment = if (fragmentIndex >= 0) afterScheme.substring(0, fragmentIndex) else afterScheme
      val queryIndex = withoutFragment.indexOf('?')
      val authorityAndPath = if (queryIndex >= 0) {
        withoutFragment.substring(0, queryIndex)
      } else {
        withoutFragment
      }
      val authority = authorityAndPath.substringBefore('/')
      val queryRaw = if (queryIndex >= 0) withoutFragment.substring(queryIndex + 1) else ""
      val at = authority.lastIndexOf('@')
      require(at > 0) { "Отсутствуют учётные данные или адрес" }
      val userInfo = decode(authority.substring(0, at))
      val hostPort = authority.substring(at + 1)
      val host: String
      val port: String
      if (hostPort.startsWith('[')) {
        val bracket = hostPort.indexOf(']')
        require(bracket > 1 && hostPort.getOrNull(bracket + 1) == ':') { "Некорректный IPv6 адрес" }
        host = hostPort.substring(1, bracket)
        port = hostPort.substring(bracket + 2)
      } else {
        val colon = hostPort.lastIndexOf(':')
        require(colon > 0) { "Порт отсутствует" }
        host = hostPort.substring(0, colon)
        port = hostPort.substring(colon + 1)
      }
      require(host.isNotBlank()) { "Адрес сервера отсутствует" }
      val query = queryRaw.split('&')
        .filter(String::isNotBlank)
        .associate { part ->
          val equal = part.indexOf('=')
          val key = decode(if (equal >= 0) part.substring(0, equal) else part)
          val item = decode(if (equal >= 0) part.substring(equal + 1) else "")
          key to item
        }
      return ParsedShareUri(
        scheme = scheme,
        userInfo = userInfo,
        host = host,
        portSpec = port,
        query = query,
        fragment = decode(fragmentRaw),
      )
    }

    private fun decode(value: String): String =
      URLDecoder.decode(value, StandardCharsets.UTF_8.name())
  }
}
