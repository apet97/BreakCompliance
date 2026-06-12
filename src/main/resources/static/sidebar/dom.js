export function el(id) {
    const node = document.getElementById(id);
    if (!node) throw new Error(`expected #${id} in sidebar markup`);
    return node;
}

export function clearChildren(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
}

export function create(tag, opts, children) {
    const node = document.createElement(tag);
    if (opts?.className) node.className = opts.className;
    if (opts?.title) node.title = opts.title;
    if (opts?.href && tag === "a") node.href = opts.href;
    if (opts?.text !== undefined) node.textContent = opts.text;
    if (children) {
        for (const child of children) {
            node.appendChild(typeof child === "string" ? document.createTextNode(child) : child);
        }
    }
    return node;
}
