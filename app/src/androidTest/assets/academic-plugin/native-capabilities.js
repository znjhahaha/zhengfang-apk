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

  // ../zhengfang-plugins/templates/native-capabilities/src/index.ts
  var index_exports = {};
  __export(index_exports, {
    default: () => index_default
  });
  var initial = () => ({ text: "Hello, v3!", status: "\u9009\u62E9\u4E00\u79CD\u5BBF\u4E3B\u80FD\u529B\u8FDB\u884C\u4F53\u9A8C", file: "", credential: "", available: [], pending: "", serial: 0 });
  var effect = (id, capability, input) => ({ id, capability, version: 1, input });
  var controls = [
    ["pick", "\u9009\u62E9\u6587\u4EF6", "files.pick"],
    ["create", "\u521B\u5EFA\u793A\u4F8B\u6587\u4EF6", "files.create"],
    ["read", "\u8BFB\u53D6\u6587\u4EF6", "files.read"],
    ["share", "\u5206\u4EAB\u6587\u4EF6", "files.share"],
    ["open", "\u6253\u5F00\u6587\u4EF6", "files.open"],
    ["remove", "\u5220\u9664\u6587\u4EF6", "files.remove"],
    ["clipboard", "\u590D\u5236\u793A\u4F8B\u6587\u5B57", "device.clipboard.write"],
    ["paste", "\u8BFB\u53D6\u526A\u8D34\u677F", "device.clipboard.read"],
    ["haptic", "\u89E6\u611F\u53CD\u9988", "device.haptic"],
    ["network", "\u67E5\u8BE2\u793A\u4F8B\u63A5\u53E3", "network.request"],
    ["auth", "\u8BA4\u8BC1\u6311\u6218", "auth.prompt"],
    ["session-save", "\u52A0\u5BC6\u4FDD\u5B58\u4F1A\u8BDD", "session.save"],
    ["session-restore", "\u6062\u590D\u4F1A\u8BDD", "session.restore"],
    ["session-clear", "\u6E05\u9664\u4F1A\u8BDD", "session.clear"],
    ["notify", "\u53D1\u9001\u793A\u4F8B\u901A\u77E5", "notifications.post"],
    ["task", "\u5B89\u6392\u4E00\u5206\u949F\u540E\u7684\u7B14\u8BB0\u4EFB\u52A1", "tasks.schedule"],
    ["tasks", "\u67E5\u8BE2\u4EFB\u52A1\u72B6\u6001", "tasks.list"],
    ["cancel", "\u53D6\u6D88\u7B49\u5F85\u4E2D\u7684\u64CD\u4F5C", "runtime.cancel"]
  ];
  function result(state, effects = []) {
    const children = [
      { id: "intro", type: "text", text: "\u80FD\u529B\u7531 App \u5B9E\u9645\u62A5\u544A\u3002\u6587\u4EF6\u548C\u51ED\u636E\u53EA\u901A\u8FC7\u53D7\u9650\u53E5\u67C4\u4F7F\u7528\uFF1B\u7F51\u7EDC\u5730\u5740\u4EC5\u4F9B\u79BB\u7EBF\u6837\u672C\u6F14\u793A\u3002" },
      { id: "status", type: "text", text: state.status },
      { id: "text", type: "input", label: "\u793A\u4F8B\u6587\u5B57", value: state.text, event: "text.change" },
      { id: "handle", type: "text", text: state.file ? "\u5DF2\u9009\u62E9\u6587\u4EF6\uFF0C\u53EF\u8BFB\u53D6\u6216\u4EA4\u7ED9\u5176\u4ED6\u5E94\u7528" : "\u8FD8\u6CA1\u6709\u6587\u4EF6" }
    ];
    for (const [id, label, capability] of controls) children.push({ id, type: "button", label, event: id, enabled: state.available.includes(capability) && (!["read", "share", "open", "remove"].includes(id) || !!state.file) && (id !== "cancel" || !!state.pending) });
    return { state, view: { id: "root", type: "scroll", gap: 12, padding: 12, children }, effects };
  }
  var plugin = {
    ui: {
      init: async (_args, _context, sdk) => result({ ...initial(), available: (await sdk.capabilities.list()).map((c) => c.name) }),
      reduce: ({ state, event }) => {
        const next = { ...state };
        const effects = [];
        const send = (name, input, prefix = name) => {
          const id = `${prefix}.${++next.serial}`;
          next.pending = id;
          effects.push(effect(id, name, input));
        };
        if (event.type === "effect.result") {
          if (event.effectId === next.pending) next.pending = "";
          if (!event.result?.ok) next.status = `\u64CD\u4F5C\u672A\u5B8C\u6210\uFF1A${event.result?.error.code || "UNKNOWN"}\u3002\u7ED3\u679C\u672A\u77E5\u65F6\u8BF7\u5148\u6838\u5B9E\uFF0C\u4E0D\u80FD\u81EA\u52A8\u91CD\u8BD5\u3002`;
          else {
            const data = event.result.data;
            next.status = "\u64CD\u4F5C\u5DF2\u5B8C\u6210";
            if (event.effectId?.startsWith("files.pick.") || event.effectId?.startsWith("files.create.")) {
              next.file = data.handle;
              if (event.effectId.startsWith("files.create.")) send("files.write", { handle: next.file, base64: "SGVsbG8sIHYzIQ==" });
            } else if (event.effectId?.startsWith("files.remove.")) next.file = "";
            else if (event.effectId?.startsWith("files.read.")) next.status = `\u5DF2\u8BFB\u53D6 ${data.size} \u5B57\u8282\uFF08\u8FD4\u56DE Base64\uFF09`;
            else if (event.effectId?.startsWith("auth.prompt.")) {
              next.credential = data.credential;
              next.status = "\u5BBF\u4E3B\u5DF2\u4FDD\u5B58\u51ED\u636E\uFF0C\u63D2\u4EF6\u53EA\u5F97\u5230\u53E5\u67C4";
            } else if (event.effectId?.startsWith("device.clipboard.read.")) next.text = data.text;
            else if (event.effectId?.startsWith("tasks.list.")) next.status = `\u5F53\u524D\u7248\u672C\u6709 ${data.length} \u6761\u4EFB\u52A1\u8BB0\u5F55`;
          }
        } else switch (event.name) {
          case "text.change":
            next.text = String(event.value).slice(0, 2e3);
            break;
          case "pick":
            send("files.pick", { mimeTypes: ["text/*", "image/*"] });
            break;
          case "create":
            send("files.create", { name: "native-example.txt", mime: "text/plain" });
            break;
          case "read":
            send("files.read", { handle: next.file, length: 262144 });
            break;
          case "share":
            send("files.share", { handle: next.file });
            break;
          case "open":
            send("files.open", { handle: next.file });
            break;
          case "remove":
            send("files.remove", { handle: next.file });
            break;
          case "clipboard":
            send("device.clipboard.write", { text: next.text });
            break;
          case "paste":
            send("device.clipboard.read", {});
            break;
          case "haptic":
            send("device.haptic", {});
            break;
          case "network":
            send("network.request", { url: "https://example.test/plugin-demo/items", purpose: "query" });
            break;
          case "auth":
            send("auth.prompt", { title: "\u793A\u4F8B\u8BA4\u8BC1\u6311\u6218", key: "example-login", remember: true, fields: [{ id: "username", label: "\u6F14\u793A\u8D26\u53F7", type: "text" }, { id: "password", label: "\u6F14\u793A\u5BC6\u7801", type: "password" }] });
            break;
          case "session-save":
            send("session.save", { key: "example-session" });
            break;
          case "session-restore":
            send("session.restore", { key: "example-session" });
            break;
          case "session-clear":
            send("session.clear", { key: "example-session" });
            break;
          case "notify":
            send("notifications.post", { id: 1, title: "\u539F\u751F\u63D2\u4EF6\u793A\u4F8B", body: next.text });
            break;
          case "task":
            send("tasks.schedule", { taskId: "remember", delaySeconds: 60, input: next.text, state: { ...next, pending: "" } });
            break;
          case "tasks":
            send("tasks.list", {});
            break;
          case "cancel":
            effects.push(effect(`cancel.${++next.serial}`, "runtime.cancel", { effectIds: [next.pending] }));
            break;
        }
        return result(next, effects);
      }
    },
    task: { run: ({ input, state, event }) => ({ state: { ...state, status: event ? "\u7B14\u8BB0\u5DF2\u5904\u7406" : "\u6B63\u5728\u4FDD\u5B58\u7B14\u8BB0" }, effects: event ? [] : [effect("task-note.write", "storage.set", { key: "task-note", value: input })] }) }
  };
  var index_default = plugin;
  return __toCommonJS(index_exports);
})();
globalThis.plugin=__schoolModule.default;
