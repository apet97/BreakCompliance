function create(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === "text") node.textContent = value;
    else if (key === "className") node.className = value;
    else if (value != null) node.setAttribute(key, value);
  }
  for (const child of Array.isArray(children) ? children : [children]) {
    if (child != null) node.appendChild(child);
  }
  return node;
}

function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

function labelFor(action) {
  if (action === "PRESET_APPLY") return "Preset applied";
  if (action === "FINDING_REVIEW") return "Finding reviewed";
  return String(action || "Audit event").replaceAll("_", " ").toLowerCase();
}

function detailText(entry) {
  const details = entry?.details && typeof entry.details === "object" ? entry.details : {};
  if (entry.action === "PRESET_APPLY" && details.presetKey) return String(details.presetKey);
  if (entry.action === "FINDING_REVIEW" && details.status) return String(details.status);
  return entry.entityId ? String(entry.entityId) : "";
}

function formatTimestamp(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function renderAuditPanel(root, model) {
  clear(root);
  if (!model.isAdmin) {
    root.hidden = true;
    return;
  }
  root.hidden = false;

  const header = create("div", { className: "audit-panel-header" });
  header.appendChild(create("h2", {
    id: "audit-panel-title",
    className: "audit-panel-title",
    text: "Audit log",
  }));
  const refresh = create("button", {
    id: "audit-refresh-btn",
    className: "btn-link audit-refresh-btn",
    type: "button",
    text: model.loading ? "Loading…" : "Refresh",
  });
  refresh.disabled = !!model.loading;
  refresh.addEventListener("click", model.onRefresh);
  header.appendChild(refresh);
  root.appendChild(header);

  if (model.range) {
    root.appendChild(create("p", {
      className: "audit-panel-range",
      text: `${model.range.start} to ${model.range.end}`,
    }));
  }

  const body = create("div", { className: "audit-panel-body", role: "log" });
  if (model.loading) {
    body.appendChild(create("p", { className: "audit-panel-empty", text: "Loading audit events…" }));
  } else if (!model.loaded) {
    body.appendChild(create("p", { className: "audit-panel-empty", text: "No audit range loaded." }));
  } else if (!Array.isArray(model.entries) || model.entries.length === 0) {
    body.appendChild(create("p", { className: "audit-panel-empty", text: "No audit events in this range." }));
  } else {
    const list = create("ul", { className: "audit-event-list" });
    for (const entry of model.entries) {
      const item = create("li", { className: "audit-event" });
      const main = create("div", { className: "audit-event-main" });
      main.appendChild(create("span", { className: "audit-event-action", text: labelFor(entry.action) }));
      const detail = detailText(entry);
      if (detail) main.appendChild(create("span", { className: "audit-event-detail", text: detail }));
      item.appendChild(main);
      item.appendChild(create("div", {
        className: "audit-event-meta",
        text: [entry.actor, formatTimestamp(entry.createdAt)].filter(Boolean).join(" · "),
      }));
      list.appendChild(item);
    }
    body.appendChild(list);
  }
  root.appendChild(body);
}
