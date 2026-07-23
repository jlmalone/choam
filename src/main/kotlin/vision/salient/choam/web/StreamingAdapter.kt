package vision.salient.choam.web

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.copyTo
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfig

private val logger = KotlinLogging.logger {}

/**
 * Content streaming adapter — serves media files with HTTP range request support.
 *
 * Tiered resolution: local file → IPFS gateway proxy → diagnostic 404.
 * Enables streaming for ALL content across all machines, not just local files.
 *
 * Endpoints:
 *   GET /stream/{cid}        — Stream content by CID (range requests supported, proxied if remote)
 *   GET /stream/info/{cid}   — Content metadata with resolution tier info
 *   GET /resolve/{path...}   — Universal content proxy (CID or machine/path)
 *   GET /api/resolve/{cid}   — JSON resolution metadata
 */
fun Route.streamingRoutes(config: ChoamConfig) {

    val proxy = ContentProxy(config)

    get("/stream/info/{cid}") {
        val cid = call.parameters["cid"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "CID required")
            return@get
        }

        val result = proxy.resolve(cid)
        when (result) {
            is ProxyResult.LocalFile -> {
                call.respond(mapOf(
                    "cid" to cid,
                    "tier" to "local",
                    "path" to result.file.absolutePath,
                    "size" to result.file.length().toString(),
                    "contentType" to result.contentType.toString(),
                    "streamUrl" to "/stream/$cid"
                ))
            }
            is ProxyResult.RemoteStream -> {
                call.respond(mapOf(
                    "cid" to cid,
                    "tier" to "ipfs_gateway",
                    "gatewayUrl" to result.url,
                    "size" to (result.size?.toString() ?: "-1"),
                    "contentType" to result.contentType.toString(),
                    "streamUrl" to "/stream/$cid"
                ))
            }
            is ProxyResult.NotAvailable -> {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "error" to result.message,
                    "cid" to cid,
                    "hint" to "Content may exist on a remote machine. Run 'choam pull' or check IPFS gateway status."
                ))
            }
        }
    }

    get("/stream/{cid}") {
        val cid = call.parameters["cid"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "CID required")
            return@get
        }

        val result = proxy.resolve(cid)
        when (result) {
            is ProxyResult.LocalFile -> {
                if (!result.file.exists()) {
                    call.respond(HttpStatusCode.Gone, "File no longer exists at ${result.file.absolutePath}")
                    return@get
                }
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                call.respondFile(result.file)
            }
            is ProxyResult.RemoteStream -> {
                // Proxy from IPFS gateway — forward Range headers for seeking/resume
                val rangeHeader = call.request.headers[HttpHeaders.Range]
                try {
                    val response = proxy.proxyStream(result.url, rangeHeader)
                    val status = if (response.status == HttpStatusCode.PartialContent)
                        HttpStatusCode.PartialContent else HttpStatusCode.OK

                    call.response.status(status)
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentType, result.contentType.toString())
                    response.headers[HttpHeaders.ContentLength]?.let {
                        call.response.header(HttpHeaders.ContentLength, it)
                    }
                    response.headers[HttpHeaders.ContentRange]?.let {
                        call.response.header(HttpHeaders.ContentRange, it)
                    }

                    // Stream bytes from gateway to client without buffering
                    val channel = response.bodyAsChannel()
                    call.respondBytesWriter {
                        channel.copyTo(this)
                    }
                } catch (e: Exception) {
                    logger.warn { "Failed to proxy $cid from ${result.url}: ${e.message}" }
                    call.respond(HttpStatusCode.BadGateway,
                        "Failed to stream from IPFS gateway: ${e.message}")
                }
            }
            is ProxyResult.NotAvailable -> {
                call.respond(HttpStatusCode.NotFound,
                    "CID not found: $cid — ${result.message}")
            }
        }
    }

    // Universal content proxy — accepts CID or machine/path
    get("/resolve/{path...}") {
        val pathSegments = call.parameters.getAll("path") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Path required")
            return@get
        }
        val fullPath = pathSegments.joinToString("/")

        // Determine if it's a CID or a machine/path
        val isCid = fullPath.startsWith("bafy") || fullPath.startsWith("Qm") || fullPath.startsWith("bafk") ||
            (fullPath.length >= 32 && !fullPath.contains("/") && fullPath.all { it.isLetterOrDigit() })

        val result = if (isCid) {
            proxy.resolve(fullPath)
        } else {
            // Parse as machine/path
            val machine = pathSegments.firstOrNull() ?: ""
            val filePath = pathSegments.drop(1).joinToString("/")
            proxy.resolveByPath(machine, filePath)
        }

        when (result) {
            is ProxyResult.LocalFile -> {
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                call.respondFile(result.file)
            }
            is ProxyResult.RemoteStream -> {
                val rangeHeader = call.request.headers[HttpHeaders.Range]
                try {
                    val response = proxy.proxyStream(result.url, rangeHeader)
                    val status = if (response.status == HttpStatusCode.PartialContent)
                        HttpStatusCode.PartialContent else HttpStatusCode.OK
                    call.response.status(status)
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentType, result.contentType.toString())
                    response.headers[HttpHeaders.ContentLength]?.let {
                        call.response.header(HttpHeaders.ContentLength, it)
                    }
                    response.headers[HttpHeaders.ContentRange]?.let {
                        call.response.header(HttpHeaders.ContentRange, it)
                    }
                    val channel = response.bodyAsChannel()
                    call.respondBytesWriter {
                        channel.copyTo(this)
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway, "Failed to proxy: ${e.message}")
                }
            }
            is ProxyResult.NotAvailable -> {
                call.respond(HttpStatusCode.NotFound, result.message)
            }
        }
    }

    // JSON resolution metadata
    get("/api/resolve/{cid}") {
        val cid = call.parameters["cid"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "CID required")
            return@get
        }

        val result = proxy.resolve(cid)
        val tier = when (result) {
            is ProxyResult.LocalFile -> "local"
            is ProxyResult.RemoteStream -> "ipfs_gateway"
            is ProxyResult.NotAvailable -> "not_available"
        }

        call.respond(mapOf(
            "cid" to cid,
            "resolved" to (result !is ProxyResult.NotAvailable).toString(),
            "tier" to tier,
            "streamUrl" to "/stream/$cid",
            "gatewayUrl" to when (result) {
                is ProxyResult.RemoteStream -> result.url
                else -> ""
            },
            "filename" to when (result) {
                is ProxyResult.LocalFile -> result.file.name
                else -> ""
            },
            "contentType" to when (result) {
                is ProxyResult.LocalFile -> result.contentType.toString()
                is ProxyResult.RemoteStream -> result.contentType.toString()
                is ProxyResult.NotAvailable -> ""
            }
        ))
    }
}

internal fun guessContentType(filename: String): ContentType {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // Video
        "mkv" -> ContentType("video", "x-matroska")
        "mp4", "m4v" -> ContentType.Video.MP4
        "avi" -> ContentType("video", "x-msvideo")
        "mov" -> ContentType("video", "quicktime")
        "webm" -> ContentType("video", "webm")
        "ts" -> ContentType("video", "mp2t")
        "flv" -> ContentType("video", "x-flv")
        // Audio
        "mp3" -> ContentType.Audio.MPEG
        "flac" -> ContentType("audio", "flac")
        "aac" -> ContentType("audio", "aac")
        "ogg" -> ContentType.Audio.OGG
        "wav" -> ContentType("audio", "wav")
        "m4a" -> ContentType("audio", "mp4")
        // Image
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "png" -> ContentType.Image.PNG
        "gif" -> ContentType.Image.GIF
        "webp" -> ContentType("image", "webp")
        "svg" -> ContentType.Image.SVG
        // Document
        "pdf" -> ContentType.Application.Pdf
        // Default
        else -> ContentType.Application.OctetStream
    }
}
