// Execute in the userScript compartment, separately from the unchanged upstream.
// Native DOM members keep Xray wrappers so callbacks stay callable. Page globals
// use the waived window, as expected by scripts reading e.g. ServerHost or jQuery.
(() => {
  const windows = new WeakMap();
  const wrap = target => {
    if (windows.has(target)) return windows.get(target);
    const methods = new WeakMap();
    const proxy = new Proxy(target, {
      get(target, key) {
        if (['window', 'self', 'top', 'parent', 'frames'].includes(key)) return wrap(Reflect.get(target, key, target));
        if (!(key in target)) return target.wrappedJSObject[key];
        const value = Reflect.get(target, key, target);
        if (typeof value !== 'function') return value;
        if (!methods.has(value)) methods.set(value, value.bind(target));
        return methods.get(value);
      },
      set(target, key, value) {
        const destination = key in target ? target : target.wrappedJSObject;
        return Reflect.set(destination, key, value, destination);
      }
    });
    windows.set(target, proxy);
    return proxy;
  };
  Object.defineProperty(globalThis, 'unsafeWindow', {value: wrap(window), configurable: true});
})();
