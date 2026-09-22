var __schoolModule = (() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

  // ../zhengfang-plugins/templates/native-components/src/index.ts
  var index_exports = {};
  __export(index_exports, {
    default: () => index_default
  });
  var initial = () => ({ label: "\u6211\u7684\u539F\u751F\u9875\u9762", count: 0, details: true, mode: "calm", progress: 0.4, shown: 12, status: "\u6B63\u5728\u8BFB\u53D6\u504F\u597D\u2026", ready: false });
  var text = (id, value) => ({ id, type: "text", text: value });
  var button = (id, label, event, enabled = true) => ({ id, type: "button", label, event, enabled });
  var effect = (id, capability, input) => ({ id, capability, version: 1, input });
  function view(state, pageId) {
    if (pageId === "summary") return { id: "summary-root", type: "column", gap: 12, padding: 16, children: [
      text("saved-title", state.label),
      text("saved-count", `\u8BA1\u6570\uFF1A${state.count}`),
      text("status", state.status),
      button("back", "\u8FD4\u56DE\u7EC4\u4EF6\u9875", "back")
    ] };
    const children = [
      { id: "heading", type: "text", text: state.label, style: "title" },
      text("status", state.status),
      { id: "form", type: "column", gap: 12, padding: 16, surface: "glass", children: [
        { id: "label", type: "input", label: "\u9875\u9762\u6807\u9898", value: state.label, event: "label.change" },
        { id: "details", type: "toggle", label: "\u663E\u793A\u56FE\u8868\u4E0E\u5217\u8868", value: state.details, event: "details.change" },
        { id: "mode", type: "select", label: "\u663E\u793A\u504F\u597D", value: state.mode, event: "mode.change", options: [{ value: "calm", label: "\u7B80\u6D01" }, { value: "detail", label: "\u8BE6\u7EC6" }] },
        { id: "progress-input", type: "slider", label: "\u793A\u4F8B\u8FDB\u5EA6", value: state.progress, min: 0, max: 1, steps: 9, event: "progress.change" }
      ] },
      { id: "counter-row", type: "row", gap: 12, children: [text("counter", `\u8BA1\u6570\uFF1A${state.count}`), button("increment", "\u589E\u52A0", "increment")] },
      { id: "progress", type: "progress", value: state.progress, label: `\u5B8C\u6210 ${Math.round(state.progress * 100)}%` },
      button("save", "\u4FDD\u5B58\u504F\u597D", "save", state.ready),
      button("summary", "\u67E5\u770B\u5DF2\u4FDD\u5B58\u5185\u5BB9", "summary", state.ready),
      button("load", "\u67E5\u8BE2\u793A\u4F8B\u6570\u636E\u6E90", "load", state.ready)
    ];
    if (state.details) children.push(
      { id: "chart", type: "canvas", width: 300, height: 90, label: "\u793A\u4F8B\u8FDB\u5EA6\u56FE", shapes: [
        { id: "baseline", type: "line", x: 0, y: 80, x2: 300, y2: 80, stroke: 2, color: "muted" },
        { id: "bar", type: "rect", x: 10, y: 10, width: state.progress * 280, height: 60, color: "primary" }
      ] },
      { id: "items", type: "list", height: 200, gap: 8, onEnd: "items.end", children: Array.from({ length: state.shown }, (_, i) => text(`item-${i + 1}`, `\u793A\u4F8B\u6761\u76EE ${i + 1}`)) }
    );
    return { id: "root", type: "scroll", gap: 16, padding: 8, children };
  }
  var result = (state, page, effects = []) => ({ state, view: view(state, page), effects });
  var plugin = {
    ui: {
      init: ({ pageId }) => result(initial(), pageId, [effect("preferences.read", "storage.get", { key: "preferences" })]),
      reduce: ({ pageId, state, event }) => {
        let next = { ...state };
        const effects = [];
        if (event.type === "effect.result") {
          if (!event.result?.ok) next.status = `\u672A\u5B8C\u6210\uFF1A${event.result?.error.code || "UNKNOWN"}`;
          else if (event.effectId === "preferences.read") {
            const saved = event.result.data;
            if (saved && typeof saved.label === "string" && typeof saved.count === "number") next = { ...next, label: saved.label, count: saved.count };
            next.status = saved ? "\u5DF2\u6062\u590D\u5F53\u524D\u8D26\u53F7\u4E0E\u7248\u672C\u7684\u504F\u597D" : "\u53EF\u4EE5\u5F00\u59CB\u7EC4\u5408\u9875\u9762";
          } else if (event.effectId === "preferences.write") next.status = "\u504F\u597D\u5DF2\u4FDD\u5B58";
          else if (event.effectId === "rows.read") next.status = `\u6570\u636E\u6E90\u8FD4\u56DE ${event.result.data.items.length} \u6761\u8BB0\u5F55`;
          next.ready = true;
        } else {
          switch (event.name) {
            case "label.change":
              next.label = String(event.value).slice(0, 80);
              break;
            case "details.change":
              next.details = event.value === true;
              break;
            case "mode.change":
              next.mode = event.value === "detail" ? "detail" : "calm";
              break;
            case "progress.change":
              next.progress = Math.max(0, Math.min(1, Number(event.value)));
              break;
            case "increment":
              next.count++;
              break;
            case "items.end":
              next.shown = Math.min(60, next.shown + 12);
              break;
            case "reset":
              next = { ...initial(), ready: true, status: "\u5DF2\u91CD\u7F6E\uFF1B\u70B9\u51FB\u4FDD\u5B58\u540E\u624D\u8986\u76D6\u504F\u597D" };
              break;
            case "save":
              next.ready = false;
              effects.push(effect("preferences.write", "storage.set", { key: "preferences", value: { label: next.label, count: next.count } }));
              break;
            case "summary":
              effects.push(effect("summary.open", "navigation.page", { pageId: "summary" }));
              break;
            case "back":
              effects.push(effect("overview.open", "navigation.page", { pageId: "overview" }));
              break;
            case "load":
              effects.push(effect("rows.read", "data.query", { providerId: "sample.rows", input: { label: next.label } }));
              break;
          }
        }
        return result(next, pageId, effects);
      }
    },
    data: { query: ({ input, cursor }) => {
      const start = Math.max(0, Math.min(40, Number(cursor) || 0));
      const label = input && typeof input === "object" && !Array.isArray(input) ? String(input.label || "\u793A\u4F8B") : "\u793A\u4F8B";
      return { items: Array.from({ length: 10 }, (_, i) => ({ id: `row-${start + i}`, label: `${label} ${start + i + 1}` })), ...start < 40 ? { nextCursor: String(start + 10) } : {} };
    } }
  };
  var index_default = plugin;
  return __toCommonJS(index_exports);
})();
globalThis.plugin=__schoolModule.default;
