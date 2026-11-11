// QA D968 guard: a laboratory overlay is a real dialog.
//
// The /lab-results detail panel was a keyboard trap the wrong way round:
// focus stayed on the page behind it, Escape did nothing (the overlay listened
// for it, but focus never entered the overlay, and the inner card swallowed it
// with stopPropagation), and Tab walked to "Signer" behind the panel. Every
// overlay in the lab screens was built the same way, so this reads every
// template under src/app/lab and requires, for each overlay container
// (detail-overlay, modal-backdrop, form-overlay), one dialog element inside it
// that:
//   - says it is one: role="dialog" and aria-modal="true";
//   - has a name: aria-label or aria-labelledby;
//   - takes focus in, keeps Tab inside and gives focus back on close:
//     cdkTrapFocus with [cdkTrapFocusAutoCapture]="true";
//   - closes on Escape: a (keydown.escape) handler that is not only
//     $event.stopPropagation().
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const labDir = join(appDir, 'lab');

const OVERLAY = /^(detail-overlay|modal-backdrop|form-overlay)$/;

/** Every opening tag in `html`, with its offset and attribute text. */
function openingTags(html) {
  const tags = [];
  const re = /<([a-zA-Z][\w-]*)\b((?:[^>"']|"[^"]*"|'[^']*')*)>/g;
  let m;
  while ((m = re.exec(html))) tags.push({ index: m.index, name: m[1], attrs: m[2] });
  return tags;
}

function classTokens(attrs) {
  const m = /\sclass="([^"]*)"/.exec(attrs);
  return m ? m[1].split(/\s+/).filter(Boolean) : [];
}

/**
 * Round 16 siblings: the shared dialog directives give role="dialog",
 * aria-modal="true", focus in, a Tab trap and focus back on close
 * (shared/a11y/modal-dialog.directive.ts, shared/a11y/dialog-a11y.directive.ts);
 * Escape closes only when their output is bound. Such an element is a
 * complete dialog once it also has a name.
 */
const DIRECTIVE_DIALOG =
  /\sappModalDialog(\s|$)[\s\S]*?\(escaped\)="[^"]+"|\(escaped\)="[^"]+"[\s\S]*?\sappModalDialog(\s|$)|\sappDialogA11y(\s|$)[\s\S]*?\(dialogClose\)="[^"]+"|\(dialogClose\)="[^"]+"[\s\S]*?\sappDialogA11y(\s|$)/;
const DIRECTIVE_MARK = /\s(appModalDialog|appDialogA11y)(\s|$)/;

function isDialogElement(attrs) {
  return /\srole="dialog"/.test(attrs) || DIRECTIVE_MARK.test(attrs);
}

/** The problems with one dialog element's attributes, empty when it is complete. */
export function dialogProblems(attrs) {
  const problems = [];
  if (DIRECTIVE_MARK.test(attrs)) {
    if (!DIRECTIVE_DIALOG.test(attrs))
      problems.push('its Escape output bound ((escaped) / (dialogClose))');
    if (!/aria-label(ledby)?\]?=/.test(attrs)) problems.push('an aria-label or aria-labelledby');
    return problems;
  }
  if (!/\srole="dialog"/.test(attrs)) problems.push('role="dialog"');
  if (!/\saria-modal="true"/.test(attrs)) problems.push('aria-modal="true"');
  if (!/aria-label(ledby)?\]?=/.test(attrs)) problems.push('an aria-label or aria-labelledby');
  if (!/\scdkTrapFocus(\s|$|=)/.test(attrs)) problems.push('cdkTrapFocus');
  if (!/\[cdkTrapFocusAutoCapture\]="true"/.test(attrs))
    problems.push('[cdkTrapFocusAutoCapture]="true"');
  const escape = /\(keydown\.escape\)="([^"]*)"/.exec(attrs);
  if (!escape || /^\s*\$event\.stopPropagation\(\)\s*;?\s*$/.test(escape[1])) {
    problems.push('a (keydown.escape) that closes it');
  }
  return problems;
}

/** Offenders in one template: overlays with no complete dialog before the next overlay. */
export function overlayOffenders(html, file = 'template', overlay = OVERLAY) {
  const tags = openingTags(html);
  const offenders = [];
  tags.forEach((tag, i) => {
    if (!classTokens(tag.attrs).some((c) => overlay.test(c))) return;
    let dialog = null;
    for (let j = i; j < tags.length; j++) {
      if (j > i && classTokens(tags[j].attrs).some((c) => overlay.test(c))) break;
      if (isDialogElement(tags[j].attrs)) {
        dialog = tags[j];
        break;
      }
    }
    const line = html.slice(0, tag.index).split('\n').length;
    if (!dialog) {
      offenders.push(`${file}:${line} overlay has no role="dialog" element`);
      return;
    }
    const problems = dialogProblems(dialog.attrs);
    if (problems.length) offenders.push(`${file}:${line} dialog lacks ${problems.join(', ')}`);
  });
  return offenders;
}

test('QA D968: every lab overlay is a named, focus-trapping modal dialog that Escape closes', () => {
  const files = walk(labDir, ['.html']);
  assert.ok(files.length >= 10, 'the scan must see the lab templates');
  let overlays = 0;
  const offenders = [];
  for (const file of files) {
    const html = readFileSync(file, 'utf8');
    overlays += openingTags(html).filter((t) =>
      classTokens(t.attrs).some((c) => OVERLAY.test(c)),
    ).length;
    offenders.push(...overlayOffenders(html, relative(appDir, file).replaceAll('\\', '/')));
  }
  assert.ok(overlays >= 20, `expected the lab overlays, saw ${overlays}`);
  assert.deepEqual(offenders, []);
});

/**
 * Round 16 siblings: the D968 side panel (a detail-overlay or form-overlay
 * whose Escape sat on the overlay while focus stayed on the page behind) was
 * built the same way outside lab/: prescriptions, imaging, referrals,
 * encounters, consultations, transfusion, treatment plans, mortality, billing,
 * on-call, slot admin, drug interactions, advance directives. Every such
 * panel in the portal is a complete dialog too.
 */
const SIDE_PANEL = /^(detail-overlay|form-overlay)$/;

test('QA D968 (siblings): every detail or form overlay outside lab/ is a complete dialog', () => {
  const files = walk(appDir, ['.html']).filter(
    (f) => !relative(appDir, f).replaceAll('\\', '/').startsWith('lab/'),
  );
  let panels = 0;
  const offenders = [];
  for (const file of files) {
    const html = readFileSync(file, 'utf8');
    panels += openingTags(html).filter((t) =>
      classTokens(t.attrs).some((c) => SIDE_PANEL.test(c)),
    ).length;
    offenders.push(
      ...overlayOffenders(html, relative(appDir, file).replaceAll('\\', '/'), SIDE_PANEL),
    );
  }
  // 18 since round 22: the advance-directive revoke confirmation is the shared
  // <app-reason-dialog> now (QA D1180), no longer a form-overlay of its own.
  assert.ok(panels >= 18, `expected the side panels outside lab/, saw ${panels}`);
  assert.deepEqual(offenders, []);
});

test('QA D968 guard: the scan rejects the shape the defect had and accepts the fixed one', () => {
  const broken = `<div class="detail-overlay" tabindex="-1" (keydown.escape)="close()">
    <div class="detail-panel"><h2>x</h2></div></div>`;
  assert.equal(overlayOffenders(broken).length, 1);
  const swallowed = `<div class="modal-backdrop" (click)="close()">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="t" cdkTrapFocus
      [cdkTrapFocusAutoCapture]="true" (keydown.escape)="$event.stopPropagation()"></div></div>`;
  assert.match(overlayOffenders(swallowed)[0], /keydown\.escape/);
  const fixed = `<div class="modal-backdrop" (click)="close()">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="t" cdkTrapFocus
      [cdkTrapFocusAutoCapture]="true" (keydown.escape)="close(); $event.stopPropagation()"></div></div>`;
  assert.deepEqual(overlayOffenders(fixed), []);
});

test('round 16: a shared dialog directive counts once its Escape output is bound and it is named', () => {
  const unbound = `<div class="detail-overlay" (keydown.escape)="close()">
    <div class="detail-panel" appModalDialog aria-labelledby="t"><h2 id="t">x</h2></div></div>`;
  assert.match(overlayOffenders(unbound)[0], /Escape output/);
  const unnamed = `<div class="modal-backdrop"><div class="modal" appDialogA11y (dialogClose)="close()"></div></div>`;
  assert.match(overlayOffenders(unnamed)[0], /aria-label/);
  const fixed = `<div class="detail-overlay" (keydown.escape)="close()">
    <div class="detail-panel" appModalDialog aria-labelledby="t" (escaped)="close()"><h2 id="t">x</h2></div></div>`;
  assert.deepEqual(overlayOffenders(fixed), []);
});
