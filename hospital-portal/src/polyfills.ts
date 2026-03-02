import 'zone.js';

// ─────────────────────────────────────────────────────────────────────────────
// Node-global shims for sockjs-client / @stomp/stompjs
//
// These libraries (and some of their transitive deps) were written for Node.js
// and reference the bare global `global` (and sometimes `process`).
// Angular 20 / esbuild does NOT auto-shim Node globals in browser bundles.
// When the bundle executes, the first reference to `global` throws:
//
//   ReferenceError: global is not defined
//
// This crashes Angular before bootstrapApplication() finishes → blank white
// screen. Mapping global → globalThis is safe in browser and SSR because
// globalThis is the universal "this root" object across all JS environments.
// ─────────────────────────────────────────────────────────────────────────────
(globalThis as unknown as Record<string, unknown>)['global'] = globalThis;

// Some deps also reference `process.env` (less common, but harmless to shim).
if (!(globalThis as unknown as Record<string, unknown>)['process']) {
  (globalThis as unknown as Record<string, unknown>)['process'] = { env: {} };
}
