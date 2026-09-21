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

  // ../../plugins/templates/campus-service/src/index.ts
  var index_exports = {};
  __export(index_exports, {
    default: () => index_default
  });
  var failure = (code, message) => ({ ok: false, error: { code, message } });
  var identity = { status: "authenticated", studentId: "demo", studentName: "\u793A\u4F8B\u540C\u5B66" };
  var challenge = { status: "captcha", continuationId: "demo-captcha", mimeType: "image/png", imageBase64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII=" };
  async function authenticated(sdk) {
    return await sdk.state.get("authenticated") === true;
  }
  var records = (registered, keyword = "") => ({
    pageId: "records",
    title: "\u6D3B\u52A8\u8BB0\u5F55",
    subtitle: "\u672C\u9875\u4F7F\u7528\u865A\u6784\u6570\u636E\uFF0C\u5C1A\u672A\u8FDE\u63A5\u771F\u5B9E\u670D\u52A1\u3002",
    layout: "compact",
    blocks: [
      { id: "records", type: "list", title: "\u53C2\u4E0E\u8BB0\u5F55", items: [
        { id: "reading", title: "\u6821\u56ED\u9605\u8BFB\u65E5", subtitle: registered ? "\u5DF2\u62A5\u540D \xB7 \u6F14\u793A\u8BB0\u5F55" : "\u5C1A\u672A\u62A5\u540D \xB7 \u6F14\u793A\u6D3B\u52A8", value: registered ? "\u5DF2\u62A5\u540D" : "\u53EF\u62A5\u540D" },
        { id: "volunteer", title: "\u793E\u533A\u5FD7\u613F\u670D\u52A1", subtitle: "2026-09-12 \xB7 \u793A\u4F8B\u6D3B\u52A8", value: "2 \u5B66\u65F6" }
      ].filter((item) => item.title.includes(keyword)) },
      { id: "navigation", type: "actions", items: [{ label: "\u8FD4\u56DE\u6210\u957F\u8BB0\u5F55", action: { type: "page", pageId: "overview" } }] }
    ]
  });
  function overview(registered) {
    return { pageId: "overview", title: "\u6210\u957F\u8BB0\u5F55", subtitle: "\u793A\u4F8B\u5927\u5B66 \xB7 \u6821\u56ED\u670D\u52A1\u6F14\u793A", layout: "comfortable", blocks: [
      { id: "profile", type: "profile", title: "\u793A\u4F8B\u540C\u5B66", subtitle: "2026 \u7EA7 \xB7 \u793A\u4F8B\u5B66\u9662", details: ["\u6821\u56ED\u670D\u52A1\u8D26\u53F7\u4E0E\u6559\u52A1\u8D26\u53F7\u5206\u522B\u767B\u5F55"], badge: "\u865A\u6784\u6570\u636E" },
      { id: "summary", type: "metrics", title: "\u6211\u7684\u6210\u957F", columns: 3, items: [
        { id: "hours", label: "\u7D2F\u8BA1\u5B66\u65F6", value: "18", unit: "h" },
        { id: "credits", label: "\u83B7\u5F97\u5B66\u5206", value: "3.5" },
        { id: "activities", label: "\u53C2\u4E0E\u6D3B\u52A8", value: registered ? "7" : "6" }
      ] },
      { id: "progress", type: "progress", title: "\u5206\u7C7B\u8FDB\u5EA6", items: [
        { id: "practice", label: "\u793E\u4F1A\u5B9E\u8DF5", value: 8, max: 12, detail: "\u8FD8\u5DEE 4 \u5B66\u65F6", tone: "default" },
        { id: "volunteer", label: "\u5FD7\u613F\u670D\u52A1", value: 10, max: 10, tone: "positive", displayValue: "\u5DF2\u5B8C\u6210" }
      ] },
      { id: "recent", type: "list", title: "\u8FD1\u671F\u8BB0\u5F55", items: [{ id: "recent-reading", title: "\u6821\u56ED\u9605\u8BFB\u65E5", subtitle: registered ? "\u62A5\u540D\u6210\u529F\uFF08\u6F14\u793A\uFF09" : "\u67E5\u770B\u6D3B\u52A8\u8BB0\u5F55", action: { type: "page", pageId: "records" } }] },
      { id: "notice", type: "notice", text: "\u53EF\u901A\u8FC7\u201C\u8C03\u6574\u6392\u5E03\u201D\u79FB\u52A8\u3001\u9690\u85CF\u6A21\u5757\uFF0C\u4E5F\u53EF\u4EE5\u6062\u590D\u9ED8\u8BA4\u5E03\u5C40\u3002\u8FD9\u91CC\u6CA1\u6709\u771F\u5B9E\u6210\u7EE9\u6216\u62A5\u540D\u3002", tone: "default" },
      { id: "actions", type: "actions", title: "\u6D3B\u52A8\u5165\u53E3", items: [{ label: registered ? "\u67E5\u770B\u62A5\u540D\u8BB0\u5F55" : "\u62A5\u540D\u6F14\u793A\u6D3B\u52A8", action: registered ? { type: "page", pageId: "records" } : { type: "action", actionId: "register" } }] },
      { id: "search", type: "form", title: "\u67E5\u627E\u6D3B\u52A8", fields: [
        { id: "keyword", label: "\u6D3B\u52A8\u540D\u79F0", type: "text", required: false, placeholder: "\u4F8B\u5982\uFF1A\u9605\u8BFB" },
        { id: "category", label: "\u6D3B\u52A8\u5206\u7C7B", type: "select", required: true, options: [{ value: "all", label: "\u5168\u90E8\u6D3B\u52A8" }, { value: "volunteer", label: "\u5FD7\u613F\u670D\u52A1" }] }
      ], submit: { label: "\u67E5\u8BE2", actionId: "filter" } }
    ] };
  }
  var index_default = {
    auth: {
      async start(args, _context, sdk) {
        await sdk.state.remove("authenticated");
        await sdk.state.remove("captcha");
        if (args.password !== "demo" || !["demo", "captcha"].includes(args.username)) return failure("INVALID_CREDENTIALS", "\u6F14\u793A\u8D26\u53F7\u6216\u5BC6\u7801\u4E0D\u6B63\u786E");
        if (args.username === "captcha") {
          await sdk.state.set("captcha", true);
          return { ok: true, data: challenge };
        }
        await sdk.state.set("authenticated", true);
        return { ok: true, data: identity };
      },
      async resume(args, _context, sdk) {
        if (args.continuationId !== "demo-captcha" || !await sdk.state.get("captcha")) return failure("SESSION_EXPIRED", "\u9A8C\u8BC1\u7801\u4F1A\u8BDD\u5DF2\u5931\u6548");
        if (args.captcha !== "1234") return { ok: true, data: challenge };
        await sdk.state.remove("captcha");
        await sdk.state.set("authenticated", true);
        return { ok: true, data: identity };
      },
      async refreshCaptcha(args, _context, sdk) {
        return args.continuationId === "demo-captcha" && await sdk.state.get("captcha") ? { ok: true, data: challenge } : failure("SESSION_EXPIRED", "\u8BF7\u91CD\u65B0\u767B\u5F55");
      },
      async validate(_args, _context, sdk) {
        return await authenticated(sdk) ? { ok: true, data: identity } : failure("SESSION_EXPIRED", "\u8BF7\u5148\u767B\u5F55\u6B64\u670D\u52A1");
      }
    },
    service: {
      async page(args, _context, sdk) {
        if (!await authenticated(sdk)) return failure("SESSION_EXPIRED", "\u8BF7\u5148\u767B\u5F55\u6B64\u670D\u52A1");
        const registered = await sdk.state.get("registered") === true;
        if (args.pageId === "overview") return { ok: true, data: overview(registered) };
        if (args.pageId === "records") return { ok: true, data: records(registered, args.params?.keyword) };
        return failure("PAGE_CHANGED", "\u672A\u77E5\u9875\u9762");
      },
      async action(args, _context, sdk) {
        if (!await authenticated(sdk)) return failure("SESSION_EXPIRED", "\u8BF7\u5148\u767B\u5F55\u6B64\u670D\u52A1");
        if (args.actionId === "filter") {
          const page = records(await sdk.state.get("registered") === true, args.params?.keyword);
          if (args.params?.category === "volunteer") {
            const list = page.blocks[0];
            if (list.type === "list") list.items = list.items.filter((item) => item.id === "volunteer");
          }
          return { ok: true, data: { actionId: args.actionId, confirmed: true, page } };
        }
        if (args.actionId !== "register") return failure("PAGE_CHANGED", "\u672A\u77E5\u64CD\u4F5C");
        await sdk.state.set("registered", true);
        return { ok: true, data: { actionId: args.actionId, confirmed: true, message: "\u6F14\u793A\u62A5\u540D\u5DF2\u5B8C\u6210", page: overview(true) } };
      }
    }
  };
  return __toCommonJS(index_exports);
})();
globalThis.plugin=__schoolModule.default;
