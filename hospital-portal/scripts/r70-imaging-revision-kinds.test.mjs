import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

test('the report revision picker offers every revision kind accepted by the server', () => {
  const backend = readFileSync(
    new URL(
      '../../hospital-core/src/main/java/com/example/hms/service/impl/ImagingReportServiceImpl.java',
      import.meta.url,
    ),
    'utf8',
  );
  const frontend = readFileSync(new URL('../src/app/imaging/imaging.ts', import.meta.url), 'utf8');
  const serverKinds = backend.match(/REVISION_STATUSES\s*=\s*Set\.of\(([\s\S]*?)\);/)?.[1];
  const pickerKinds = frontend.match(/revisionStatuses\s*=\s*\[([^\]]*)\]/)?.[1];
  assert.ok(serverKinds);
  assert.ok(pickerKinds);
  assert.match(frontend, /return this\.revisionStatuses\s*;/);
  const expected = [...serverKinds.matchAll(/ImagingReportStatus\.(\w+)/g)].map((m) => m[1]);
  const actual = [...pickerKinds.matchAll(/'([^']+)'/g)].map((m) => m[1]);
  assert.ok(expected.length >= 3);
  assert.deepEqual(actual.sort(), expected.sort());
});
