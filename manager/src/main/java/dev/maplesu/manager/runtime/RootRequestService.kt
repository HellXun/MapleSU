package dev.maplesu.manager.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder

/*
 * Retired on purpose: distribution goes through NativeSuBrokerService, which hands
 * out the published endpoint (the resident Ultra supervised process). Keeping this binding
 * closed means there is exactly one
 * public Root channel, and that channel's executor authorizes each caller uid.
 */
class RootRequestService : Service() {
    override fun onBind(intent: Intent): IBinder? = null
}
