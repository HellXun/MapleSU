package dev.maplesu.manager.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.maplesu.manager.runtime.authorization.SuperuserControl

/**
 * Boot reconcile for a failed kernel-side mirror (design §3.2/§3.4, `PENDING_APPLY`).
 *
 * <p>Nothing has to be recreated after a boot: a granted application's client is a plain
 * file in its own library directory, and the restricted entries read the Manager's store
 * on every request. The one thing that *can* be behind is the KSU allowlist mirror - a
 * revoke or grant whose mirror step failed. This receiver retries exactly that, and only
 * when [SuperuserControl.hasPendingMarker] says a mirror actually failed, so a boot does
 * not start a root process to answer "is anything wrong?".
 *
 * <p>The retry is one-shot and bounded: if the transport is still unavailable it leaves
 * the marker in place and the next boot (or the next UI commit) tries again. It never
 * changes a decision - the decision was already written and is already enforced.
 */
class RuntimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SuperuserControl.hasPendingMarker(context)) return
        val pending = goAsync()
        Thread({
            try {
                SuperuserControl.reconcilePending(context)
            } finally {
                runCatching { pending.finish() }
            }
        }, "maplesu-superuser-reconcile").apply { isDaemon = true }.start()
    }
}
