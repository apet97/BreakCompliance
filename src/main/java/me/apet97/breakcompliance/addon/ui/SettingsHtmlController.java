package me.apet97.breakcompliance.addon.ui;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custom settings page HTML shell, served at GET /settings. Like the
 * sidebar, this is a thin wrapper that hands off to {@code /settings-page.js}
 * — the bundle drives all section rendering via /api/settings,
 * /api/templates, /api/assignments, /api/refresh-signals.
 */
@RestController
public class SettingsHtmlController {

    private static final String HTML = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Break Compliance — Settings</title>
                <link rel="stylesheet" href="/styles.css">
              </head>
              <body>
                <div class="app-container wide">
                  <header class="app-header">
                    <h1>Break Compliance — Settings</h1>
                    <p id="session-status" class="caption muted" style="margin:0 0 8px;font-size:11px">Loading…</p>
                  </header>
                  <div id="banner" class="error-banner" style="display:none"></div>
                  <section class="panel">
                    <div class="panel-header"><h2>Workspace settings</h2></div>
                    <div id="settings-section" class="panel-body section-stack"></div>
                  </section>
                  <section class="panel">
                    <div class="panel-header"><h2>Rule templates</h2></div>
                    <div id="templates-section" class="panel-body section-stack"></div>
                  </section>
                  <section class="panel">
                    <div class="panel-header"><h2>Assignments</h2></div>
                    <div id="assignments-section" class="panel-body section-stack"></div>
                  </section>
                  <section class="panel">
                    <div class="panel-header"><h2>Refresh signals &amp; runner</h2></div>
                    <div id="signals-section" class="panel-body section-stack"></div>
                  </section>
                </div>
                <script type="module" src="/settings-page.js"></script>
              </body>
            </html>
            """;

    @GetMapping(value = "/settings", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> settings() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(HTML);
    }
}
