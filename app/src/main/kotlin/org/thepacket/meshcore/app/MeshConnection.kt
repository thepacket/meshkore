package org.thepacket.meshcore.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.thepacket.meshcore.ble.CompanionScanner
import org.thepacket.meshcore.ble.NordicMeshCoreLink

/**
 * Process-scoped owner of the BLE link + [MeshSession]. Living here (rather than in the
 * ViewModel) lets the connection — and the chat/session state — survive Activity recreation
 * and run inside [MeshConnectionService] while the app is backgrounded. There is exactly one
 * connection at a time, so a singleton is the right scope.
 */
object MeshConnection {
    private var initialized = false

    // Process-lifetime scope: outlives any single Activity/ViewModel.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var scanner: CompanionScanner
        private set
    lateinit var link: NordicMeshCoreLink
        private set
    lateinit var session: MeshSession
        private set

    /** Idempotent — safe to call from both the Application and the ViewModel. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        scanner = CompanionScanner(app)
        link = NordicMeshCoreLink(app)
        session = MeshSession(link, scope, ChatStore(app), AdminPrefs(app))
        initialized = true
    }
}
