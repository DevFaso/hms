// QA D1184 guard (round 22): a stored file the server no longer has answers
// 404 with a localized reason ("Le fichier stocké n'est plus disponible."),
// and the patient read only "Échec du téléchargement": a `responseType: 'blob'`
// request receives the JSON refusal as a Blob, and the handler showed its
// generic toast.
//
// Every component that downloads a STORED file (a patient document, a chat
// attachment, a patient photo: the service methods below) and shows a toast in
// its error handler must read the server's reason first with
// `blobRefusalReason(err)` (src/app/shared/blob-refusal.ts). A handler with no
// toast (a missing photo renders initials, a chat attachment stays a link) is
// not a refusal surface.
//
// Generated exports (PDF/CSV) are a sibling, not this class: their refusals
// are scope or permission errors, listed under "Remaining" in the round-22 PR.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = join(here, '..', '..', 'src', 'app');

/** Service methods that stream a stored file as a Blob. */
export const STORED_FILE_METHODS = [
  'downloadDocumentBlob',
  'downloadBlob',
  'getAttachmentBlob',
  'getPhotoBlob',
];

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (name.endsWith('.ts') && !name.endsWith('.spec.ts')) out.push(full);
  }
  return out;
}

/**
 * The error handlers of `.<method>(…).subscribe({ … error: … })` calls that show
 * a toast without `blobRefusalReason`. A local helper named like a service
 * method (`private downloadBlob(blob, name)`) is not a call through a service.
 */
export function blindStoredFileHandlers(source) {
  const out = [];
  const call = new RegExp(
    `\\.(${STORED_FILE_METHODS.join('|')})\\(([^)]*)\\)\\s*\\.subscribe\\(\\{`,
    'g',
  );
  for (const m of source.matchAll(call)) {
    const start = m.index + m[0].length;
    const errorAt = source.indexOf('error:', start);
    if (errorAt < 0) continue;
    const handler = source.slice(errorAt, errorAt + 600);
    const end = handler.search(/\n\s{4,8}\}\);/);
    const body = end > 0 ? handler.slice(0, end) : handler;
    if (/toast\./.test(body) && !/blobRefusalReason\(/.test(body)) {
      out.push({ method: m[1], line: source.slice(0, m.index).split('\n').length });
    }
  }
  return out;
}

test('QA D1184: a stored-file download shows the server reason of a refusal', () => {
  const problems = [];
  let calls = 0;
  for (const file of walk(appDir)) {
    const source = readFileSync(file, 'utf8');
    if (STORED_FILE_METHODS.some((m) => source.includes(`.${m}(`))) calls++;
    for (const h of blindStoredFileHandlers(source)) {
      problems.push(
        `${relative(appDir, file)}:${h.line}: .${h.method}(…) shows a generic toast on error ` +
          "(show `await blobRefusalReason(err) ?? this.translate.instant('…')`)",
      );
    }
  }
  assert.ok(calls >= 4, `the scan found the stored-file downloads (${calls})`);
  assert.deepEqual(problems, [], problems.join('\n'));
});

test('QA D1184 guard: the scan sees the round-22 shape and accepts the fix', () => {
  const before = `    this.portalService.downloadDocumentBlob(doc.id).subscribe({
      next: (blob) => {
        this.downloading.set(null);
      },
      error: () => {
        this.downloading.set(null);
        this.toast.error(this.translate.instant('PORTAL.DOCUMENTS.DOWNLOAD_FAILED'));
      },
    });`;
  assert.deepEqual(
    blindStoredFileHandlers(before).map((h) => h.method),
    ['downloadDocumentBlob'],
  );
  const after = before.replace(
    "this.toast.error(this.translate.instant('PORTAL.DOCUMENTS.DOWNLOAD_FAILED'));",
    "void blobRefusalReason(err).then((r) => this.toast.error(r ?? this.translate.instant('X')));",
  );
  assert.deepEqual(blindStoredFileHandlers(after), []);
  const silent = `    this.extrasService.getPhotoBlob(id).subscribe({
      next: (blob) => this.show(blob),
      error: () => {
        this.photoUrl.set(null);
      },
    });`;
  assert.deepEqual(blindStoredFileHandlers(silent), []);
});
