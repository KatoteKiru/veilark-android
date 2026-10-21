package com.example.veilark.lab

import android.app.Activity
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import com.example.veilark.BuildConfig
import com.example.veilark.core.runtime.CoreBetaManager
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.profile.CoreProfileStore
import com.example.veilark.profile.CoreArgonProfile
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.vpn.ConnectionState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** No Compose/UI workflow, networking substitute or native lifecycle substitute. */
class FairwayProductActivity : Activity()

/** Self instrumentation exists ONLY in the separate testOnly fairwayLab variant.
 * Imports one synthetic localhost peer profile into this package's real Keystore,
 * starts actual manager/coordinator/VpnService and awaits independent client I/O.
 */
class FairwayProductStand : Instrumentation() {
  private var case = "unspecified"
  private var observeIdle = false
  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    case = arguments?.getString("case", "unspecified") ?: "unspecified"
    observeIdle = arguments?.getString("observe_idle") == "true"
    start()
  }

  override fun onStart() {
    val context = targetContext
    val result = Bundle()
    var activity: Activity? = null
    var registered = false
    val stop = CountDownLatch(1)
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) { stop.countDown() }
    }
    var failure: Throwable? = null
    var stage = "ADMISSION"
    try {
      check(BuildConfig.DEBUG && BuildConfig.FAIRWAY_LAB_ENABLED && !BuildConfig.SELF_UPDATE_ENABLED)
      check(context.packageName.endsWith(".fairwaylab"))
      check(case.matches(Regex("product-service-[0-9a-f]{12}")))
      check(VpnService.prepare(context) == null)
      check(CoreBetaManager.stopped.value)
      check(!CoreProfileStore.exists(context)) { "Refuse existing lab identity" }
      // Only this testOnly variant exposes a per-run unpredictable action to ADB.
      // NOT_EXPORTED would reject the shell UID and never exercise manager Stop.
      context.registerReceiver(receiver, IntentFilter(context.packageName + ".PRODUCT_STOP." + case), Context.RECEIVER_EXPORTED)
      registered = true
      activity = startActivitySync(Intent(context, FairwayProductActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      stage = "PROFILE_IMPORT"
      val file = File(context.filesDir, "fairway-product-peer.json")
      check(file.isFile && file.length() in 1..16384)
      val bytes = file.readBytes()
      try {
        CoreArgonProfile.parse(bytes).use {
          check(it.endpointIpv4 == "10.0.2.2" && it.mtu == 1280 && it.dnsIpv4 == listOf("77.88.8.8")) {
            "Refuse non-loopback laboratory peer"
          }
        }
        CoreProfileStore.import(context, bytes)
      } finally { bytes.fill(0); check(file.delete()) }
      check(context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE).edit()
        .putString("core_routing_mode", ProfileSelection.ROUTING_RU_DIRECT)
        .putString("application_mode", ProfileSelection.APPS_ONLY)
        .putStringSet("selected_applications", setOf("uk.veilark.lab.tunclient"))
        .commit())
      stage = "START_SERVICE"
      runBlocking {
        CoreBetaManager.start(context)
        withTimeout(20000) {
          CoreBetaManager.state.first { it == ConnectionState.Connected || it == ConnectionState.Failed }
        }
      }
      check(CoreBetaManager.state.value == ConnectionState.Connected) { "Actual product service failed to connect" }
      val routeLog = TechnicalLogStore.entries.value.lastOrNull {
        it.component == "CORE" && it.message.contains("routing_mode=")
      }?.message.orEmpty()
      check(routeLog.contains("routing_mode=ru_direct route_count=1 excluded_count=0 partial_ru=false direct_ru_ipv4=45223232")) {
        "Actual service did not apply complete native RU policy"
      }
      result.putBoolean("product_full_ru_policy_applied", true)
      if (observeIdle) {
        stage = "IDLE_OBSERVATION"
        Thread.sleep(3000) // Lab-only startup settling; no product timer is added.
        val uid = Process.myUid()
        val tx = TrafficStats.getUidTxPackets(uid)
        val rx = TrafficStats.getUidRxPackets(uid)
        val elapsed = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        Thread.sleep(10000)
        val cpuMs = Process.getElapsedCpuTime() - cpu
        val elapsedMs = SystemClock.elapsedRealtime() - elapsed
        result.putLong("idle_elapsed_ms", elapsedMs)
        result.putLong("idle_cpu_ms", cpuMs)
        val afterTx = TrafficStats.getUidTxPackets(uid)
        val afterRx = TrafficStats.getUidRxPackets(uid)
        if (tx >= 0 && afterTx >= tx) result.putLong("idle_uid_tx_packets", afterTx - tx)
        if (rx >= 0 && afterRx >= rx) result.putLong("idle_uid_rx_packets", afterRx - rx)
        result.putBoolean("idle_cpu_below_five_percent", elapsedMs >= 10000 && cpuMs * 20 <= elapsedMs)
        check(CoreBetaManager.state.value == ConnectionState.Connected)
      }
      stage = "CLIENTS"
      Log.i("FairwayProductLab", "PRODUCT_READY case=$case; actual CoreBetaVpnService; full RU; client UID only")
      check(stop.await(50, TimeUnit.SECONDS)) { "Independent client completion timed out" }
      check(CoreBetaManager.state.value == ConnectionState.Connected) { "Product service terminated during clients" }
    } catch (error: Throwable) {
      failure = error
      result.putString("failure_stage", stage)
    }
    finally {
      try {
        CoreBetaManager.stop(context)
        runBlocking { withTimeout(10000) { CoreBetaManager.stopped.first { it } } }
        result.putBoolean("product_resources_released", true)
        if (CoreProfileStore.exists(context)) CoreProfileStore.deleteSelected(context)
      } catch (error: Throwable) {
        if (failure == null) failure = error else failure!!.addSuppressed(error)
      }
      if (registered) context.unregisterReceiver(receiver)
      activity?.let { active -> runOnMainSync { active.finish() } }
    }
    result.putString("verdict", if (failure == null) "PASS" else "FAIL")
    result.putInt("native_begin_count", TechnicalLogStore.entries.value.count {
      it.component == "CORE" && Regex("(?:^| )stage=CORE_BEGIN(?: |$)").containsMatchIn(it.message)
    })
    result.putInt("reconnect_backoff_count", TechnicalLogStore.entries.value.count {
      it.component == "CORE" && Regex("(?:^| )reconnect_backoff_ms=[0-9]+(?: |$)").containsMatchIn(it.message)
    })
    result.putString("service_stages", TechnicalLogStore.entries.value
      .filter { it.component == "CORE" }.flatMap {
        Regex("(?:stage|native_status|native_reason)=[A-Za-z0-9_()/+\\-]+").findAll(it.message).map { match -> match.value }.toList()
      }.takeLast(24).joinToString(";"))
    // No exception messages/stack traces/profile/key content in the receipt.
    failure?.let { result.putString("failure_class", it.javaClass.simpleName) }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
  }
}
