package vision.salient.choam.web

import kotlinx.html.*
import vision.salient.choam.catalog.CatalogIndex
import vision.salient.choam.catalog.SearchFilters
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.network.ProgressMonitor
import java.io.File

private val MEDIA_EXTS = listOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "flv",
    "mp3", "flac", "aac", "ogg", "wav", "m4a", "wma")

private val VIDEO_EXTS = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "flv")
private val AUDIO_EXTS = setOf("mp3", "flac", "aac", "ogg", "wav", "m4a", "wma")

/**
 * Web media browser — search + browse media files with content-type icons,
 * full CIDs, IPFS links, and play/stream buttons.
 */
fun HTML.mediaPage(config: ChoamConfig, query: String, ext: String, limit: Int) = layout("Media", "media") {
    h1 { +"Media Browser" }

    // Search form
    form(classes = "search-form") {
        attributes["hx-get"] = "/htmx/media-results"
        attributes["hx-target"] = "#media-results"
        attributes["hx-trigger"] = "submit"
        input {
            type = InputType.text; name = "q"; placeholder = "Search media files..."
            value = query
        }
        select {
            name = "ext"
            option { value = ""; if (ext.isEmpty()) selected = true; +"All media types" }
            option { value = "mkv,mp4,avi,mov,webm"; if (ext.contains("mkv")) selected = true; +"Video" }
            option { value = "mp3,flac,aac,ogg,wav,m4a"; if (ext.contains("mp3")) selected = true; +"Audio" }
        }
        button { type = ButtonType.submit; +"Search" }
    }

    // Results area
    div {
        id = "media-results"
        if (query.isNotEmpty() || ext.isNotEmpty()) {
            mediaResultsContent(query, ext, limit)
        } else {
            // Show recent media by default
            p("detail") { +"Search for media files or browse by type." }
            mediaResultsContent("", MEDIA_EXTS.joinToString(","), 50)
        }
    }
}

fun HTML.mediaResultsFragment(query: String, ext: String, limit: Int) {
    body { mediaResultsContent(query, ext, limit) }
}

private fun FlowContent.mediaResultsContent(query: String, ext: String, limit: Int) {
    val indexDbPath = "${System.getProperty("user.home")}/.choam/catalog-index.db"
    if (!File(indexDbPath).exists()) {
        p { +"No search index. Run 'choam catalog-sync' then 'choam rebuild-index'." }
        return
    }

    val extensions = if (ext.isNotEmpty()) {
        ext.split(",").map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotEmpty() }
    } else {
        MEDIA_EXTS
    }

    val catalogIndex = CatalogIndex(indexDbPath)
    val conn = catalogIndex.open()
    val filters = SearchFilters(extensions = extensions)
    val results = catalogIndex.advancedSearch(conn, query, filters, limit)
    conn.close()

    if (results.isEmpty()) {
        p { +"No media files found${if (query.isNotEmpty()) " for \"$query\"" else ""}." }
        return
    }

    p { +"${results.size} media files" }

    for (r in results) {
        val fileExt = r.filename.substringAfterLast(".", "").lowercase()
        val icon = when (fileExt) {
            in VIDEO_EXTS -> "🎬"
            in AUDIO_EXTS -> "🎵"
            else -> "📄"
        }
        val isStreamable = fileExt in VIDEO_EXTS || fileExt in AUDIO_EXTS

        div("result-item") {
            style = "padding: 8px 0; border-bottom: 1px solid var(--border)"

            // Filename + icon + size
            div {
                span { +icon }
                span { style = "margin-left: 8px; font-weight: bold"; +r.filename }
                span("size") { +" ${ProgressMonitor.formatBytes(r.size)}" }
                span { style = "color: var(--text-dim); font-size: 12px; margin-left: 8px"; +"${r.machine}/${r.driveLabel}" }
            }

            // CID + IPFS link
            if (r.cid.isNotEmpty()) {
                div {
                    style = "margin: 4px 0; font-size: 12px"
                    a(href = "/inspect/${r.cid}") {
                        style = "color: var(--green); font-family: var(--mono)"
                        +"CID: ${r.cid}"
                    }
                }
                div {
                    style = "font-size: 11px"
                    a(href = "https://ipfs.io/ipfs/${r.cid}") {
                        target = "_blank"
                        style = "color: var(--blue)"
                        +"IPFS Gateway"
                    }
                    if (isStreamable) {
                        a(href = "/stream/${r.cid}") {
                            style = "color: var(--green); margin-left: 12px"
                            +"Stream"
                        }
                    }
                }
            }

            // Path
            div("path") { +r.path }
        }
    }
}
