window.__ModuleLoader__.load({
  id: "dsh-sidebar-unhide",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    /**
     * True when a Tauri desktop shell owns the sidebar toggle.
     *
     * dsh-tauri hides the in-page collapse button because the shell's title-bar
     * icon posts `dsh://sidebar:toggle`. If that shell is absent (phone browser,
     * plain Chrome), the button must remain visible or the sidebar deadlocks
     * open. Detection is deliberately conservative: any of these signals means
     * "a shell is present, leave the button alone".
     */
    function hasTauriShell() {
      try {
        if (typeof window.__TAURI__ !== "undefined") return true;
        if (typeof window.__TAURI_INTERNALS__ !== "undefined") return true;
        if (typeof window.__TAURI_IPC__ === "function") return true;
        // dsh-desktop injects a postMessage bridge; if we are framed by a
        // parent that could own the toggle, do not fight it.
        if (window.parent && window.parent !== window) return true;
      } catch {
        // Cross-origin parent access throws; treat that as "unknown shell"
        // and leave the button under dsh-tauri's rule.
        return true;
      }
      return false;
    }

    /**
     * CSS that wins against dsh-tauri's `display:none !important`.
     *
     * Both rules use !important, so the later / more specific one wins. The
     * extra class (dshp-panel__toggle, from dsh-tauri-panel which replaced the
     * core sidebar) raises specificity above the aria-label-only hide rule.
     */
    function unhideCss() {
      return [
        "button.dshp-panel__toggle[aria-label='收起侧边栏'],",
        "button.dshp-panel__toggle[aria-label='Collapse sidebar'],",
        "button[aria-label='收起侧边栏'],",
        "button[aria-label='Collapse sidebar']",
        "{ display: inline-flex !important; visibility: visible !important; pointer-events: auto !important; }",
      ].join("");
    }

    const TAG_ID = "dsh-sidebar-unhide/unhide.css";

    function injectStyle(css) {
      if (typeof document === "undefined") return;
      if (document.querySelector("style[data-plugin-css=" + JSON.stringify(TAG_ID) + "]")) return;
      const tag = document.createElement("style");
      tag.dataset.plugin = "dsh-sidebar-unhide";
      tag.dataset.pluginCss = TAG_ID;
      tag.textContent = css;
      document.head.appendChild(tag);
    }

    const inject = [];

    function apply(_ctx) {
      // Only act outside a desktop shell. Inside Tauri the shell's own control
      // is the correct entry point, and re-showing the button would duplicate it.
      if (hasTauriShell()) return;
      injectStyle(unhideCss());
    }

    exports.apply = apply;
    exports.inject = inject;
    exports.hasTauriShell = hasTauriShell;
    exports.unhideCss = unhideCss;
    return module.exports;
  },
});
