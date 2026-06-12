package me.apet97.breakcompliance.addon.ui;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sidebar HTML shell, served to the Clockify iframe at GET /sidebar. The
 * pre-existing DOM nodes (jurisdiction-select, date-preset-select,
 * results-container, etc.) are the contract that {@code /sidebar.js} drives.
 *
 * <p>Browser flow on load: read {@code ?auth_token=...} from the iframe
 * URL, strip it via {@code History.replaceState} (no fragment to avoid the
 * {@code DataCloneError} pitfall), forward as {@code X-Addon-Token} on every
 * subsequent {@code /api/*} call.
 *
 * <p>This controller is reached through {@link
 * me.apet97.breakcompliance.api.AddonTokenAuthFilter}; the filter
 * verifies the token before the shell is served so an unauthenticated
 * request returns 401 with no body.
 */
@RestController
public class SidebarHtmlController {

    private static final String HTML = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Break Compliance</title>
                <!-- Apply theme synchronously BEFORE the body paints so a dark-mode
                     Clockify host doesn't flash light first. Kept in a first-party
                     file because CSP intentionally uses script-src 'self' with no
                     inline-script escape hatch. -->
                <script src="/theme-init.js"></script>
                <!-- Clockify-native UI baseline (colors, typography, controls).
                     Our /styles.css below overrides where the layout needs to
                     diverge for the narrow sidebar form factor. CSP allows
                     this origin in style-src/img-src/font-src. -->
                <link rel="stylesheet" href="https://resources.developer.clockify.me/ui/latest/css/main.min.css">
                <link rel="stylesheet" href="/styles.css">
              </head>
              <body>
                <div class="app-container" role="application" aria-label="Break Compliance">
                  <header class="app-header">
                    <h1>Break Compliance</h1>
                    <p id="session-status" class="session-status" aria-live="polite" hidden></p>
                    <div class="active-template-row">
                      <span class="control-label">Active preset</span>
                      <button
                        id="active-template-chip"
                        class="active-template-chip"
                        type="button"
                        aria-haspopup="dialog"
                        aria-expanded="false"
                        title="Click to see the active thresholds"
                      >
                        <span id="active-template-label">—</span>
                        <span class="chip-caret" aria-hidden="true">▾</span>
                      </button>
                      <span id="customized-pill" class="customized-pill" hidden></span>
                      <button
                        id="switch-preset-btn"
                        class="btn-link"
                        type="button"
                        aria-expanded="false"
                        aria-controls="preset-chooser"
                      >Switch…</button>
                      <div id="active-template-details" class="active-template-details" role="dialog" aria-label="Active template thresholds" hidden></div>
                    </div>
                    <div id="preset-chooser" class="preset-chooser" role="region" aria-label="Choose a preset" hidden></div>
                    <p id="admin-required-note" class="admin-required-note" role="note" hidden>
                      <span class="admin-required-glyph" aria-hidden="true">🔒</span>
                      Workspace admin required to refresh data and change settings. You can still browse existing findings.
                    </p>
                    <div class="settings-link-row">
                      <details id="settings-hint-fallback" class="settings-hint" hidden>
                        <summary class="settings-hint-summary"><span class="settings-hint-glyph" aria-hidden="true">ⓘ</span> Where do I fine-tune the thresholds?</summary>
                        <p class="settings-hint-body">Switching presets here applies the jurisdiction's defaults immediately. To fine-tune individual fields, open <strong>Workspace Settings → Add-ons → Break Compliance → ⋯ → Settings</strong> in Clockify; any edits there land on top of the preset.</p>
                      </details>
                    </div>
                    <div class="header-controls">
                      <div class="control-group">
                        <label for="date-preset-select">Date Range</label>
                        <select id="date-preset-select">
                          <option value="today">Today</option>
                          <option value="this_week" selected>This Week</option>
                          <option value="last_week">Last Week</option>
                          <option value="last_2_weeks">Last 2 Weeks</option>
                          <option value="last_month">Last Month</option>
                          <option value="all_open">All Open (last 90 days)</option>
                          <option value="custom_range">Custom Range</option>
                        </select>
                      </div>
                      <div id="custom-range-inputs" class="control-group custom-range-group" hidden>
                        <div class="date-range-row">
                          <div class="date-input-group">
                            <label for="custom-start-date">From</label>
                            <input type="date" id="custom-start-date">
                          </div>
                          <div class="date-input-group">
                            <label for="custom-end-date">To</label>
                            <input type="date" id="custom-end-date">
                          </div>
                        </div>
                      </div>
                      <div class="button-row">
                        <button id="run-btn" class="btn-primary" type="button">Check Compliance</button>
                        <button id="refresh-btn" class="btn-icon" type="button" title="Re-run the last check" aria-label="Refresh">↻</button>
                      </div>
                    </div>
                  </header>
                  <div id="settings-warning-banner" class="settings-warning-banner" role="status" aria-live="polite" hidden></div>
                  <div id="status-banner" class="error-banner" role="status" aria-live="polite" hidden></div>
                  <div id="diagnostics" class="diagnostics" hidden></div>
                  <div class="staleness-row">
                    <p id="last-checked" class="caption muted last-checked" hidden></p>
                    <span id="pending-refresh-pill" class="pending-refresh-pill" role="status" hidden></span>
                  </div>
                  <div class="results-toolbar">
                    <fieldset class="view-toggle" aria-label="View mode">
                      <legend class="sr-only">View</legend>
                      <label class="toggle-option"><input type="radio" name="view-toggle" value="pivot" checked><span>Pivot Table</span></label>
                      <label class="toggle-option"><input type="radio" name="view-toggle" value="checklist"><span>Checklist</span></label>
                    </fieldset>
                    <select id="user-filter-select" class="user-filter-select" aria-label="Filter findings by user" hidden></select>
                    <button id="export-csv-btn" class="btn-link export-csv-btn" type="button" hidden title="Download the current findings list as a CSV file">
                      <span aria-hidden="true">⬇</span> Export CSV
                    </button>
                  </div>
                  <div id="loading" class="loading" role="status" aria-live="polite" hidden>
                    <div class="loading-spinner" aria-hidden="true"></div>
                    <span>Checking compliance…</span>
                    <button id="cancel-ingest-btn" class="btn-link" type="button">Cancel</button>
                  </div>
                  <div id="results-container" class="results-container" aria-live="polite"></div>
                  <section id="audit-panel" class="audit-panel" aria-labelledby="audit-panel-title" hidden></section>
                </div>
                <script type="module" src="/sidebar.js"></script>
              </body>
            </html>
            """;

    @GetMapping(value = "/sidebar", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> sidebar() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(HTML);
    }
}
