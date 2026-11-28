// QA r81 lane Lows guard (D4162, D4163, D4167, D4159): the laboratory lane's screens
// share one header, one close control, one tab layout, and fit their lists.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const app = (...p) => readFileSync(join(root, 'src', 'app', ...p), 'utf8');
const json = (l) =>
  JSON.parse(readFileSync(join(root, 'src', 'assets', 'i18n', `${l}.json`), 'utf8'));

const HEADERED = [
  ['lab', 'lab-inventory', 'lab-inventory'],
  ['lab', 'lab-instruments', 'lab-instruments'],
  ['lab', 'lab-outbox', 'lab-outbox'],
  ['lab', 'lab-staff-list', 'lab-staff-list'],
  ['lab', 'lab-ops-dashboard', 'lab-ops-dashboard'],
  ['micro', 'microbiology'],
  ['transfusion', 'transfusion'],
];

test('D4162 (a): every lane page carries the Espace diagnostic header', () => {
  for (const parts of HEADERED) {
    const file = parts[parts.length - 1];
    const dir = parts.slice(0, -1);
    const html = app(...dir, `${file}.html`);
    assert.match(html, /class="diagnostics-eyebrow"/, `${file}.html: eyebrow band`);
    assert.doesNotMatch(html, /header-icon/, `${file}.html: the icon-only header is gone`);
    assert.match(app(...dir, `${file}.scss`), /lane-header\.header/, `${file}.scss`);
  }
});

test('D4162 (b): imaging and blood-bank dialogs close with the icon button and a name', () => {
  for (const file of [
    ['imaging', 'imaging.html'],
    ['transfusion', 'transfusion.html'],
  ]) {
    const html = app(...file);
    assert.doesNotMatch(html, /&times;/, `${file.join('/')}: no typed multiplication sign`);
    for (const m of html.matchAll(/<button[^>]*class="modal-close"[^>]*>/g)) {
      assert.match(m[0], /aria-label\]="[^"]*COMMON\.CLOSE/, `${file.join('/')}: ${m[0]}`);
    }
  }
});

test('D4162 (c, d): the open/all switch is styled and the unit filter hides on the other tabs', () => {
  assert.match(
    app('transfusion', 'o-positive-releases', 'o-positive-releases.component.scss'),
    /\.tab-btn\s*\{/,
  );
  assert.match(app('transfusion', 'transfusion.html'), /@else if \(tab\(\) === 'units'\)/);
});

test('D4167: incompatible releases have their own tab, apart from O positive', () => {
  const html = app('transfusion', 'transfusion.html');
  assert.match(html, /tab\(\) === 'opositive'\) \{\s*<app-o-positive-releases \/>\s*\}/);
  assert.match(html, /tab\(\) === 'incompatible'\) \{[\s\S]*?<app-incompatible-releases \/>/);
  for (const l of ['en', 'fr', 'es']) {
    const t = json(l).TRANSFUSION;
    assert.ok(t.INCOMPAT.TAB && t.INCOMPAT.CHOSEN_LEAST_ONE, `${l}: tab and singular keys`);
    assert.doesNotMatch(t.INCOMPAT.CHOSEN_LEAST, /\(s\)/, `${l}: no (s) plural`);
  }
  const fr = json('fr').TRANSFUSION;
  assert.doesNotMatch(fr.VERIFIER, /identifiant/);
  assert.match(fr.INCOMPAT.DECLARED_BY, /^délivrance déclarée par/);
  assert.match(
    app('transfusion', 'incompatible-releases', 'incompatible-releases.component.html'),
    /candidatesConsidered === 1/,
  );
});

test('D4159: the verification method default is translated, not an English literal', () => {
  const ts = app('transfusion', 'transfusion.ts');
  assert.doesNotMatch(ts, /'Wristband scan'/);
  assert.match(ts, /TRANSFUSION\.VERIFICATION_METHOD_DEFAULT/);
  assert.equal(json('fr').TRANSFUSION.VERIFICATION_METHOD_DEFAULT, 'Scan du bracelet');
});

test('D4163: the wide lane lists read as cards on a tablet instead of scrolling sideways', () => {
  assert.match(
    readFileSync(join(root, 'src', 'styles.scss'), 'utf8'),
    /min-width: 600px\) and \(max-width: 1023px\)[\s\S]*?data-cards-tablet/,
  );
  for (const file of [
    ['lab', 'lab-outbox', 'lab-outbox.html'],
    ['lab', 'lab-release-worklist', 'lab-release-worklist.html'],
    ['lab', 'lab-staff-list', 'lab-staff-list.html'],
  ]) {
    assert.match(app(...file), /<table[^>]*data-cards-tablet/, file.join('/'));
  }
});

test('D4162 (e): the validation queue never breaks a word inside a header or chip', () => {
  const scss = app('lab', 'lab-release-worklist', 'lab-release-worklist.scss');
  const table = scss.slice(scss.indexOf('/* D3909'));
  assert.doesNotMatch(table.slice(0, table.indexOf('th:last-child')), /overflow-wrap: anywhere/);
  assert.doesNotMatch(scss, /break-all/);
});
