// QA D1212 guard (round 23): the patient portal shows a failure without the
// server's reason.
//
// A proxy opening a grantor's data got 404 "Patient introuvable avec ID : …"
// and read only "Impossible de charger cette section…": the viewer's error
// handler was `error: () => this.tabError.set(true)`. Every failure the
// patient portal shows (an error flag, an error toast) takes the error and
// shows the server's reason first: `refusalReason(err)` (a 4xx; a 5xx body
// never reaches the patient), `blobRefusalReason(err)` for a file download,
// or a pager's `failureReason`. The generic key is the fallback.
//
// A handler that shows nothing (it only ends a spinner) is not read here.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';
import { balanced, endOfLiteralOrComment } from './js-scan.mjs';

const portalDir = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'src',
  'app',
  'patient-portal',
);

const HANDLER = /\berror:\s*(?:\([^)]*\)|\w+)\s*=>\s*/g;
/** The handler shows a failure: an error / failed flag raised, or an error toast. */
const SHOWS_FAILURE = /\.\w*(?:[Ff]ail\w*|[Ee]rror)\.set\(\s*true\s*\)|\btoast\.error\(/;
/** The handler hands the server's reason on. */
const PASSES_REASON =
  /\b(?:refusalReason|blobRefusalReason|labelledRefusalReason)\(|[Ff]ailureReason\b|\.\w*(?:Failure|Reason|Refusal)\w*\(\s*err\b/;

/** An arrow's expression body: up to the `,` / `)` / `}` that ends it at depth 0. */
function expressionBody(source, start) {
  let depth = 0;
  for (let i = start; i < source.length; i++) {
    const skipped = endOfLiteralOrComment(source, i);
    if (skipped >= 0) {
      i = skipped;
      continue;
    }
    const ch = source[i];
    if ('([{'.includes(ch)) depth++;
    else if (')]}'.includes(ch)) {
      if (depth === 0) return source.slice(start, i);
      depth--;
    } else if (ch === ',' && depth === 0) return source.slice(start, i);
  }
  return source.slice(start);
}

/** Every error handler of a source that shows a failure without the server's reason: its line. */
export function reasonlessFailures(source) {
  const out = [];
  for (const m of source.matchAll(HANDLER)) {
    const start = m.index + m[0].length;
    const body =
      source[start] === '{' ? balanced(source, start, '{', '}') : expressionBody(source, start);
    if (SHOWS_FAILURE.test(body) && !PASSES_REASON.test(body)) {
      out.push(source.slice(0, m.index).split('\n').length);
    }
  }
  return out;
}

test("QA D1212: every failure the patient portal shows carries the server's reason", () => {
  const problems = [];
  let files = 0;
  for (const file of walk(portalDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    files++;
    const rel = relative(portalDir, file).replaceAll('\\', '/');
    for (const line of reasonlessFailures(readFileSync(file, 'utf8'))) {
      problems.push(
        `patient-portal/${rel}:${line}: shows a failure without the server's reason ` +
          '(take the error and show refusalReason(err) ?? the generic key)',
      );
    }
  }
  assert.ok(files > 20, `every patient-portal source must be read, saw ${files}`);
  assert.deepEqual(problems, [], problems.join('\n'));
});

test('QA D1212 guard: the scan sees the round-23 shapes and passes the fix', () => {
  const viewer = `      error: () => {\n        this.tabLoading.set(false);\n        this.tabError.set(true);\n      },`;
  assert.deepEqual(reasonlessFailures(viewer), [1]);
  const oneLine = `      error: () => this.downloadError.set(true),\n`;
  assert.deepEqual(reasonlessFailures(oneLine), [1]);
  const toast = `      error: () => {\n        this.toast.error(this.translate.instant('PORTAL.FAMILY.LOAD_FAILED'));\n      },`;
  assert.deepEqual(reasonlessFailures(toast), [1]);
  const fixed = `      error: (err: unknown) => {\n        this.failureReason.set(refusalReason(err));\n        this.tabError.set(true);\n      },`;
  assert.deepEqual(reasonlessFailures(fixed), []);
  const fixedToast = `      error: (err: unknown) => {\n        this.toast.error(refusalReason(err) ?? this.translate.instant('X'));\n      },`;
  assert.deepEqual(reasonlessFailures(fixedToast), []);
  // An expression body over several lines, and a helper handed the error, are read whole.
  const wrapped = `      error: (err: unknown) =>
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('X'),
        ),
    });`;
  assert.deepEqual(reasonlessFailures(wrapped), []);
  const helper = `      error: (err: unknown) => {
        this.toast.error(this.grantFailureMessage(err));
      },`;
  assert.deepEqual(reasonlessFailures(helper), []);
  // A handler that shows nothing is not this rule's concern.
  assert.deepEqual(reasonlessFailures(`      error: () => this.loading.set(false),\n`), []);
});
