package vision.salient.choam.web

import kotlinx.html.*
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.sync.TransferQueueStore
import vision.salient.choam.sync.TransferQueueEntry
import vision.salient.choam.sync.TransferStatus
import java.io.File
import java.time.Duration
import java.time.Instant

fun HTML.queuePage(config: ChoamConfig, error: String? = null) = layout("Transfer Queue", "queue") {
    val queue = TransferQueueStore()
    val entries = queue.loadAll()

    h1 { +"Transfer Queue" }

    if (error != null) {
        div {
            style = "background: rgba(255,60,60,0.15); border: 1px solid #ff3c3c; border-radius: 6px; padding: 12px 16px; margin-bottom: 16px; color: #ff6b6b; font-size: 13px;"
            strong { +"Queue processing failed: " }
            +error
        }
    }

    val pending = entries.count { it.status == TransferStatus.PENDING }
    val running = entries.count { it.status == TransferStatus.RUNNING }
    val failed = entries.count { it.status == TransferStatus.FAILED }
    val completed = entries.count { it.status == TransferStatus.COMPLETED }
    val cancelled = entries.count { it.status == TransferStatus.CANCELLED }
    val totalBytes = entries.filter { it.status != TransferStatus.CANCELLED }
        .sumOf { it.totalBytes.coerceAtLeast(resolveFileSize(it)) }

    // Summary cards
    div("grid") {
        div("card") {
            h3 { +"Pending" }
            div("value") { +"$pending" }
        }
        div("card") {
            h3 { +"Running" }
            div("value status-info") { +"$running" }
        }
        div("card") {
            h3 { +"Failed" }
            div("value ${if (failed > 0) "status-err" else ""}") { +"$failed" }
        }
        div("card") {
            h3 { +"Completed" }
            div("value status-ok") { +"$completed" }
        }
        if (totalBytes > 0) {
            div("card") {
                h3 { +"Total Size" }
                div("value") { +ProgressMonitor.formatBytes(totalBytes) }
            }
        }
    }

    // Daemon status indicator
    val health = vision.salient.choam.daemon.DaemonState.readHealth()
    val daemonRunning = health != null && !vision.salient.choam.daemon.DaemonState.isHealthStale()
    div {
        style = "margin: 12px 0; padding: 8px 12px; border-radius: 4px; font-size: 13px; " +
            if (daemonRunning) "background: #1a3a1a; border: 1px solid var(--green);"
            else "background: #3a1a1a; border: 1px solid #ff5555;"
        if (daemonRunning) {
            +"Daemon: running (PID ${health!!.pid}, ${health.state})"
            if (health.activeTransferName != null) {
                +" — transferring ${health.activeTransferName}"
            }
            if (health.lastQueueResult != null) {
                span {
                    style = "margin-left: 12px; color: var(--text-dim);"
                    +"Last run: ${health.lastQueueResult}"
                }
            }
        } else {
            +"Daemon: not running — queue processing disabled. Start with "
            code { +"choam daemon start" }
        }
    }

    // Action buttons
    div {
        style = "display: flex; gap: 8px; margin: 16px 0; align-items: center; flex-wrap: wrap;"
        if (daemonRunning && (pending > 0 || failed > 0)) {
            form {
                method = FormMethod.post
                action = "/api/queue/process"
                button { +"Process Queue (${pending + failed})" }
            }
        } else if (!daemonRunning && (pending > 0 || failed > 0)) {
            span {
                style = "color: var(--text-dim); font-size: 12px;"
                +"Start daemon to process ${pending + failed} transfer(s)"
            }
        }
        if (failed > 0) {
            form {
                method = FormMethod.post
                action = "/api/queue/retry-all"
                button {
                    style = "background: var(--bg-card); border: 1px solid var(--green);"
                    +"Retry All Failed ($failed)"
                }
            }
        }
        if (completed > 0 || cancelled > 0) {
            form {
                method = FormMethod.post
                action = "/api/queue/clear"
                button {
                    style = "background: var(--bg-hover); color: var(--text-dim);"
                    +"Clear Completed"
                }
            }
        }
        if (running > 0) {
            span {
                style = "color: var(--text-dim); font-size: 12px; margin-left: 12px;"
                +"Auto-refreshing while running"
            }
        }
    }

    // Live progress polling for running transfers
    val runningIds = entries.filter { it.status == TransferStatus.RUNNING }.map { it.id }
    if (runningIds.isNotEmpty()) {
        script {
            unsafe {
                raw("""
                    function formatBytes(b) {
                        if (b >= 1073741824) return (b/1073741824).toFixed(1) + ' GB';
                        if (b >= 1048576) return (b/1048576).toFixed(1) + ' MB';
                        if (b >= 1024) return (b/1024).toFixed(1) + ' KB';
                        return b + ' B';
                    }
                    function formatSpeed(b) { return b > 0 ? formatBytes(b) + '/s' : ''; }
                    function updateProgress() {
                        ${runningIds.joinToString("\n") { id -> """
                        fetch('/api/queue/$id/progress').then(r=>r.json()).then(d=>{
                            var el = document.getElementById('progress-$id');
                            if (el && d.bytesTransferred) {
                                var parts = [];
                                if (d.totalFiles > 1) {
                                    var overallPct = d.overallTotalBytes > 0 ? Math.round(d.overallBytesTransferred * 100 / d.overallTotalBytes) : 0;
                                    parts.push(d.filesCompleted + '/' + d.totalFiles + ' files');
                                    parts.push(formatBytes(d.overallBytesTransferred) + ' / ' + formatBytes(d.overallTotalBytes));
                                    parts.push(overallPct + '%');
                                } else {
                                    var pct = d.totalBytes > 0 ? Math.round(d.bytesTransferred * 100 / d.totalBytes) : 0;
                                    parts.push(formatBytes(d.bytesTransferred) + ' / ' + formatBytes(d.totalBytes));
                                    parts.push(pct + '%');
                                }
                                if (d.speedBytesPerSec > 0) parts.push(formatSpeed(d.speedBytesPerSec));
                                if (d.fileName) parts.push(d.fileName);
                                el.textContent = parts.join('  ');
                            }
                        }).catch(function(){});
                        """ }}
                    }
                    updateProgress();
                    setInterval(updateProgress, 3000);
                    setTimeout(function(){ location.reload(); }, 30000);
                """.trimIndent())
            }
        }
    }

    if (entries.isEmpty()) {
        p { +"No transfers queued. Use "; code { +"choam send <file> <dest> --queue" }; +" to add transfers." }
        return@layout
    }

    // Active & pending
    val active = entries.filter { it.status in listOf(TransferStatus.RUNNING, TransferStatus.PENDING, TransferStatus.FAILED) }
    if (active.isNotEmpty()) {
        h2 { +"Active & Pending" }
        queueTable(active, showActions = true)
    }

    // Completed & cancelled
    val done = entries.filter { it.status in listOf(TransferStatus.COMPLETED, TransferStatus.CANCELLED) }
    if (done.isNotEmpty()) {
        h2 { +"Completed & Cancelled" }
        queueTable(done, showActions = true)
    }
}

private fun resolveFileSize(entry: TransferQueueEntry): Long {
    if (entry.totalBytes > 0) return entry.totalBytes
    val f = File(entry.sourcePath)
    return if (f.isFile) f.length()
    else if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    else 0L
}

private fun formatElapsed(startedAt: String?): String {
    if (startedAt == null) return ""
    return try {
        val start = Instant.parse(startedAt)
        val elapsed = Duration.between(start, Instant.now())
        ProgressMonitor.formatDuration(elapsed)
    } catch (_: Exception) { "" }
}

private fun BODY.queueTable(entries: List<TransferQueueEntry>, showActions: Boolean = false) {
    table {
        thead {
            tr {
                th { +"ID" }
                th { +"Status" }
                th { +"Mode" }
                th { +"Source" }
                th { +"Destination" }
                th { +"Size" }
                th { +"Elapsed" }
                th { +"Info" }
                if (showActions) th { +"Actions" }
            }
        }
        tbody {
            for (entry in entries) {
                tr {
                    td { code { +entry.id } }
                    td {
                        val cls = when (entry.status) {
                            TransferStatus.PENDING -> "status-warn"
                            TransferStatus.RUNNING -> "status-info"
                            TransferStatus.COMPLETED -> "status-ok"
                            TransferStatus.FAILED -> "status-err"
                            TransferStatus.CANCELLED -> ""
                        }
                        span(cls) { +entry.status.name.lowercase() }
                        if (entry.mode.name == "MOVE") {
                            +" "
                            span {
                                style = "font-size: 10px; color: var(--text-dim);"
                                +"(move)"
                            }
                        }
                    }
                    td { +entry.priority.name.lowercase() }
                    td {
                        val name = File(entry.sourcePath).name
                        if (name.length > 40) +"${name.take(37)}..." else +name
                    }
                    td {
                        val dest = "${entry.destinationMachine}:${entry.destinationPath}"
                        if (dest.length > 45) +"${dest.take(42)}..." else +dest
                    }
                    td {
                        val size = resolveFileSize(entry)
                        if (size > 0) +ProgressMonitor.formatBytes(size) else +"?"
                    }
                    td {
                        val elapsed = when (entry.status) {
                            TransferStatus.RUNNING -> formatElapsed(entry.startedAt)
                            TransferStatus.COMPLETED -> {
                                if (entry.startedAt != null && entry.completedAt != null) {
                                    try {
                                        ProgressMonitor.formatDuration(
                                            Duration.between(Instant.parse(entry.startedAt), Instant.parse(entry.completedAt))
                                        )
                                    } catch (_: Exception) { "" }
                                } else ""
                            }
                            else -> ""
                        }
                        +elapsed
                    }
                    td {
                        if (entry.status == TransferStatus.RUNNING) {
                            // Live progress placeholder — filled by JS
                            span {
                                id = "progress-${entry.id}"
                                style = "color: var(--green); font-size: 12px;"
                                +"loading..."
                            }
                        } else if (entry.error != null) {
                            val err = entry.error!!
                            val isDeferred = entry.status == TransferStatus.PENDING && err.startsWith("Deferred:")
                            // SourceGuard errors get a category badge
                            val sgPrefix = listOf("[SG_ACQUIRE]", "[SG_WAL]", "[SG_VERIFY]")
                                .firstOrNull { err.startsWith(it) }
                            if (sgPrefix != null) {
                                span {
                                    style = "background: #7c3aed; color: white; padding: 1px 4px; border-radius: 3px; font-size: 10px; margin-right: 4px;"
                                    +sgPrefix.removeSurrounding("[", "]")
                                }
                            } else if (isDeferred) {
                                span {
                                    style = "background: #b45309; color: white; padding: 1px 4px; border-radius: 3px; font-size: 10px; margin-right: 4px;"
                                    +"DEFERRED"
                                }
                            }
                            // Error display — all text escaped via kotlinx.html builders
                            val displayErr = if (sgPrefix != null) err.removePrefix("$sgPrefix ") else err
                            val color = if (isDeferred) "var(--yellow, #b45309)" else "var(--red, #ff5555)"
                            if (displayErr.length > 60) {
                                details {
                                    style = "font-size:12px;color:$color;cursor:pointer"
                                    summary {
                                        val summaryText = if (displayErr.length > 50) displayErr.take(47) + "..." else displayErr
                                        +summaryText
                                    }
                                    pre {
                                        style = "white-space:pre-wrap;margin:4px 0;font-size:11px;color:var(--text-dim)"
                                        +displayErr  // Escaped by kotlinx.html
                                    }
                                }
                            } else {
                                span(if (isDeferred) "status-warn" else "status-err") {
                                    title = err
                                    +displayErr
                                }
                            }
                        } else if (entry.status == TransferStatus.COMPLETED && entry.bytesTransferred > 0) {
                            span("status-ok") {
                                +"${ProgressMonitor.formatBytes(entry.bytesTransferred)} transferred"
                            }
                        } else {
                            span {
                                style = "color: var(--text-dim); font-size: 12px;"
                                +entry.createdAt.substringBefore("T")
                            }
                        }
                    }
                    if (showActions) {
                        td {
                            div {
                                style = "display: flex; gap: 4px;"
                                if (entry.status in listOf(TransferStatus.FAILED, TransferStatus.CANCELLED)) {
                                    form {
                                        method = FormMethod.post
                                        action = "/api/queue/${entry.id}/retry"
                                        style = "display: inline;"
                                        button {
                                            style = "padding: 2px 8px; font-size: 12px;"
                                            +if (entry.status == TransferStatus.CANCELLED) "Re-queue" else "Retry"
                                        }
                                    }
                                }
                                if (entry.status in listOf(TransferStatus.PENDING, TransferStatus.FAILED)) {
                                    form {
                                        method = FormMethod.post
                                        action = "/api/queue/${entry.id}/cancel"
                                        style = "display: inline;"
                                        button {
                                            style = "padding: 2px 8px; font-size: 12px; background: var(--bg-hover); color: var(--text-dim);"
                                            +"Cancel"
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
