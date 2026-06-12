(function () {
  try {
    var theme = (new URLSearchParams(location.search).get("theme") || "").toUpperCase();
    if (theme === "DARK" || theme === "LIGHT") {
      document.documentElement.setAttribute("data-clockify-theme", theme.toLowerCase());
    }
  } catch (error) {
    // Theme bootstrap must never block the sidebar from rendering.
  }
})();
