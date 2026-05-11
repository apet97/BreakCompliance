# Design Attribution — Break Compliance for Clockify

_Last updated: 2026-05-12._

## Source

The visual language (palette, density, spacing tokens, button shapes, finding card layout) is adapted from an internal design system handoff. The CSS lives at `src/main/resources/static/styles.css` and is hand-authored — the build does not regenerate it.

Tokens carried over:

- 13 px base font size — tuned for the narrow (360–480 px) Clockify sidebar viewport, with a `@media (max-width: 720px)` block that tightens cell padding and condenses the date columns for mobile widths.
- 6 px border-radius across cards, inputs, and buttons.
- Neutral grays (`#1A1A2E`, `#6B7280`, `#E5E7EB`) with a single accent (`#3B82F6` brand blue); a dark-mode palette under `html[data-clockify-theme='dark']` / `body.dark` lifts background contrast to WCAG AA on every status pill.
- Status colours: green `#10B981` (pass), amber `#F59E0B` (warn), red `#EF4444` (fail).

## Component DOM contract

The JS bundle (`/sidebar.js`) consumes DOM elements by id; the HTML shell in `SidebarHtmlController` provides them:

- Sidebar: `#session-status`, `#active-template-chip`, `#active-template-label`, `#active-template-details`, `#date-preset-select`, `#custom-range-inputs`, `#custom-start-date`, `#custom-end-date`, `#run-btn`, `#refresh-btn`, `#status-banner`, `#diagnostics`, `#last-checked`, `#loading`, `#results-container`, and the view-toggle radio inputs inside `<fieldset class="view-toggle">`.

Settings UI is delivered by Clockify natively via structured-settings declarations in the manifest (`ClockifySettings`/`Tab`/`Setting` — see `ClockifyAddonConfig.java`); the add-on does not host its own settings page. The sidebar surfaces the active rule template as a clickable chip with a thresholds popover (`#active-template-chip` + `#active-template-details`) so admins can see what's currently in effect without leaving the sidebar.

The JS modules use `document.createElement` + `textContent` (never `innerHTML`) for dynamic content so user-supplied strings (template names, finding messages) cannot be interpreted as markup.

## Iconography

`src/main/resources/static/icon.svg` is a hand-drawn 64×64 marketplace mark: Clockify-blue rounded tile, white clock face paused at the 4-hour break threshold (hour hand at 9, minute hand at 12), three steam wisps evoking a coffee break, and a green compliance check overlay in the bottom-right corner. Vector-only, no embedded raster, no external resources, ~2.4 KB. Designed to match Clockify's marketplace icon slot (64×64 with reserved transparent margin in the corner radius).

## Accessibility notes

- Buttons and links have visible focus rings via `:focus-visible` so keyboard navigation is unambiguous.
- The sidebar uses `<label>`/`for=` pairs for every form control; the view toggle is a `<fieldset>` with a `<legend class="sr-only">` rather than a styled-button hack.
- Status banners use both colour and an icon glyph so colour-blind users still distinguish severities; the banner is wrapped in `role="status" aria-live="polite"` so screen readers announce range results without stealing focus.
- The active-template chip exposes `aria-haspopup="dialog"` + `aria-expanded` and dismisses on Escape / outside click; the popover is a `role="dialog"` with `aria-label="Active template thresholds"`.
- Empty-state messages are always visible (not behind hover) and distinguish "no check has run yet" from "all clear in this range".
- Global `[hidden] { display: none !important; }` rule prevents author CSS (`.loading {display:flex}`, `.diagnostics {display:grid}`) from leaking past the HTML5 `hidden` attribute.

## License of CSS / icon

CSS and icon are hand-authored and licensed identically to the rest of this repository.
