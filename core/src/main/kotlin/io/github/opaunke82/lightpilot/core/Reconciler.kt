package io.github.opaunke82.lightpilot.core

/**
 * Per-light bookkeeping of what was sent and what the light reported.
 */
data class LinkState(
    val lastSent: String? = null,
    val sentAtMs: Long = 0,
    val retries: Int = 0,
    /** Last mode the light reported, null if unknown or the protocol reports nothing. */
    val lastObserved: String? = null,
)

data class ReconcileResult(
    val link: LinkState,
    /** Mode to send now, or null. */
    val send: String?,
    /** Set when the light changed to a mode the app did not send. */
    val externalChange: String?,
    /** True when the light confirmed the desired mode (only possible with feedback). */
    val confirmed: Boolean,
)

/**
 * Compares the desired mode with what was sent and what the light reports,
 * and decides whether to send. Only sends on a difference (P2).
 *
 * External changes are detected when the reported mode CHANGES to a value
 * the app did not send. A command that got lost leaves the reported mode
 * unchanged, so it is retried instead of being taken as an external change.
 */
object Reconciler {
    const val CONFIRM_WINDOW_MS = 2_000L
    const val MAX_RETRIES = 2
    const val RETRY_RESET_MS = 10_000L

    /**
     * Reports this soon after a command are not taken as external changes:
     * the Karoo sometimes reports the previous mode once more after a command.
     */
    const val EXTERNAL_GRACE_MS = 3_000L

    fun step(
        link: LinkState,
        desired: String?,
        observed: String?,
        connected: Boolean,
        nowMs: Long,
    ): ReconcileResult {
        if (!connected) {
            // Forget what was sent, so the desired mode is sent again after reconnecting.
            return ReconcileResult(LinkState(lastObserved = null), null, null, false)
        }

        val observedChanged = observed != null && observed != link.lastObserved
        val inGrace = link.lastSent != null && nowMs - link.sentAtMs < EXTERNAL_GRACE_MS
        val external = if (
            observedChanged &&
            link.lastObserved != null &&
            observed != link.lastSent &&
            !inGrace
        ) observed else null
        val withObserved = link.copy(lastObserved = observed ?: link.lastObserved)

        if (external != null) {
            // Adopt it: the brain pins this mode, so nothing is sent against the user.
            return ReconcileResult(withObserved.copy(lastSent = external, retries = 0), null, external, false)
        }
        if (desired == null) return ReconcileResult(withObserved, null, null, false)

        // Light reports nothing (BLE): send once per change of the desired mode.
        if (observed == null && link.lastObserved == null) {
            return if (link.lastSent == desired) {
                ReconcileResult(withObserved, null, null, false)
            } else {
                ReconcileResult(withObserved.copy(lastSent = desired, sentAtMs = nowMs, retries = 0), desired, null, false)
            }
        }

        val current = observed ?: link.lastObserved
        if (current == desired) {
            return ReconcileResult(withObserved.copy(lastSent = desired, retries = 0), null, null, true)
        }
        if (link.lastSent != desired) {
            return ReconcileResult(withObserved.copy(lastSent = desired, sentAtMs = nowMs, retries = 0), desired, null, false)
        }
        val waited = nowMs - link.sentAtMs
        if (waited < CONFIRM_WINDOW_MS) return ReconcileResult(withObserved, null, null, false)
        if (link.retries < MAX_RETRIES) {
            return ReconcileResult(
                withObserved.copy(sentAtMs = nowMs, retries = link.retries + 1), desired, null, false,
            )
        }
        if (waited >= RETRY_RESET_MS) {
            return ReconcileResult(withObserved.copy(sentAtMs = nowMs, retries = 0), desired, null, false)
        }
        return ReconcileResult(withObserved, null, null, false)
    }
}
