package com.example.veilark.profile

object SubscriptionDeletionPolicy {
  fun requireDeletable(origin: SubscriptionOrigin) {
    require(origin != SubscriptionOrigin.BUILT_IN) {
      "Встроенную подписку удалить нельзя"
    }
  }

  fun removesActiveSource(activeSourceId: String?, deletedSourceId: String): Boolean =
    activeSourceId != null && activeSourceId == deletedSourceId
}

/** Deterministically resolves a missing saved selection to an available source. */
object SubscriptionSelectionPolicy {
  fun resolve(preferredId: String?, availableIds: List<String>): String? =
    preferredId?.takeIf(availableIds::contains) ?: availableIds.firstOrNull()
}

/** Prevents an asynchronous refresh from reviving a subscription that was deleted in-flight. */
object SubscriptionRefreshPolicy {
  fun canCommit(sourceId: String, currentSourceIds: Collection<String>): Boolean =
    sourceId.isNotBlank() && sourceId in currentSourceIds
}
