package vision.salient.choam.daemon

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfig

private val logger = KotlinLogging.logger {}

/**
 * Action API endpoints — lets the web dashboard (and external tools) trigger
 * operations on the running daemon.
 *
 * POST endpoints run tasks asynchronously and return immediately.
 * GET endpoints return daemon state and activity log.
 */
fun Route.actionApiRoutes(config: ChoamConfig, scheduler: DaemonScheduler?) {

    // --- Daemon status ---

    get("/api/daemon/status") {
        val pid = DaemonState.readPidFile()
        val running = DaemonState.isRunning()
        val schedulerStatus = scheduler?.status() ?: emptyMap()

        call.respond(mapOf(
            "running" to running.toString(),
            "pid" to (pid?.toString() ?: ""),
            "uptime" to if (running) DaemonState.getUptime() else "",
            "paused" to DaemonState.paused.toString(),
            "schedulerTasks" to schedulerStatus.keys.joinToString(","),
            "version" to config.version
        ))
    }

    get("/api/daemon/activity") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        val entries = DaemonState.getRecentActivity(limit)
        call.respond(entries.map { entry ->
            mapOf(
                "timestamp" to entry.timestamp,
                "action" to entry.action,
                "detail" to entry.detail,
                "success" to entry.success.toString()
            )
        })
    }

    // --- Action triggers ---

    post("/api/catalog-sync") {
        if (scheduler == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("status" to "unavailable", "message" to "Daemon scheduler not running.")
            )
            return@post
        }
        if (!scheduler.triggerTask("catalog_freshness")) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("status" to "unavailable", "message" to "Catalog freshness task unavailable.")
            )
            return@post
        }
        DaemonState.logActivity("api_trigger", "Catalog freshness check triggered via API")
        call.respond(mapOf("status" to "started", "task" to "catalog-sync"))
    }

    post("/api/fulfill") {
        call.respond(
            HttpStatusCode.NotImplemented,
            mapOf(
                "status" to "unavailable",
                "message" to "Fulfill is not available via the web API. Run 'choam fulfill' from the CLI."
            )
        )
    }

    post("/api/daemon/pause") {
        DaemonState.paused = true
        DaemonState.logActivity("daemon_pause", "Scheduler paused via API")
        call.respond(mapOf("paused" to "true"))
    }

    post("/api/daemon/resume") {
        DaemonState.paused = false
        DaemonState.logActivity("daemon_resume", "Scheduler resumed via API")
        call.respond(mapOf("paused" to "false"))
    }

    post("/api/dag-sync") {
        if (scheduler == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("status" to "unavailable", "message" to "Daemon scheduler not running.")
            )
            return@post
        }
        if (!scheduler.triggerTask("dag_sync")) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("status" to "unavailable", "message" to "DAG sync task unavailable.")
            )
            return@post
        }
        DaemonState.logActivity("api_trigger", "DAG sync triggered via API")
        call.respond(mapOf("status" to "started", "task" to "dag-sync"))
    }

    post("/api/peer-check") {
        if (scheduler == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("status" to "unavailable", "message" to "Daemon scheduler not running.")
            )
            return@post
        }
        if (!scheduler.triggerTask("peer_reachability")) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("status" to "unavailable", "message" to "Peer reachability task unavailable.")
            )
            return@post
        }
        DaemonState.logActivity("api_trigger", "Peer reachability check triggered via API")
        call.respond(mapOf("status" to "started", "task" to "peer-check"))
    }

    post("/api/drive-check") {
        if (scheduler == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("status" to "unavailable", "message" to "Daemon scheduler not running.")
            )
            return@post
        }
        if (!scheduler.triggerTask("drive_health")) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("status" to "unavailable", "message" to "Drive health task unavailable.")
            )
            return@post
        }
        DaemonState.logActivity("api_trigger", "Drive health check triggered via API")
        call.respond(mapOf("status" to "started", "task" to "drive-check"))
    }

    // --- HTMX fragment for dashboard activity feed ---

    get("/htmx/daemon-activity") {
        call.respondHtml(HttpStatusCode.OK) {
            body {
                val entries = DaemonState.getRecentActivity(10)
                if (entries.isEmpty()) {
                    p { style = "color: #888; font-size: 12px"; +"No daemon activity yet." }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"Time" }; th { +"Task" }; th { +"Detail" }; th { +"Status" }
                            }
                        }
                        tbody {
                            for (entry in entries) {
                                tr {
                                    td { +entry.timestamp.substringAfter("T").substringBefore(".") }
                                    td { +entry.action }
                                    td { style = "font-size: 12px; max-width: 400px; overflow: hidden; text-overflow: ellipsis"; +entry.detail }
                                    td {
                                        if (entry.success) {
                                            span { style = "color: #00cc66"; +"ok" }
                                        } else {
                                            span { style = "color: #cc3333"; +"fail" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
