#!/usr/bin/env node
/**
 * Browser storage gate (`npm run guards`).
 *
 * The session (token, profile) and clinical text live in memory, never in web
 * storage. `localStorage` outlives the tab and the sign-out and is shared by
 * everyone who uses the browser, so the portal writes only device preferences:
 * every `localStorage.setItem` key must be in `LOCAL_STORAGE_ALLOWLIST`. Every
 * `sessionStorage.setItem` key must be in `SESSION_SCOPED_KEYS`, the list the
 * sign-out clears. Both lists live in src/app/core/browser-storage.ts, the one
 * module allowed to build keys at run time (the patient's notes); everywhere
 * else a key is a string literal or a string constant declared in the file.
 *
 * Runs on its own too: `node scripts/check-browser-storage.mjs`.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { componentSources } from './lib/component-sources.mjs';
import { PORTAL_DIR } from './lib/template-gate.mjs';

export const REGISTRY_FILE = 'src/app/core/browser-storage.ts';

/** The string literals of `export const <name> … = [ … ];` in the registry source. */
export function listLiterals(source, name) {
  const block = new RegExp(`export const ${name}\\b[^=]*=\\s*\\[([\\s\\S]*?)\\];`).exec(source);
  if (!block) throw new Error(`${REGISTRY_FILE}: ${name} not found`);
  return [...block[1].matchAll(/'([^']+)'|"([^"]+)"/g)].map((m) => m[1] ?? m[2]);
}

export function loadRegistry(source = readFileSync(resolve(PORTAL_DIR, REGISTRY_FILE), 'utf8')) {
  return {
    local: new Set(listLiterals(source, 'LOCAL_STORAGE_ALLOWLIST')),
    session: new Set(listLiterals(source, 'SESSION_SCOPED_KEYS')),
  };
}

/** A key argument as written: a literal, or a constant declared in the same file. */
function resolveKey(arg, text) {
  const literal = /^(['"`])([^'"`$]*)\1$/.exec(arg);
  if (literal) return literal[2];
  const ident = /^(?:[A-Za-z_$][\w$]*\.)*([A-Za-z_$][\w$]*)$/.exec(arg);
  if (!ident) return null;
  const decl = new RegExp(`\\b${ident[1]}\\s*(?::\\s*[\\w.]+\\s*)?=\\s*(['"])([^'"]+)\\1`).exec(
    text,
  );
  return decl ? decl[2] : null;
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length;
}

/**
 * @param {{ file: string, text: string }[]} components non-spec `.ts` sources
 * @param {{ local: Set<string>, session: Set<string> }} registry
 * @returns {string[]} one message per offending write
 */
export function storageFindings(components, registry) {
  const findings = [];
  for (const { file, text } of components) {
    if (file === REGISTRY_FILE || file.startsWith('src/app/testing/')) continue;
    for (const m of text.matchAll(
      /\b(localStorage|sessionStorage)\s*\??\.\s*setItem\s*\(\s*([^,]+?)\s*,/g,
    )) {
      const [, store, arg] = m;
      const where = `${file}:${lineOf(text, m.index)}`;
      const key = resolveKey(arg, text);
      if (key === null) {
        findings.push(
          `${where}: ${store} key \`${arg}\` is not a literal or a constant of this file — ` +
            `add a helper to ${REGISTRY_FILE} instead.`,
        );
      } else if (store === 'localStorage' && !registry.local.has(key)) {
        findings.push(
          `${where}: localStorage key '${key}' is not a device preference in LOCAL_STORAGE_ALLOWLIST ` +
            `(${REGISTRY_FILE}). Tokens, profiles and clinical text never go to web storage.`,
        );
      } else if (store === 'sessionStorage' && !registry.session.has(key)) {
        findings.push(
          `${where}: sessionStorage key '${key}' is not in SESSION_SCOPED_KEYS (${REGISTRY_FILE}). ` +
            `Tokens, profiles and clinical text stay in memory; other keys must be cleared at sign-out.`,
        );
      }
    }
    for (const m of text.matchAll(
      /\b(localStorage|sessionStorage)\s*(?:\[|\.(?!setItem\b|getItem\b|removeItem\b|clear\b|key\b|length\b)[A-Za-z_$][\w$]*\s*=(?!=))/g,
    )) {
      findings.push(
        `${file}:${lineOf(text, m.index)}: write ${m[1]} through setItem with a registered key.`,
      );
    }
  }
  return findings;
}

export function checkBrowserStorage(ctx = {}) {
  const findings = storageFindings(ctx.components ?? componentSources(), loadRegistry());
  for (const finding of findings) console.error(finding);
  if (findings.length) {
    console.error(`\n${findings.length} browser storage write(s) outside ${REGISTRY_FILE}'s lists`);
  }
  return findings.length === 0;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  process.exitCode = checkBrowserStorage() ? 0 : 1;
}
