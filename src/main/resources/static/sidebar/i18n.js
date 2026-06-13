const fallbackDictionary = {
    "app.title": "Break Compliance",
    "preset.activeLabel": "Active preset",
    "preset.switch": "Switch…",
    "admin.required": "Workspace admin required to refresh data and change settings. You can still browse existing findings.",
    "dateRange.label": "Date Range",
    "dateRange.today": "Today",
    "dateRange.thisWeek": "This Week",
    "dateRange.lastWeek": "Last Week",
    "dateRange.lastTwoWeeks": "Last 2 Weeks",
    "dateRange.lastMonth": "Last Month",
    "dateRange.allOpen": "All Open (last 90 days)",
    "dateRange.custom": "Custom Range",
    "dateRange.from": "From",
    "dateRange.to": "To",
    "action.check": "Check Compliance",
    "action.checking": "Checking…",
    "action.refresh": "Refresh",
    "action.cancel": "Cancel",
    "view.label": "View",
    "view.pivot": "Pivot Table",
    "view.checklist": "Checklist",
    "export.csv": "Export CSV",
    "loading.checking": "Checking compliance…",
    "settings.warningTitle": "Settings need a fix",
    "settings.warningFoot": "Reopen the settings page (⋯ → Settings on the add-on) to fix these, then re-run Check Compliance.",
    "status.pendingRefresh": "Pending refresh · webhook {relative}",
    "status.lastChecked": "Last checked {relative}",
};

let dictionary = { ...fallbackDictionary };

export async function loadI18n(locale = "en") {
    const response = await fetch(`/i18n/${locale}.json`, { credentials: "same-origin" });
    if (!response.ok) {
        throw new Error(`Unable to load locale ${locale}: ${response.status}`);
    }
    dictionary = { ...fallbackDictionary, ...await response.json() };
    return dictionary;
}

export function t(key, params = {}) {
    let value = dictionary[key] ?? key;
    for (const [name, replacement] of Object.entries(params)) {
        value = value.replaceAll(`{${name}}`, String(replacement));
    }
    return value;
}
