# Design Attribution — Break Compliance for Clockify

_Last updated: 2026-05-11._

## Source

The visual language (palette, density, spacing tokens, button shapes, finding card layout) is adapted from an internal design system handoff. The CSS lives at `src/main/resources/static/styles.css` and is hand-authored — the build does not regenerate it.

Tokens carried over:

- 13 px base font size — tuned for the narrow (360–480 px) Clockify sidebar viewport.
- 6 px border-radius across cards, inputs, and buttons.
- Neutral grays (`#1A1A2E`, `#6B7280`, `#E5E7EB`) with a single accent (`#3B82F6` brand blue).
- Status colours: green `#10B981` (pass), amber `#F59E0B` (warn), red `#EF4444` (fail).

## Component DOM contract

The JS bundles (`/sidebar.js`, `/settings-page.js`) consume DOM elements by id; the HTML shells in `SidebarHtmlController` and `SettingsHtmlController` provide them:

- Sidebar: `#jurisdiction-select`, `#date-preset-select`, `#custom-range-inputs`, `#run-btn`, `#settings-link`, `#status-banner`, `#diagnostics`, `#loading`, `#results-container`, `#export-json`, `#export-csv`, view-toggle radios.
- Settings: `#banner`, `#settings-section`, `#templates-section`, `#assignments-section`, `#signals-section`.

The JS modules use `document.createElement` + `textContent` (never `innerHTML`) for dynamic content so user-supplied strings (template names, finding messages) cannot be interpreted as markup.

## Iconography

`src/main/resources/static/icon.svg` is the placeholder marketplace icon. Replace with the design-system-approved mark before submission. Dimensions must match Clockify's marketplace icon requirements (typically 64×64 with reserved transparent margin).

## Accessibility notes

- Buttons and links have visible focus rings via `:focus-visible` so keyboard navigation is unambiguous.
- The sidebar uses `<label>`/`for=` pairs for every form control; the view toggle is a `radiogroup` rather than a styled-button hack.
- Status banners use both colour and an icon glyph so colour-blind users still distinguish severities.
- Empty-state messages ("no findings in this range") are not hidden behind hover; the empty state is always visible.

## License of CSS / icon

CSS is hand-authored and licensed identically to the rest of this repository. The icon SVG is a placeholder created in-house; replace before submission with whichever mark the design team finalises.
