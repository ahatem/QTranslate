package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single owner of application exit.
 *
 * Every real exit route (main window close with
 * [com.github.ahatem.qtranslate.core.settings.data.CloseButtonBehavior.EXIT], tray Exit, the
 * close dialog's Exit option) converges on [requestShutdown]. It runs the pieces that have real
 * persistence or resource-cleanup responsibility, in order: final window bounds, the main store,
 * the plugin manager, the shared host HTTP client, all off the EDT, and only cancels the
 * application scope once they finish; [exit] runs last.
 *
 * Each step is an injected callback rather than a direct dependency on the concrete classes that
 * own that resource, so the ordering and idempotency contract here can be tested without
 * constructing the real application graph. [com.github.ahatem.qtranslate.app.buildDependencies]
 * wires the real callbacks.
 *
 * Local, synchronous UI/native cleanup (global hotkey backend, tray icon, the frame's own
 * coroutine scope) is not this class's job: the caller performs that itself before invoking
 * [requestShutdown], since only it holds those resources.
 */
class ApplicationShutdown(
    private val persistWindowBounds: suspend (Size, Position) -> Unit,
    private val shutdownMainStore: suspend () -> Unit,
    private val shutdownPluginManager: suspend () -> Unit,
    private val closeHostHttpClient: () -> Unit,
    private val cancelAppScope: () -> Unit,
    private val logger: Logger,
    private val exit: () -> Unit
) {
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    /**
     * Requests application shutdown. A no-op after the first call: a second exit route racing
     * in while teardown is already running (e.g. Exit clicked twice) is ignored rather than
     * repeating the sequence, so store shutdown, plugin shutdown and process exit each happen
     * exactly once regardless of which caller got here first.
     *
     * [windowSize]/[windowPosition] must be captured by the caller while the window is still
     * live: by the time this runs off the EDT the frame may already be disposed.
     */
    fun requestShutdown(windowSize: Size, windowPosition: Position) {
        if (!started.compareAndSet(false, true)) return
        shutdownScope.launch {
            runCatching { persistWindowBounds(windowSize, windowPosition) }
                .onFailure { logger.error("Failed to persist final window bounds", it) }

            runCatching { shutdownMainStore() }
                .onFailure { logger.error("Main store shutdown failed", it) }

            runCatching { shutdownPluginManager() }
                .onFailure { logger.error("Plugin manager shutdown failed", it) }

            runCatching { closeHostHttpClient() }
                .onFailure { logger.error("Failed to close host HTTP client", it) }

            runCatching { cancelAppScope() }
                .onFailure { logger.error("Failed to cancel application scope", it) }

            exit()
        }
    }
}
