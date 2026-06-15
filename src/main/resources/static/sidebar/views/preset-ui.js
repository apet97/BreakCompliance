// Break Compliance — preset surface: the active-template chip + thresholds
// popover, the Matches/Customized pill, and the inline preset chooser.
//
// The orchestrator injects its cross-cutting bits once via configurePresetUi:
//   api          — the shared X-Addon-Token RestClient
//   showBanner   — status banner writer
//   afterApply   — re-render the validation-warning banner + settings hint
import { HttpError } from "../api-client.js";
import { formatMinutes } from "../date-range.js";
import { clearChildren, create, el } from "../dom.js";
import { fieldsThatDivergeFromPreset, PRESET_LABELS, TIMEZONE_LABELS } from "../presets.js";
import { isAdmin } from "../roles.js";
import { state } from "../state.js";

let deps = { api: async () => ({}), showBanner: () => {}, afterApply: () => {} };

export function configurePresetUi(overrides) {
    deps = { ...deps, ...overrides };
}

export async function loadPresetCatalog() {
    if (state.presetCatalog) return state.presetCatalog;
    const response = await deps.api("/api/presets");
    state.presetCatalog = response.presets ?? [];
    return state.presetCatalog;
}

function activePresetFromCatalog() {
    const key = state.session?.appliedPresetKey;
    if (!key || !state.presetCatalog) return null;
    return state.presetCatalog.find(p => p.key === key) ?? null;
}

function clearCustomizedPillInteraction(pill) {
    pill.removeAttribute("role");
    pill.removeAttribute("aria-label");
    pill.tabIndex = -1;
    pill.onclick = null;
    pill.onkeydown = null;
}

export function renderCustomizedPill() {
    const pill = el("customized-pill");
    const activePreset = activePresetFromCatalog();
    const activeTemplate = state.session?.activeTemplate;
    if (!activePreset || !activeTemplate) {
        pill.hidden = true;
        clearCustomizedPillInteraction(pill);
        return;
    }
    const divergent = fieldsThatDivergeFromPreset(activeTemplate, activePreset);
    if (divergent.length === 0) {
        pill.hidden = false;
        pill.className = "customized-pill matches";
        pill.textContent = "Matches preset";
        clearCustomizedPillInteraction(pill);
    } else if (!isAdmin()) {
        // Non-admins see the informational "Customized" label without the
        // clickable Reset affordance — the apply endpoint would 403 anyway.
        pill.hidden = false;
        pill.className = "customized-pill diverged";
        pill.textContent = "Customized";
        clearCustomizedPillInteraction(pill);
    } else {
        pill.hidden = false;
        pill.className = "customized-pill diverged";
        pill.textContent = `Customized · Reset to ${activePreset.label}?`;
        pill.setAttribute("role", "button");
        pill.setAttribute("aria-label", `Reset customized thresholds to ${activePreset.label}`);
        pill.tabIndex = 0;
        const resetToPreset = () => applyPreset(activePreset.key, { skipDivergenceConfirm: true });
        pill.onclick = resetToPreset;
        pill.onkeydown = event => {
            if (event.key !== "Enter" && event.key !== " ") return;
            event.preventDefault();
            resetToPreset();
        };
    }
}

export async function togglePresetChooser(force) {
    state.chooserOpen = typeof force === "boolean" ? force : !state.chooserOpen;
    const panel = el("preset-chooser");
    const trigger = el("switch-preset-btn");
    trigger.setAttribute("aria-expanded", state.chooserOpen ? "true" : "false");
    if (!state.chooserOpen) {
        panel.hidden = true;
        return;
    }
    panel.hidden = false;
    try {
        await loadPresetCatalog();
    } catch (err) {
        clearChildren(panel);
        panel.appendChild(create("p", { className: "muted", text: "Couldn't load presets — try again." }));
        return;
    }
    renderPresetChooser();
}

function renderPresetChooser() {
    const panel = el("preset-chooser");
    if (!state.chooserOpen) { panel.hidden = true; return; }
    clearChildren(panel);
    const activeKey = state.session?.appliedPresetKey;
    const activeTemplate = state.session?.activeTemplate;
    for (const preset of state.presetCatalog ?? []) {
        const card = create("div", { className: "preset-card" + (preset.key === activeKey ? " active" : "") });
        const header = create("div", { className: "preset-card-header" });
        header.appendChild(create("span", { className: "preset-card-title", text: preset.label }));
        if (preset.key === activeKey) {
            const divergent = fieldsThatDivergeFromPreset(activeTemplate, preset);
            const badge = create("span", {
                className: "preset-card-badge " + (divergent.length === 0 ? "matches" : "diverged"),
                text: divergent.length === 0 ? "Active · matches" : `Active · customized (${divergent.length})`,
            });
            header.appendChild(badge);
        }
        card.appendChild(header);

        const th = preset.thresholds;
        const summary = create("ul", { className: "preset-card-summary" });
        summary.appendChild(create("li", { text: `Work threshold: ${formatMinutes(th.workThresholdMinutes)}` }));
        summary.appendChild(create("li", { text: `Required break: ${formatMinutes(th.breakThresholdMinutes)}` }));
        summary.appendChild(create("li", { text: `Min segment: ${formatMinutes(th.minBreakSegmentMinutes)}` }));
        if (th.secondWorkThresholdMinutes) {
            summary.appendChild(create("li", { text: `2nd tier: ${formatMinutes(th.secondWorkThresholdMinutes)} → ${formatMinutes(th.secondBreakThresholdMinutes)}` }));
        }
        summary.appendChild(create("li", { text: `Split breaks: ${th.allowSplitBreaks ? "allowed" : "one block required"}` }));
        card.appendChild(summary);

        if (preset.description) {
            card.appendChild(create("p", { className: "preset-card-desc", text: preset.description }));
        }

        const applyBtn = create("button", {
            className: "btn-secondary preset-card-apply",
            text: preset.key === activeKey ? "Re-apply" : "Apply this preset",
        });
        applyBtn.type = "button";
        applyBtn.disabled = state.chooserBusy === preset.key;
        if (state.chooserBusy === preset.key) applyBtn.textContent = "Applying…";
        applyBtn.addEventListener("click", () => applyPreset(preset.key));
        card.appendChild(applyBtn);

        panel.appendChild(card);
    }
}

async function applyPreset(presetKey, options = {}) {
    if (!isAdmin()) return;
    const activePreset = activePresetFromCatalog();
    const target = (state.presetCatalog ?? []).find(p => p.key === presetKey);
    const activeTemplate = state.session?.activeTemplate;
    if (!target) return;

    // Confirm if applying would overwrite user customizations. Skip the
    // prompt when the user explicitly chose "Reset to preset" via the pill.
    if (!options.skipDivergenceConfirm && activePreset && activeTemplate) {
        const divergent = fieldsThatDivergeFromPreset(activeTemplate, activePreset);
        if (divergent.length > 0) {
            const ok = window.confirm(
                `Applying "${target.label}" will overwrite ${divergent.length} customized field${divergent.length === 1 ? "" : "s"}. Continue?`);
            if (!ok) return;
        }
    }

    state.chooserBusy = presetKey;
    renderPresetChooser();
    try {
        await deps.api("/api/presets/apply", {
            method: "POST",
            body: JSON.stringify({ presetKey }),
        });
        // Re-fetch the session so the chip + popover + customized pill
        // reflect the new threshold values.
        state.session = await deps.api("/api/session");
        renderActiveTemplate();
        renderCustomizedPill();
        deps.afterApply();
        deps.showBanner("ok", `Applied "${target.label}". Reload the Clockify settings page if you want to fine-tune individual fields.`);
        togglePresetChooser(false);
    } catch (err) {
        deps.showBanner("err", err instanceof HttpError
            ? `Couldn't apply preset: ${err.message}`
            : "Couldn't apply preset.");
    } finally {
        state.chooserBusy = null;
        renderPresetChooser();
    }
}

export function renderActiveTemplate() {
    const labelNode = el("active-template-label");
    const chip = el("active-template-chip");
    const details = el("active-template-details");

    const presetKey = state.session?.appliedPresetKey ?? "custom-basic";
    // Prefer the catalog's label so any future preset addition shows up
    // correctly without a code update. Fall back to the static map.
    const catalogEntry = (state.presetCatalog ?? []).find(p => p.key === presetKey);
    const presetLabel = catalogEntry?.label ?? PRESET_LABELS[presetKey] ?? presetKey;
    labelNode.textContent = presetLabel;

    clearChildren(details);
    const active = state.session?.activeTemplate;
    if (!active) {
        details.appendChild(create("p", { className: "muted", text: "Thresholds not configured yet — open the settings page in Clockify." }));
        chip.removeAttribute("data-preview");
        return;
    }

    // P2.5 — one-line summary on chip hover, so admins don't have to click
    // the chip to glance at the current rule.
    // P2.7 — also mirror onto aria-label so screen-reader users get the
    // same context (CSS ::after pseudo-content is invisible to AT).
    const splitNote = active.allowSplitBreaks ? "split allowed" : "single block required";
    const segmentNote = `${active.minBreakSegmentMinutes ?? 0}m segments OK`;
    const preview = `≥${active.workThresholdMinutes ?? 0}m work → ≥${active.breakThresholdMinutes ?? 0}m break (${segmentNote}, ${splitNote})`;
    chip.setAttribute("data-preview", preview);
    chip.setAttribute("aria-label", `Active preset: ${presetLabel}. ${preview}. Click to see full thresholds.`);

    const rows = [
        ["Work threshold", formatMinutes(active.workThresholdMinutes)],
        ["Required break", formatMinutes(active.breakThresholdMinutes)],
        ["Min break segment", formatMinutes(active.minBreakSegmentMinutes)],
        ["Max continuous work", formatMinutes(active.maxContinuousWorkMinutes)],
        ["Grace period", formatMinutes(active.gracePeriodMinutes)],
        ["Split breaks", active.allowSplitBreaks
            ? "Allowed (sum of qualifying segments)"
            : "Not allowed (single uninterrupted break required — California meal-rule)"],
    ];
    if (active.secondWorkThresholdMinutes && active.secondWorkThresholdMinutes > 0) {
        rows.push(["Second-tier work threshold", formatMinutes(active.secondWorkThresholdMinutes)]);
        rows.push(["Second-tier required break", formatMinutes(active.secondBreakThresholdMinutes)]);
    } else {
        rows.push(["Second tier", "Disabled"]);
    }
    rows.push(["Timezone strategy", TIMEZONE_LABELS[active.timezoneStrategy] ?? (active.timezoneStrategy ?? "—")]);
    rows.push(["Fallback detection", active.fallbackDetectionEnabled ? "On" : "Off"]);

    const dl = create("dl", { className: "threshold-list" });
    for (const [k, v] of rows) {
        dl.appendChild(create("dt", { text: k }));
        dl.appendChild(create("dd", { text: v }));
    }
    details.appendChild(dl);
    chip.setAttribute("aria-expanded", state.detailsOpen ? "true" : "false");
    details.hidden = !state.detailsOpen;
}

export function toggleActiveTemplateDetails(force) {
    state.detailsOpen = typeof force === "boolean" ? force : !state.detailsOpen;
    renderActiveTemplate();
}
