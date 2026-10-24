package com.example.veilark.core.runtime

import java.util.Locale

/** Supported RFC7065 subset: UDP TURN, IPv4 literals or ASCII DNS names.
 * No TLS/TCP downgrade, URI credentials, implicit DNS, or vendor bootstrap URL.
 */
internal class CoreTurnEndpoint private constructor(val host: String, val port: Int) {
  override fun toString(): String = "CoreTurnEndpoint(redacted)"

  companion object {
    private const val ERROR = "Unsupported TURN endpoint."
    private val URI = Regex("(?i:turn):([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?(?:\\?transport=udp)?")
    private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    fun parse(value: String): CoreTurnEndpoint {
      require(value.length <= 320) { ERROR }
      val match = URI.matchEntire(value) ?: throw IllegalArgumentException(ERROR)
      val host = match.groupValues[1].lowercase(Locale.ROOT).removeSuffix(".")
      require(host.isNotEmpty() && host.length <= 253) { ERROR }
      val labels = host.split('.')
      if (host.all { it == '.' || it in '0'..'9' }) {
        require(labels.size == 4 && labels.all {
          val octet = it.toIntOrNull()
          octet != null && octet in 0..255 && octet.toString() == it
        }) { ERROR }
        require(labels[0].toInt() in 1..223 && host != "255.255.255.255") { ERROR }
      } else {
        require(labels.size >= 2 && labels.all { LABEL.matches(it) }) { ERROR }
      }
      val rawPort = match.groupValues[2]
      val port = if (rawPort.isEmpty()) 3478 else rawPort.toIntOrNull() ?: 0
      require(port in 1..65535 && (rawPort.isEmpty() || rawPort == port.toString())) { ERROR }
      return CoreTurnEndpoint(host, port)
    }
  }
}

/** Short-lived, in-memory credentials supplied by an authorized provisioning path.
 * This object validates syntax/lifetime, not provider authorization or reachability.
 * No preference/JSON serialization and no automatically scheduled token refresh.
 */
internal class CoreTurnGrant private constructor(
  val endpoint: CoreTurnEndpoint,
  private val issuedAtMillis: Long,
  private val expiresAtMillis: Long,
  private val username: ByteArray,
  private val password: ByteArray,
) : AutoCloseable {
  private var closed = false

  @Synchronized fun remainingMillis(nowMillis: Long): Long {
    check(!closed && nowMillis >= issuedAtMillis && nowMillis < expiresAtMillis) { ERROR }
    return expiresAtMillis - nowMillis
  }

  /** Native must synchronously copy borrowed credentials; copies are wiped on
   * success and exception. No caller callback or IO runs under the grant monitor.
   */
  fun <T> withCredentialBytes(nowMillis: Long, block: (ByteArray, ByteArray, Long) -> T): T {
    val (userCopy, passwordCopy, remaining) = synchronized(this) {
      val remaining = remainingMillis(nowMillis)
      val userCopy = username.copyOf()
      val passwordCopy = try { password.copyOf() } catch (error: Throwable) {
        userCopy.fill(0)
        throw error
      }
      Triple(userCopy, passwordCopy, remaining)
    }
    try { return block(userCopy, passwordCopy, remaining) }
    finally { userCopy.fill(0); passwordCopy.fill(0) }
  }

  @Synchronized override fun close() {
    closed = true
    username.fill(0)
    password.fill(0)
  }

  override fun toString(): String = "CoreTurnGrant(redacted)"

  companion object {
    private const val ERROR = "TURN credentials unavailable."

    fun create(uri: String, usernameUtf8: ByteArray, passwordUtf8: ByteArray,
      lifetimeSeconds: Long, nowMillis: Long): CoreTurnGrant {
      val endpoint = CoreTurnEndpoint.parse(uri)
      // Explicit printable-ASCII subset avoids silently changing SASL-prepared bytes.
      require(usernameUtf8.size in 1..512 && passwordUtf8.size in 1..1024 &&
        lifetimeSeconds in 1..86_400 && nowMillis >= 0 &&
        nowMillis <= Long.MAX_VALUE - lifetimeSeconds * 1000) { ERROR }
      val username = usernameUtf8.copyOf()
      val password = try { passwordUtf8.copyOf() } catch (error: Throwable) {
        username.fill(0)
        throw error
      }
      try {
        require(username.all { it.toInt() in 33..126 } && password.all { it.toInt() in 32..126 }) { ERROR }
        return CoreTurnGrant(endpoint, nowMillis, nowMillis + lifetimeSeconds * 1000, username, password)
      } catch (error: Throwable) {
        username.fill(0); password.fill(0)
        throw error
      }
    }
  }
}
