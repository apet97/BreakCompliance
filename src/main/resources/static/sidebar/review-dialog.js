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

export function requestReviewNote(nextStatus) {
  return new Promise(resolve => {
    const previousFocus = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    const titleId = `review-dialog-title-${Date.now()}`;
    const textareaId = `review-dialog-note-${Date.now()}`;
    let closed = false;

    const overlay = create("div", { className: "review-dialog-backdrop" });
    const dialog = create("div", {
      className: "review-dialog",
      role: "dialog",
      "aria-modal": "true",
      "aria-labelledby": titleId,
    });
    const title = create("h2", {
      id: titleId,
      className: "review-dialog-title",
      text: nextStatus === "ACKNOWLEDGED" ? "Acknowledge finding" : "Override finding",
    });
    const label = create("label", {
      className: "review-dialog-label",
      for: textareaId,
      text: "Review note",
    });
    const textarea = create("textarea", {
      id: textareaId,
      className: "review-dialog-textarea",
      rows: "4",
      maxlength: "1000",
    });
    const actions = create("div", { className: "review-dialog-actions" });
    const cancel = create("button", {
      className: "btn-link review-dialog-cancel",
      type: "button",
      text: "Cancel",
    });
    const noNote = create("button", {
      className: "btn-secondary review-dialog-no-note",
      type: "button",
      text: "Continue without note",
    });
    const save = create("button", {
      className: "btn-primary review-dialog-save",
      type: "button",
      text: "Save",
    });

    function close(value) {
      if (closed) return;
      closed = true;
      document.removeEventListener("keydown", onKeyDown);
      overlay.remove();
      previousFocus?.focus?.();
      resolve(value);
    }

    function onKeyDown(event) {
      if (event.key === "Escape") {
        event.preventDefault();
        close(undefined);
      }
      if (event.key !== "Tab") return;
      const focusables = [textarea, cancel, noNote, save];
      const current = document.activeElement;
      const idx = focusables.indexOf(current);
      if (event.shiftKey && (idx <= 0)) {
        event.preventDefault();
        save.focus();
      } else if (!event.shiftKey && idx === focusables.length - 1) {
        event.preventDefault();
        textarea.focus();
      }
    }

    cancel.addEventListener("click", () => close(undefined));
    noNote.addEventListener("click", () => close(null));
    save.addEventListener("click", () => close(textarea.value.trim() || null));
    overlay.addEventListener("click", event => {
      if (event.target === overlay) close(undefined);
    });

    actions.append(cancel, noNote, save);
    dialog.append(title, label, textarea, actions);
    overlay.appendChild(dialog);
    document.body.appendChild(overlay);
    document.addEventListener("keydown", onKeyDown);
    textarea.focus();
  });
}
