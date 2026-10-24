package com.example.veilark.core.runtime

/** Matched nine-long native request metadata. This never transports IP packet payloads. */
internal object CoreFairwayRequestCodec {
  fun decode(values: LongArray): CoreFairwaySocketRequest {
    require(values.size == 9) { "Invalid Fairway request metadata length" }
    val protocol = when (values[8]) {
      1L -> CoreFairwaySocketProtocol.ICMP
      6L -> CoreFairwaySocketProtocol.TCP
      17L -> CoreFairwaySocketProtocol.UDP
      else -> throw IllegalArgumentException("Invalid Fairway request protocol")
    }
    require(values[4] in 0L..0xffff_ffffL && values[6] in 0L..0xffff_ffffL) {
      "Invalid Fairway request IPv4 value"
    }
    val validPorts = if (protocol == CoreFairwaySocketProtocol.ICMP) {
      values[5] in 0L..65535L && values[7] == 0L
    } else values[5] in 1L..65535L && values[7] in 1L..65535L
    require(validPorts) {
      "Invalid Fairway request port"
    }
    return CoreFairwaySocketRequest(
      attemptHandle = values[0], networkEpoch = values[1], policyGeneration = values[2],
      flowId = values[3], sourceIpv4 = values[4].toInt(), sourcePort = values[5].toInt(),
      destinationIpv4 = values[6].toInt(), destinationPort = values[7].toInt(), protocol = protocol,
    )
  }

  fun encode(request: CoreFairwaySocketRequest): LongArray = longArrayOf(
    request.attemptHandle, request.networkEpoch, request.policyGeneration, request.flowId,
    request.sourceIpv4.toLong() and 0xffff_ffffL, request.sourcePort.toLong(),
    request.destinationIpv4.toLong() and 0xffff_ffffL, request.destinationPort.toLong(),
    request.protocol.number.toLong(),
  )
}
