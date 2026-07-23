package vision.salient.choam.daemon

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.web.configureRouting
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionApiTest {

    @Test
    fun `daemon status endpoint returns JSON`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.get("/api/daemon/status")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "running")
        assertContains(body, "paused")
    }

    @Test
    fun `daemon activity endpoint returns JSON array`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.get("/api/daemon/activity")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `catalog-sync POST returns started`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.post("/api/catalog-sync")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "started")
    }

    @Test
    fun `fulfill POST returns started`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.post("/api/fulfill")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "started")
    }

    @Test
    fun `pause POST sets paused true`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.post("/api/daemon/pause")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "true")
        DaemonState.paused = false // Reset
    }

    @Test
    fun `resume POST sets paused false`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        DaemonState.paused = true
        val response = client.post("/api/daemon/resume")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "false")
    }

    @Test
    fun `peer-check POST returns started`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.post("/api/peer-check")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "started")
    }

    @Test
    fun `dag-sync POST returns started`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.post("/api/dag-sync")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "started")
    }

    @Test
    fun `HTMX daemon activity fragment returns HTML`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.get("/htmx/daemon-activity")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // Should contain either "No daemon activity" or a table
        assertTrue(body.contains("activity") || body.contains("table") || body.contains("No daemon"))
    }

    @Test
    fun `health endpoint still works`() = testApplication {
        application { configureRouting(ChoamConfig()) }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "ok")
    }
}
