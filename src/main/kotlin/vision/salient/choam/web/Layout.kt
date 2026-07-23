package vision.salient.choam.web

import kotlinx.html.*

/**
 * Shared layout for all CHOAM dashboard pages.
 * Dark theme with green accents (Matrix-inspired, matching other portfolio projects).
 * HTMX loaded from CDN for dynamic partial updates.
 */
fun HTML.layout(pageTitle: String, activeNav: String = "", content: BODY.() -> Unit) {
    head {
        title { +"CHOAM — $pageTitle" }
        meta { charset = "utf-8" }
        meta { name = "viewport"; this.content = "width=device-width, initial-scale=1" }
        meta { name = "theme-color"; this.content = "#0a0a0a" }
        meta { name = "apple-mobile-web-app-capable"; this.content = "yes" }
        meta { name = "apple-mobile-web-app-status-bar-style"; this.content = "black-translucent" }
        link { rel = "manifest"; href = "/manifest.json" }
        script { src = "https://unpkg.com/htmx.org@1.9.10" }
        style {
            unsafe {
                raw(CSS)
            }
        }
    }
    body {
        nav {
            div("nav-brand") { +"CHOAM" }
            div("nav-links") {
                navLink("/", "Dashboard", activeNav == "dashboard")
                navLink("/search", "Search", activeNav == "search")
                navLink("/media", "Media", activeNav == "media")
                navLink("/drives", "Drives", activeNav == "drives")
                navLink("/federation", "Federation", activeNav == "federation")
                navLink("/report", "Report", activeNav == "report")
                navLink("/network", "Network", activeNav == "network")
                navLink("/queue", "Queue", activeNav == "queue")
                navLink("/history", "History", activeNav == "history")
            }
        }
        main {
            this@body.content()
        }
        footer {
            +"CHOAM — Cross-Host Orchestrated Asset Management"
        }
    }
}

private fun DIV.navLink(href: String, text: String, active: Boolean) {
    a(href = href) {
        if (active) classes = setOf("active")
        +text
    }
}

private const val CSS = """
:root {
    --bg: #0a0a0a;
    --bg-card: #141414;
    --bg-hover: #1a1a1a;
    --border: #2a2a2a;
    --text: #e0e0e0;
    --text-dim: #888;
    --green: #00cc66;
    --green-dim: #00994d;
    --yellow: #ccaa00;
    --red: #cc3333;
    --blue: #3399cc;
    --mono: 'SF Mono', 'Menlo', 'Monaco', 'Consolas', monospace;
}
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
    font-family: var(--mono);
    background: var(--bg);
    color: var(--text);
    font-size: 14px;
    line-height: 1.6;
}
nav {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 24px;
    border-bottom: 1px solid var(--border);
    background: var(--bg-card);
}
.nav-brand {
    font-size: 18px;
    font-weight: bold;
    color: var(--green);
    letter-spacing: 2px;
}
.nav-links { display: flex; gap: 20px; }
.nav-links a {
    color: var(--text-dim);
    text-decoration: none;
    padding: 4px 8px;
    border-radius: 4px;
    transition: all 0.2s;
}
.nav-links a:hover { color: var(--text); background: var(--bg-hover); }
.nav-links a.active { color: var(--green); border-bottom: 2px solid var(--green); }
main { max-width: 1200px; margin: 24px auto; padding: 0 24px; }
footer {
    text-align: center;
    padding: 24px;
    color: var(--text-dim);
    font-size: 12px;
    border-top: 1px solid var(--border);
    margin-top: 48px;
}
h1 { color: var(--green); font-size: 20px; margin-bottom: 16px; }
h2 { color: var(--text); font-size: 16px; margin: 24px 0 12px; border-bottom: 1px solid var(--border); padding-bottom: 4px; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; margin: 16px 0; }
.card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 16px;
}
.card h3 { font-size: 14px; color: var(--text-dim); margin-bottom: 8px; text-transform: uppercase; letter-spacing: 1px; }
.card .value { font-size: 24px; color: var(--green); font-weight: bold; }
.card .detail { font-size: 12px; color: var(--text-dim); margin-top: 4px; }
.status-ok { color: var(--green); }
.status-warn { color: var(--yellow); }
.status-err { color: var(--red); }
.status-info { color: var(--blue); }
table { width: 100%; border-collapse: collapse; margin: 8px 0; }
th { text-align: left; padding: 8px; color: var(--text-dim); border-bottom: 1px solid var(--border); font-size: 12px; text-transform: uppercase; }
td { padding: 8px; border-bottom: 1px solid var(--border); }
tr:hover { background: var(--bg-hover); }
.badge {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 12px;
    font-size: 11px;
    font-weight: bold;
}
.badge-hot { background: #331111; color: var(--red); border: 1px solid var(--red); }
.badge-warm { background: #332200; color: var(--yellow); border: 1px solid var(--yellow); }
.badge-cold { background: #112233; color: var(--blue); border: 1px solid var(--blue); }
.badge-ok { background: #113311; color: var(--green); border: 1px solid var(--green); }
.badge-stale { background: #332200; color: var(--yellow); border: 1px solid var(--yellow); }
input, select {
    background: var(--bg);
    color: var(--text);
    border: 1px solid var(--border);
    padding: 8px 12px;
    border-radius: 4px;
    font-family: var(--mono);
    font-size: 14px;
}
input:focus, select:focus { outline: none; border-color: var(--green); }
button {
    background: var(--green-dim);
    color: white;
    border: none;
    padding: 8px 16px;
    border-radius: 4px;
    font-family: var(--mono);
    cursor: pointer;
    font-size: 14px;
}
button:hover { background: var(--green); }
.search-form { display: flex; gap: 8px; margin: 16px 0; flex-wrap: wrap; }
.search-form input { flex: 1; min-width: 200px; }
.result-group { margin: 16px 0; }
.result-group h3 { color: var(--green); font-size: 14px; }
.result-item { padding: 4px 0; font-size: 13px; }
.result-item .path { color: var(--text-dim); font-size: 12px; }
.result-item .size { color: var(--blue); }
.htmx-indicator { display: none; }
.htmx-request .htmx-indicator { display: inline; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.4; } }
[hx-get]:not([hx-swap-oob]) > .detail { animation: pulse 1.5s infinite; }
.spinner { color: var(--green); }
.stream-btn {
    display: inline-block;
    background: var(--green-dim);
    color: white;
    padding: 4px 12px;
    border-radius: 4px;
    text-decoration: none;
    font-size: 12px;
}
.stream-btn:hover { background: var(--green); }
code {
    background: var(--bg-card);
    border: 1px solid var(--border);
    padding: 1px 6px;
    border-radius: 3px;
    font-size: 13px;
}
ul { margin: 8px 0; padding-left: 24px; }
li { margin: 4px 0; }
"""
