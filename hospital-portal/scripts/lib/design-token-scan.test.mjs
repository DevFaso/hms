// QA r65 (D2790 / D2793 / D2804): the design-token gate's scanners, and the
// contrast of the tokens themselves read from the global stylesheet.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  classifyButtonSelector,
  contrast,
  rootTokens,
  scssFindings,
  sourceFindings,
  templateFindings,
} from './design-token-scan.mjs';
import { designTokenFindings, gate } from '../check-design-tokens.mjs';
import { compare, loadBaseline } from './baseline.mjs';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const rules = (hits) => hits.map((h) => `${h.rule}:${h.anchor}`);

test('a pre-rebrand colour is reported wherever it is written', () => {
  const hits = scssFindings(`
    .tab.active { color: #6366F1; border-bottom-color: #4f46e5; }
    .x:focus { box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.25); }
    .y { background: linear-gradient(135deg, #7c3aed, #5b21b6); }
    .z { outline: 2px solid rgba(99,102,241,.4); }
  `);
  assert.deepEqual(rules(hits).sort(), [
    'legacy-colour:#4f46e5',
    'legacy-colour:#6366f1',
    'legacy-colour:#7c3aed',
    'legacy-colour:legacy-halo',
    'legacy-colour:legacy-halo',
  ]);
});

test('the brand tokens and a comment that names an old colour are not reported', () => {
  const hits = scssFindings(`
    // was #6366f1 before the rebrand
    /* the pre-rebrand blue rgba(37, 99, 235) */
    .tab.active { color: var(--primary); box-shadow: var(--focus-halo); }
    .cat { color: var(--accent-violet); background: #ede9fe; }
  `);
  assert.deepEqual(hits, []);
});

test('a text colour below 4.5:1 is reported; the same grey as a border or a fill is not', () => {
  const hits = scssFindings(`
    .hint { color: #64748b; }
    .meta { color: var(--muted, #94a3b8); }
    .label {
      font-size: 12px;
      color:
        #6b7280;
    }
    .card { border: 1px solid #94a3b8; background: #64748b; outline-color: #9ca3af; }
    .ok { color: var(--text-secondary); }
    .ok2 { color: #475569; }
  `);
  assert.deepEqual(rules(hits), [
    'low-contrast-text:#64748b',
    'low-contrast-text:#94a3b8',
    'low-contrast-text:#6b7280',
  ]);
});

test('a stylesheet may not restyle a shared button', () => {
  const hits = scssFindings(`
    .btn-primary { background: #0b7061; padding: 0.5rem 1rem; border-radius: 6px; }
    .modal-actions {
      .btn-danger { background: #ef4444; }
      .btn-secondary {
        &:hover { background: #f8fafc; }
        &:disabled { opacity: 0.6; }
      }
    }
    .btn { &.btn-primary { color: white; } }
    @media (max-width: 600px) { .page-actions .btn-secondary { font-size: 12px; } }
  `).filter((h) => h.rule === 'local-button');
  assert.deepEqual(
    hits.map((h) => h.anchor),
    [
      '.btn-primary',
      '.btn-primary',
      '.btn-primary',
      '.modal-actions .btn-danger',
      '.modal-actions .btn-secondary:hover',
      '.modal-actions .btn-secondary:disabled',
      '.btn.btn-primary',
      '.page-actions .btn-secondary',
    ],
  );
});

test('a page may place a shared button and own a variant of it', () => {
  const hits = scssFindings(`
    .form-actions .btn-primary { width: 100%; margin-left: auto; flex-shrink: 0; white-space: nowrap; }
    .btn-primary.small { padding: 4px 10px; font-size: 0.8rem; }
    .btn-secondary[aria-pressed='true'] { background: #fff4d6; }
    .btn-icon.btn-danger { color: #b91c1c; }
    .btn-primary-sm { background: var(--primary); }
    .btn-danger-solid { background: #b91c1c; }
    .btn-primary .spinner { border-color: #fff; }
    .btn-primary::after { content: ''; background: #fff; }
  `);
  assert.deepEqual(hits, []);
});

test('the sheet that defines the buttons is read without the button rule', () => {
  const sheet = '.btn-primary { background: var(--primary); color: #fff; }';
  assert.equal(scssFindings(sheet).length, 2);
  assert.deepEqual(scssFindings(sheet, { buttons: false }), []);
});

test('selector kinds: the base, a state of the base, a variant, something else', () => {
  const kinds = Object.fromEntries(
    [
      '.btn-primary',
      '.modal-actions > .btn-primary:hover:not(:disabled)',
      'button.btn-secondary[disabled]',
      '.btn.btn-danger',
      '.btn-primary.btn-sm',
      '.btn-primary:not(.danger)',
      '.btn-secondary[aria-pressed="true"]',
      '.btn-primary .material-symbols-outlined',
      '.btn-primary-sm',
      '.btn-primary::before',
    ].map((sel) => [sel, classifyButtonSelector(sel)]),
  );
  assert.deepEqual(kinds, {
    '.btn-primary': 'exact',
    '.modal-actions > .btn-primary:hover:not(:disabled)': 'exact',
    'button.btn-secondary[disabled]': 'exact',
    '.btn.btn-danger': 'exact',
    '.btn-primary.btn-sm': 'modified',
    '.btn-primary:not(.danger)': 'exact',
    '.btn-secondary[aria-pressed="true"]': 'modified',
    '.btn-primary .material-symbols-outlined': 'other',
    '.btn-primary-sm': 'other',
    '.btn-primary::before': 'other',
  });
});

test('a template: the bare .btn base on a shared button, and inline colours', () => {
  const hits = templateFindings(`
    <button class="btn btn-primary">Créer</button>
    <button class="btn-secondary btn-sm">Annuler</button>
    <button class="btn btn-outline">Autre</button>
    <span class="material-symbols-outlined" style="color: #7c3aed">vaccines</span>
    <span style="color: #64748b">x</span>
    <div style="background: #f1f5f9; color: var(--text-secondary)">ok</div>
  `);
  assert.deepEqual(rules(hits), [
    'btn-base-class:btn-primary',
    'legacy-colour:#7c3aed',
    'low-contrast-text:#64748b',
  ]);
});

test('a component source: a legacy hex in inline styles or a palette', () => {
  assert.deepEqual(rules(sourceFindings("const c = { CONFIRMED: '#6366f1', DONE: '#059669' };")), [
    'legacy-colour:#6366f1',
  ]);
});

/* ── The tokens themselves (D2793) ──────────────────────────────────────── */

const tokens = rootTokens(readFileSync(join(src, 'styles.scss'), 'utf8'));
const PAGE = '#f1f5f9';
const WHITE = '#ffffff';

test('the text tokens reach 4.5:1 on white and on the page background', () => {
  assert.equal(tokens['--surface-page'], PAGE);
  for (const name of ['--text-primary', '--text-secondary', '--text-muted']) {
    const value = tokens[name];
    assert.match(value ?? '', /^#[0-9a-f]{6}$/i, `${name} is a hex in :root`);
    assert.ok(contrast(value, WHITE) >= 4.5, `${name} ${value} on white`);
    assert.ok(contrast(value, PAGE) >= 4.5, `${name} ${value} on the page background`);
  }
});

test('white text reaches 4.5:1 on the brand and danger fills; the accent on white', () => {
  for (const name of ['--primary', '--primary-dark', '--danger', '--danger-dark']) {
    assert.ok(contrast(WHITE, tokens[name]) >= 4.5, `white on ${name} ${tokens[name]}`);
  }
  assert.ok(contrast(tokens['--accent-violet'], WHITE) >= 4.5);
  // the deep brand on its tint (chips, selected rows)
  assert.ok(contrast(tokens['--primary-deep'], tokens['--primary-tint']) >= 4.5);
});

/* ── The tree ───────────────────────────────────────────────────────────── */

test('the shared sheet defines each of the three buttons exactly once', () => {
  const shared = readFileSync(join(src, 'app', 'shared', 'styles', '_page-common.scss'), 'utf8');
  for (const name of ['btn-primary', 'btn-secondary', 'btn-danger']) {
    // the rule that gives the button its colours (the grouped shape rule aside)
    const own = shared.match(new RegExp(`^\\.${name} \\{\\r?\\n  background:`, 'gm')) ?? [];
    assert.equal(own.length, 1, `.${name} { background… } in _page-common.scss`);
  }
});

test('the portal carries no site beyond its pins, and every pin says why', () => {
  const baseline = loadBaseline(gate.baselinePath);
  const { fresh, stale } = compare(designTokenFindings(), baseline);
  assert.deepEqual(
    fresh.map((f) => `${f.file}:${f.line} ${f.rule}`),
    [],
  );
  assert.deepEqual(stale, []);
  for (const [id, pin] of Object.entries(baseline.pinned)) {
    assert.ok(pin.why && !/^TODO/i.test(pin.why), `${id} has a reason`);
  }
});

test('app.config.ts installs the design conventions (card labels, hidden icons, tooltips)', () => {
  const config = readFileSync(join(src, 'app', 'app.config.ts'), 'utf8');
  assert.match(
    config,
    /provideAppInitializer\(\(\) => installDesignConventions\(inject\(DOCUMENT\)\)\)/,
  );
});
