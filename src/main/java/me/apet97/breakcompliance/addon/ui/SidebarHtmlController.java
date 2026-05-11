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
                <!-- Clockify-native UI baseline (colors, typography, controls).
                     Our /styles.css below overrides where the layout needs to
                     diverge for the narrow sidebar form factor. CSP allows
                     this origin in style-src/img-src/font-src. -->
                <link rel="stylesheet" href="https://resources.developer.clockify.me/ui/latest/css/main.min.css">
                <link rel="stylesheet" href="/styles.css">
              </head>
              <body>
                <div class="app-container">
                  <header class="app-header">
                    <h1>Break Compliance</h1>
                    <p id="session-status" class="caption muted" style="margin:0 0 8px;font-size:11px">Connecting…</p>
                    <div class="header-controls">
                      <div class="control-group">
                        <label>Active template</label>
                        <p id="active-template-label" class="readonly-value">—</p>
                      </div>
                      <div class="control-group">
                        <label for="date-preset-select">Date Range</label>
                        <select id="date-preset-select">
                          <option value="today">Today</option>
                          <option value="this_week" selected>This Week</option>
                          <option value="last_week">Last Week</option>
                          <option value="last_2_weeks">Last 2 Weeks</option>
                          <option value="last_month">Last Month</option>
                          <option value="custom_range">Custom Range</option>
                        </select>
                      </div>
                      <div id="custom-range-inputs" class="control-group custom-range-group" style="display:none">
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
                      </div>
                      <p class="caption muted" style="margin:6px 0 0;font-size:11px">Configure thresholds &amp; preset in Clockify: <strong>Workspace Settings → Add-ons → Break Compliance → ⋯ → Settings</strong>.</p>
                    </div>
                  </header>
                  <div id="status-banner" class="error-banner" style="display:none"></div>
                  <div id="diagnostics" class="diagnostics" style="display:none"></div>
                  <div class="view-toggle">
                    <label class="toggle-option"><input type="radio" name="view-toggle" value="pivot" checked><span>Pivot Table</span></label>
                    <label class="toggle-option"><input type="radio" name="view-toggle" value="checklist"><span>Checklist</span></label>
                  </div>
                  <div id="loading" class="loading" style="display:none">
                    <div class="loading-spinner"></div><span>Checking compliance…</span>
                  </div>
                  <div id="results-container" class="results-container"></div>
                  <div class="actions-row" style="margin-top:12px">
                  </div>
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
