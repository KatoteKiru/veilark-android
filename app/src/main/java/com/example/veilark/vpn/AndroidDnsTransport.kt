package com.example.veilark.vpn

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Resolves bootstrap names on Android's physical network, outside the VPN. */
internal class AndroidDnsTransport(
  private val networkProvider: () -> Network?,
) : LocalDNSTransport {
  override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

  override fun exchange(ctx: ExchangeContext, message: ByteArray) {
    val network = networkProvider() ?: error("Physical network is unavailable for DNS")
    runBlocking {
      suspendCoroutine { continuation ->
        val signal = CancellationSignal()
        ctx.onCancel(signal::cancel)
        DnsResolver.getInstance().rawQuery(
          network,
          message,
          DnsResolver.FLAG_NO_RETRY,
          Dispatchers.IO.asExecutor(),
          signal,
          object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
              if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
              continuation.resume(Unit)
            }

            override fun onError(error: DnsResolver.DnsException) {
              val cause = error.cause
              if (cause is ErrnoException) {
                ctx.errnoCode(cause.errno)
                continuation.resume(Unit)
              } else {
                continuation.resumeWithException(error)
              }
            }
          },
        )
      }
    }
  }

  override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
    val physicalNetwork = networkProvider()
      ?: error("Physical network is unavailable for DNS")
    runBlocking {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        suspendCoroutine { continuation ->
          val signal = CancellationSignal()
          ctx.onCancel(signal::cancel)
          val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
            override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
              if (rcode == 0) {
                ctx.success(answer.mapNotNull(InetAddress::getHostAddress).joinToString("\n"))
              } else {
                ctx.errorCode(rcode)
              }
              continuation.resume(Unit)
            }

            override fun onError(error: DnsResolver.DnsException) {
              val cause = error.cause
              if (cause is ErrnoException) {
                ctx.errnoCode(cause.errno)
                continuation.resume(Unit)
              } else {
                continuation.resumeWithException(error)
              }
            }
          }
          val type = when {
            network.endsWith("4") -> DnsResolver.TYPE_A
            network.endsWith("6") -> DnsResolver.TYPE_AAAA
            else -> null
          }
          if (type == null) {
            DnsResolver.getInstance().query(
              physicalNetwork, domain, DnsResolver.FLAG_NO_RETRY,
              Dispatchers.IO.asExecutor(), signal, callback,
            )
          } else {
            DnsResolver.getInstance().query(
              physicalNetwork, domain, type, DnsResolver.FLAG_NO_RETRY,
              Dispatchers.IO.asExecutor(), signal, callback,
            )
          }
        }
      } else {
        val answer = try {
          physicalNetwork.getAllByName(domain)
        } catch (_: UnknownHostException) {
          ctx.errorCode(RCODE_NXDOMAIN)
          return@runBlocking
        }
        ctx.success(answer.mapNotNull(InetAddress::getHostAddress).joinToString("\n"))
      }
    }
  }

  private companion object {
    const val RCODE_NXDOMAIN = 3
  }
}
