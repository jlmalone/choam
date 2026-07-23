package vision.salient.choam.web

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import vision.salient.choam.config.*
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Phase 5 web dashboard — Ktor routing, page rendering, HTMX partials, API.
 *
 * Uses Ktor testApplication to verify:
 * - All routes return 200
 * - Pages contain expected structural elements (nav, titles, HTMX attributes)
 * - Search accepts query parameters
 * - HTMX partials return HTML fragments
 * - API endpoint returns JSON
 * - Empty config doesn't crash pages
 * - Config with drives/repos renders tables
 */
class WebDashboardTest {

    private val emptyConfig = ChoamConfig()

    private val fullConfig = ChoamConfig(
        version = "1.0.0",
        machines = mapOf(
            "local" to MachineProfile(
                name = "local", hostname = "test-host", type = MachineType.DESKTOP,
                repositories = mapOf("film" to "/media/film", "tv" to "/media/tv"),
                tailscaleIp = "100.64.0.1"
            ),
            "server-a" to MachineProfile(
                name = "server-a", hostname = "server-a-host", type = MachineType.DESKTOP,
                repositories = mapOf("film" to "/Volumes/ext-drive/film"),
                sshUser = "user", tailscaleIp = "100.64.0.2",
                aliases = listOf("server-a-old")
            )
        ),
        drives = mapOf(
            "ext-drive" to Drive(
                uuid = "test-uuid", label = "ext-drive",
                repositories = mapOf("film" to "film"), storageClass = StorageClass.WARM
            ),
            "nas" to Drive(
                uuid = "nas-uuid", label = "NAS",
                repositories = mapOf("backup" to "backup"), storageClass = StorageClass.HOT
            )
        ),
        repositories = mapOf(
            "film" to RepositoryConfig(
                name = "film", type = RepositoryType.MEDIA,
                replication = ReplicationPolicy(minCopies = 2, preferredCopies = 3)
            ),
            "tv" to RepositoryConfig(name = "tv", type = RepositoryType.MEDIA)
        )
    )

    // ===========================================
    // Dashboard page
    // ===========================================

    @Test
    fun `dashboard returns 200`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `dashboard contains CHOAM title and nav`() = testApplication {
        application { configureRouting(emptyConfig) }
        val html = client.get("/").bodyAsText()
        assertContains(html, "CHOAM")
        assertContains(html, "Dashboard")
        assertContains(html, "/search")
        assertContains(html, "/drives")
        assertContains(html, "/history")
    }

    @Test
    fun `dashboard contains HTMX attributes for auto-refresh`() = testApplication {
        application { configureRouting(emptyConfig) }
        val html = client.get("/").bodyAsText()
        assertContains(html, "hx-get")
        assertContains(html, "hx-trigger")
        assertContains(html, "/htmx/machines")
        assertContains(html, "/htmx/catalog-stats")
    }

    @Test
    fun `dashboard with repos shows replication section`() = testApplication {
        application { configureRouting(fullConfig) }
        val html = client.get("/").bodyAsText()
        assertContains(html, "Replication")
        assertContains(html, "/htmx/replication")
        // Replication fragment loads data from unified_registry.db (may not exist on CI)
        val fragment = client.get("/htmx/replication")
        assertEquals(HttpStatusCode.OK, fragment.status)
    }

    @Test
    fun `dashboard loads HTMX from CDN`() = testApplication {
        application { configureRouting(emptyConfig) }
        val html = client.get("/").bodyAsText()
        assertContains(html, "htmx.org")
    }

    // ===========================================
    // Search page
    // ===========================================

    @Test
    fun `search page returns 200`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/search")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `search page contains form with query input`() = testApplication {
        application { configureRouting(emptyConfig) }
        val html = client.get("/search").bodyAsText()
        assertContains(html, "Search")
        assertContains(html, "name=\"q\"")
        assertContains(html, "name=\"ext\"")
    }

    @Test
    fun `search page with query parameter pre-fills input`() = testApplication {
        application { configureRouting(emptyConfig) }
        val html = client.get("/search?q=Aliens").bodyAsText()
        assertContains(html, "Aliens")
    }

    @Test
    fun `search page with machine list shows dropdown options`() = testApplication {
        application { configureRouting(fullConfig) }
        val html = client.get("/search").bodyAsText()
        assertContains(html, "local")
        assertContains(html, "server-a")
    }

    @Test
    fun `search page renders HTMX target for results`() = testApplication {
        application { configureRouting(emptyConfig) }
        val html = client.get("/search").bodyAsText()
        assertContains(html, "id=\"results\"")
        assertContains(html, "hx-get")
        assertContains(html, "/htmx/search-results")
    }

    // ===========================================
    // Drives page
    // ===========================================

    @Test
    fun `drives page returns 200`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/drives")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `drives page contains HTMX drive status loader`() = testApplication {
        application { configureRouting(emptyConfig) }
        val html = client.get("/drives").bodyAsText()
        assertContains(html, "Drives")
        assertContains(html, "/htmx/drive-status")
    }

    // ===========================================
    // History page
    // ===========================================

    @Test
    fun `history page returns 200`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/history")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `history page shows no-history message when empty`() = testApplication {
        application { configureRouting(emptyConfig) }
        val html = client.get("/history").bodyAsText()
        assertContains(html, "History")
        // Either shows table headers or "no history" message
        assertTrue(html.contains("No sync history") || html.contains("Source"))
    }

    // ===========================================
    // HTMX partial endpoints
    // ===========================================

    @Test
    fun `htmx machines returns 200`() = testApplication {
        application { configureRouting(fullConfig) }
        val response = client.get("/htmx/machines")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `htmx machines renders machine names`() = testApplication {
        application { configureRouting(fullConfig) }
        val html = client.get("/htmx/machines").bodyAsText()
        assertContains(html, "local")
        assertContains(html, "server-a")
    }

    @Test
    fun `htmx machines shows machine types`() = testApplication {
        application { configureRouting(fullConfig) }
        val html = client.get("/htmx/machines").bodyAsText()
        assertContains(html, "desktop")
    }

    @Test
    fun `htmx catalog-stats returns 200`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/htmx/catalog-stats")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `htmx search-results returns 200`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/htmx/search-results?q=test")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `htmx drive-status returns 200 with empty config`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/htmx/drive-status")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `htmx drive-status with drives shows storage class badges`() = testApplication {
        application { configureRouting(fullConfig) }
        val html = client.get("/htmx/drive-status").bodyAsText()
        assertContains(html, "WARM")
        assertContains(html, "HOT")
        assertContains(html, "ext-drive")
        assertContains(html, "NAS")
    }

    @Test
    fun `htmx drive-status shows repository mappings`() = testApplication {
        application { configureRouting(fullConfig) }
        val html = client.get("/htmx/drive-status").bodyAsText()
        assertContains(html, "film")
        assertContains(html, "backup")
    }

    // ===========================================
    // API endpoint
    // ===========================================

    @Test
    fun `api health returns 200 with JSON`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "ok")
        assertContains(body, "version")
    }

    @Test
    fun `api health includes config version`() = testApplication {
        application { configureRouting(fullConfig) }
        val body = client.get("/api/health").bodyAsText()
        assertContains(body, "1.0.0")
    }

    // ===========================================
    // CSS and layout structure
    // ===========================================

    @Test
    fun `pages include Matrix dark theme CSS`() = testApplication {
        application { configureRouting(emptyConfig) }
        val html = client.get("/").bodyAsText()
        assertContains(html, "#00cc66")  // green accent
        assertContains(html, "#0a0a0a")  // dark background
        assertContains(html, "monospace")
    }

    @Test
    fun `all pages share consistent nav structure`() = testApplication {
        application { configureRouting(emptyConfig) }

        for (path in listOf("/", "/search", "/drives", "/history")) {
            val html = client.get(path).bodyAsText()
            assertContains(html, "nav-brand", message = "Missing nav-brand on $path")
            assertContains(html, "nav-links", message = "Missing nav-links on $path")
            assertContains(html, "CHOAM", message = "Missing CHOAM brand on $path")
        }
    }

    @Test
    fun `active nav is highlighted on each page`() = testApplication {
        application { configureRouting(emptyConfig) }

        val dashHtml = client.get("/").bodyAsText()
        // Dashboard link should have 'active' class
        assertTrue(dashHtml.contains("class=\"active\""), "Dashboard should have active nav")

        val searchHtml = client.get("/search").bodyAsText()
        assertTrue(searchHtml.contains("class=\"active\""), "Search should have active nav")
    }

    // ===========================================
    // Edge cases
    // ===========================================

    @Test
    fun `unknown route returns 404`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/nonexistent")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `search with special characters does not crash`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/search?q=%22test%22%20AND%20*")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `search with empty query returns 200`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/search?q=")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `htmx search-results with ext filter returns 200`() = testApplication {
        application { configureRouting(emptyConfig) }
        val response = client.get("/htmx/search-results?q=test&ext=mkv,mp4&machine=server-a&limit=10")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
