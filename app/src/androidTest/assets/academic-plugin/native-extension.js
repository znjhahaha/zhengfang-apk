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

  // ../plugins/templates/native-extension/src/index.ts
  var index_exports = {};
  __export(index_exports, {
    default: () => index_default
  });
  var native = (operationId, params) => ({ type: "native", operationId, ...params ? { params } : {} });
  var overview = { pageId: "overview", title: "\u6821\u56ED\u5DE5\u5177", subtitle: "\u6240\u6709\u4E1A\u52A1\u6570\u636E\u5747\u4E3A\u6F14\u793A\uFF1B\u7CFB\u7EDF\u64CD\u4F5C\u7531\u4F60\u9010\u6B21\u786E\u8BA4\u3002", blocks: [
    { id: "profile", type: "profile", title: "\u7ED9\u6821\u56ED\u751F\u6D3B\u6DFB\u70B9\u5DE5\u5177", subtitle: "\u539F\u751F\u6269\u5C55\u793A\u4F8B", badge: "\u865A\u6784\u6570\u636E" },
    { id: "shortcuts", type: "grid", columns: 2, items: [
      { id: "report", label: "\u6210\u957F\u62A5\u544A", subtitle: "\u56FE\u8868\u4E0E\u8868\u683C", action: { type: "page", pageId: "report" } },
      { id: "scan", label: "\u626B\u63CF\u4E8C\u7EF4\u7801", subtitle: "\u53EA\u8BFB\u53D6\u6587\u5B57", action: native("scan") },
      { id: "file", label: "\u9009\u62E9\u6587\u4EF6", subtitle: "\u6700\u5927 64 KiB", action: native("file") },
      { id: "notify", label: "\u6F14\u793A\u901A\u77E5", subtitle: "\u4E00\u6B21\u6027\u63D0\u9192", action: native("notify", { title: "\u6821\u56ED\u5DE5\u5177\u6F14\u793A", body: "\u8FD9\u662F\u4F60\u521A\u624D\u5141\u8BB8\u53D1\u9001\u7684\u6F14\u793A\u901A\u77E5\u3002" }) },
      { id: "calendar", label: "\u6F14\u793A\u65E5\u7A0B", subtitle: "\u5728\u7CFB\u7EDF\u4E2D\u786E\u8BA4", action: native("event", { title: "\u6821\u56ED\u9605\u8BFB\u65E5\uFF08\u6F14\u793A\uFF09", startAt: "2026-10-01T09:00:00+08:00", endAt: "2026-10-01T10:00:00+08:00", location: "\u793A\u4F8B\u56FE\u4E66\u9986", description: "\u5F00\u53D1\u6A21\u677F\u7684\u6F14\u793A\u6D3B\u52A8\uFF0C\u8BF7\u6309\u9700\u8981\u4FDD\u5B58\u6216\u53D6\u6D88\u3002" }) }
    ] },
    { id: "note", type: "form", title: "\u8868\u5355\u6F14\u793A", fields: [
      { id: "note", label: "\u8BB0\u5F55\u60F3\u6CD5", type: "multiline", required: false, placeholder: "\u5199\u4E0B\u4E00\u4E2A\u60F3\u6CD5" },
      { id: "enabled", label: "\u663E\u793A\u5DF2\u5B8C\u6210\u9879\u76EE", type: "toggle", required: true, value: "false" }
    ], submit: { label: "\u9884\u89C8\u586B\u5199\u5185\u5BB9", actionId: "save-note" } }
  ] };
  var report = { pageId: "report", title: "\u6210\u957F\u62A5\u544A", subtitle: "\u8FD9\u4E9B\u6570\u503C\u6765\u81EA\u6A21\u677F\uFF0C\u4E0D\u662F\u4F60\u7684\u6559\u52A1\u6570\u636E\u3002", blocks: [
    { id: "summary", type: "keyValue", title: "\u62A5\u544A\u4FE1\u606F", items: [{ id: "term", label: "\u5B66\u671F", value: "2026 \u79CB\u5B63\uFF08\u6F14\u793A\uFF09" }, { id: "source", label: "\u6570\u636E\u6765\u6E90", value: "\u6A21\u677F\u5185\u7F6E\u865A\u6784\u6837\u672C" }] },
    { id: "chart", type: "barChart", title: "\u5404\u7C7B\u6D3B\u52A8", unit: "\u6B21", items: [{ id: "reading", label: "\u9605\u8BFB", value: 6 }, { id: "sport", label: "\u8FD0\u52A8", value: 4 }, { id: "volunteer", label: "\u5FD7\u613F\u6D3B\u52A8", value: 2 }] },
    { id: "table", type: "table", title: "\u6D3B\u52A8\u660E\u7EC6", columns: ["\u6D3B\u52A8", "\u7C7B\u578B", "\u65F6\u957F"], rows: [{ id: "one", cells: ["\u6821\u56ED\u9605\u8BFB\u65E5", "\u9605\u8BFB", "2 \u5C0F\u65F6"] }, { id: "two", cells: ["\u6821\u56ED\u6162\u8DD1", "\u8FD0\u52A8", "1 \u5C0F\u65F6"] }] },
    { id: "timeline", type: "timeline", title: "\u6700\u8FD1\u8BB0\u5F55", items: [{ id: "first", title: "\u53C2\u52A0\u9605\u8BFB\u5206\u4EAB", time: "9 \u6708 18 \u65E5", detail: "\u5B8C\u6210\u4E00\u6B21\u5C0F\u7EC4\u9605\u8BFB\u5206\u4EAB\u3002" }, { id: "second", title: "\u53C2\u52A0\u6821\u56ED\u6162\u8DD1", time: "9 \u6708 20 \u65E5", detail: "\u8BB0\u5F55\u4E00\u6B21\u8FD0\u52A8\u3002" }] },
    { id: "back", type: "actions", items: [{ label: "\u8FD4\u56DE\u6821\u56ED\u5DE5\u5177", action: { type: "page", pageId: "overview" } }] }
  ] };
  var plugin = { service: {
    page(args) {
      return args.pageId === "overview" || args.pageId === "report" ? { ok: true, data: args.pageId === "overview" ? overview : report } : { ok: false, error: { code: "UNSUPPORTED", message: "\u9875\u9762\u4E0D\u5B58\u5728" } };
    },
    action(args) {
      if (args.actionId === "native-result") {
        const result = args.nativeResult;
        if (!result) return { ok: false, error: { code: "VALIDATION_FAILED", message: "\u7F3A\u5C11\u7CFB\u7EDF\u64CD\u4F5C\u7ED3\u679C" } };
        const messages = { success: "\u7CFB\u7EDF\u64CD\u4F5C\u5DF2\u5B8C\u6210", cancelled: "\u4F60\u5DF2\u53D6\u6D88\u7CFB\u7EDF\u64CD\u4F5C", opened: "\u65E5\u5386\u7F16\u8F91\u5668\u5DF2\u6253\u5F00\uFF0C\u8BF7\u5728\u65E5\u5386\u4E2D\u786E\u8BA4\u662F\u5426\u4FDD\u5B58", unavailable: "\u8BBE\u5907\u672A\u63D0\u4F9B\u6B64\u80FD\u529B\u6216\u6743\u9650\u5C1A\u672A\u5F00\u542F", error: "\u7CFB\u7EDF\u64CD\u4F5C\u672A\u5B8C\u6210" };
        const extra = result.name ? `\uFF1A${result.name}\uFF08${result.size ?? 0} \u5B57\u8282\uFF09` : result.text ? `\uFF1A${result.text.slice(0, 120)}` : "";
        return { ok: true, data: { actionId: args.actionId, confirmed: true, message: messages[result.status] + extra } };
      }
      if (args.actionId === "save-note") return { ok: true, data: { actionId: args.actionId, confirmed: true, message: `\u8868\u5355\u5DF2\u8BFB\u53D6\uFF0C\u663E\u793A\u5DF2\u5B8C\u6210\uFF1A${args.params?.enabled === "true" ? "\u662F" : "\u5426"}\u3002\u6A21\u677F\u6CA1\u6709\u4FDD\u5B58\u8FD9\u4E9B\u5185\u5BB9\u3002` } };
      return { ok: false, error: { code: "UNSUPPORTED", message: "\u64CD\u4F5C\u4E0D\u5B58\u5728" } };
    }
  } };
  var index_default = plugin;
  return __toCommonJS(index_exports);
})();
globalThis.plugin=__schoolModule.default;
