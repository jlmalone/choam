package vision.salient.choam.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.daemon.DaemonScheduler
import vision.salient.choam.daemon.actionApiRoutes

fun Application.configureRouting(config: ChoamConfig, scheduler: DaemonScheduler? = null) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; encodeDefaults = true })
    }

    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Head)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Range)
        exposeHeader(HttpHeaders.ContentLength)
        exposeHeader(HttpHeaders.AcceptRanges)
        exposeHeader(HttpHeaders.ContentRange)
    }

    routing {
        get("/") {
            call.respondHtml(HttpStatusCode.OK) { dashboardPage(config) }
        }

        get("/search") {
            val query = call.request.queryParameters["q"] ?: ""
            val ext = call.request.queryParameters["ext"] ?: ""
            val machine = call.request.queryParameters["machine"] ?: ""
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respondHtml(HttpStatusCode.OK) { searchPage(config, query, ext, machine, limit) }
        }

        get("/drives") {
            call.respondHtml(HttpStatusCode.OK) { drivesPage(config) }
        }

        get("/history") {
            call.respondHtml(HttpStatusCode.OK) { historyPage(config) }
        }

        get("/queue") {
            val error = call.request.queryParameters["error"]
            call.respondHtml(HttpStatusCode.OK) { queuePage(config, error) }
        }

        // Daemon health endpoint (reads health file, no daemon IPC needed)
        get("/api/daemon/health") {
            val health = vision.salient.choam.daemon.DaemonState.readHealth()
            if (health != null) {
                call.respond(HttpStatusCode.OK, health)
            } else {
                call.respondText("""{"status":"stopped"}""", ContentType.Application.Json, HttpStatusCode.OK)
            }
        }

        // JSON API for queue (programmatic access)
        get("/api/queue") {
            val queue = vision.salient.choam.sync.TransferQueueStore()
            call.respond(HttpStatusCode.OK, queue.loadAll())
        }

        // Queue actions: retry, cancel, clear, retry-all
        post("/api/queue/{id}/retry") {
            val id = call.parameters["id"] ?: ""
            val queue = vision.salient.choam.sync.TransferQueueStore()
            queue.update(id) { it.copy(
                status = vision.salient.choam.sync.TransferStatus.PENDING,
                error = null
            ) }
            call.respondRedirect("/queue")
        }

        post("/api/queue/{id}/cancel") {
            val id = call.parameters["id"] ?: ""
            val queue = vision.salient.choam.sync.TransferQueueStore()
            queue.cancel(id)
            call.respondRedirect("/queue")
        }

        post("/api/queue/retry-all") {
            val queue = vision.salient.choam.sync.TransferQueueStore()
            val entries = queue.loadAll()
            for (entry in entries) {
                if (entry.status == vision.salient.choam.sync.TransferStatus.FAILED) {
                    queue.update(entry.id) { it.copy(
                        status = vision.salient.choam.sync.TransferStatus.PENDING,
                        error = null
                    ) }
                }
            }
            call.respondRedirect("/queue")
        }

        post("/api/queue/clear") {
            val queue = vision.salient.choam.sync.TransferQueueStore()
            queue.clear(completedOnly = true)
            call.respondRedirect("/queue")
        }

        // Live progress for a running queue transfer
        get("/api/queue/{id}/progress") {
            val id = call.parameters["id"] ?: ""
            val progressFile = java.io.File(System.getProperty("user.home"), ".choam/queue-progress-$id.json")
            if (progressFile.exists()) {
                call.respondText(progressFile.readText(), ContentType.Application.Json)
            } else {
                call.respondText("{}", ContentType.Application.Json)
            }
        }

        // Process queue — triggers the daemon's queue processor only.
        // Web UI never spawns its own queue processor thread.
        post("/api/queue/process") {
            if (scheduler != null) {
                scheduler.triggerTask("queue_processor")
                call.respondRedirect("/queue")
            } else {
                // No daemon running — refuse to process. Operator must start daemon or use CLI.
                call.respondRedirect("/queue?error=${java.net.URLEncoder.encode(
                    "No daemon running. Start with 'choam daemon start' or process via 'choam queue --run'.", "UTF-8"
                )}")
            }
        }

        get("/inspect/{cid}") {
            val cid = call.parameters["cid"] ?: ""
            call.respondHtml(HttpStatusCode.OK) { inspectPage(config, cid) }
        }

        get("/federation") {
            call.respondHtml(HttpStatusCode.OK) { federationPage(config) }
        }

        get("/report") {
            call.respondHtml(HttpStatusCode.OK) { reportPage(config) }
        }

        get("/network") {
            call.respondHtml(HttpStatusCode.OK) { networkPage(config) }
        }

        get("/media") {
            val query = call.request.queryParameters["q"] ?: ""
            val ext = call.request.queryParameters["ext"] ?: ""
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respondHtml(HttpStatusCode.OK) { mediaPage(config, query, ext, limit) }
        }

        // HTMX partial endpoints (return HTML fragments, not full pages)
        get("/htmx/machines") {
            call.respondHtml(HttpStatusCode.OK) { machineStatusFragment(config) }
        }

        get("/htmx/stats-cards") {
            call.respondHtml(HttpStatusCode.OK) { statsCardsFragment() }
        }

        get("/htmx/catalog-stats") {
            call.respondHtml(HttpStatusCode.OK) { catalogStatsFragment(config) }
        }

        get("/htmx/replication") {
            call.respondHtml(HttpStatusCode.OK) { replicationFragment(config) }
        }

        get("/htmx/federation-summary") {
            call.respondHtml(HttpStatusCode.OK) { federationSummaryFragment(config) }
        }

        get("/htmx/search-results") {
            val query = call.request.queryParameters["q"] ?: ""
            val ext = call.request.queryParameters["ext"] ?: ""
            val machine = call.request.queryParameters["machine"] ?: ""
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respondHtml(HttpStatusCode.OK) { searchResultsFragment(config, query, ext, machine, limit) }
        }

        get("/htmx/drive-status") {
            call.respondHtml(HttpStatusCode.OK) { driveStatusFragment(config) }
        }

        // Report section fragments — each loads independently
        get("/htmx/report/coverage") {
            call.respondHtml(HttpStatusCode.OK) { reportCoverageFragment() }
        }
        get("/htmx/report/replication") {
            call.respondHtml(HttpStatusCode.OK) { reportReplicationFragment(config) }
        }
        get("/htmx/report/copy-distribution") {
            call.respondHtml(HttpStatusCode.OK) { reportCopyDistributionFragment() }
        }
        get("/htmx/report/risk") {
            call.respondHtml(HttpStatusCode.OK) { reportRiskFragment() }
        }
        get("/htmx/report/staleness") {
            call.respondHtml(HttpStatusCode.OK) { reportStalenessFragment() }
        }
        get("/htmx/report/transfer-speeds") {
            call.respondHtml(HttpStatusCode.OK) { reportTransferSpeedsFragment(config) }
        }
        get("/htmx/report/geo-diversity") {
            call.respondHtml(HttpStatusCode.OK) { reportGeoDiversityFragment() }
        }
        get("/htmx/report/content-classes") {
            call.respondHtml(HttpStatusCode.OK) { reportContentClassesFragment() }
        }
        get("/htmx/report/dedup") {
            call.respondHtml(HttpStatusCode.OK) { reportDedupFragment() }
        }
        get("/htmx/report/recommendations") {
            call.respondHtml(HttpStatusCode.OK) { reportRecommendationsFragment(config) }
        }

        get("/htmx/media-results") {
            val query = call.request.queryParameters["q"] ?: ""
            val ext = call.request.queryParameters["ext"] ?: ""
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respondHtml(HttpStatusCode.OK) { mediaResultsFragment(query, ext, limit) }
        }

        // Content streaming (HTTP range requests for Jellyfin compatibility)
        streamingRoutes(config)

        // Daemon action API + activity feed
        actionApiRoutes(config, scheduler)

        // PWA manifest
        get("/manifest.json") {
            call.respondText("""
                {
                    "name": "CHOAM",
                    "short_name": "CHOAM",
                    "description": "Cross-Host Orchestrated Asset Management",
                    "start_url": "/",
                    "display": "standalone",
                    "background_color": "#0a0a0a",
                    "theme_color": "#00cc66",
                    "icons": [
                        {"src": "/api/icon/192", "sizes": "192x192", "type": "image/svg+xml"},
                        {"src": "/api/icon/512", "sizes": "512x512", "type": "image/svg+xml"}
                    ]
                }
            """.trimIndent(), ContentType.Application.Json)
        }

        // PWA icon (generated SVG → serves as PNG substitute)
        get("/api/icon/{size}") {
            val size = call.parameters["size"]?.toIntOrNull() ?: 192
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 $size $size">
                <rect width="$size" height="$size" fill="#0a0a0a"/>
                <text x="${size/2}" y="${size/2 + size/8}" text-anchor="middle" font-family="monospace" font-size="${size/4}" font-weight="bold" fill="#00cc66">C</text>
            </svg>"""
            call.respondText(svg, ContentType("image", "svg+xml"))
        }

        // API endpoints (JSON)
        get("/api/health") {
            call.respond(mapOf("status" to "ok", "version" to config.version))
        }
    }
}
