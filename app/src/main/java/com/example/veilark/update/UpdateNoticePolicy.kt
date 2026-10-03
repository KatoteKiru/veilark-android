package com.example.veilark.update

internal fun shouldNotifyRelease(version: Int, lastAttempted: Int): Boolean =
  version > 0 && version > lastAttempted
