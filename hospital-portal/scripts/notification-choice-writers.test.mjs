import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { walk } from './lib/walk.mjs';
import ts from 'typescript';
import vm from 'node:vm';

test('every active preference editor sends only edited cells, not its stale full matrix', () => {
  const app = fileURLToPath(new URL('../src/app/', import.meta.url));
  const writers = [];
  for (const path of walk(app, ['.ts'])) {
    if (path.endsWith('.spec.ts') || /[/\\](services|testing)[/\\]/.test(path)) continue;
    const source = readFileSync(path, 'utf8');
    if (
      !/\.update(?:Notification)?Preferences\(/.test(source) &&
      !source.includes('/channel-preferences`')
    )
      continue;
    writers.push(path.replaceAll('\\', '/').slice(app.replaceAll('\\', '/').length));
    assert.match(source, /changedChannelChoices\(/, path);
  }
  assert.equal(writers.length, 2, JSON.stringify(writers));
});

test('the shared writer contract omits unchanged cells and server-authored provenance', () => {
  const source = readFileSync(
    new URL('../src/app/shared/notification-channel-choices.ts', import.meta.url),
    'utf8',
  );
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports = {};
  vm.runInNewContext(compiled, { exports });
  const saved = [{ notificationType: 'BILLING', channel: 'EMAIL', enabled: true }];
  const result = exports.changedChannelChoices(
    [
      saved[0],
      {
        notificationType: 'LAB_RESULT',
        channel: 'SMS',
        enabled: false,
        recordedByUserId: 'server-only',
      },
    ],
    saved,
  );
  assert.deepEqual(JSON.parse(JSON.stringify(result)), [
    { notificationType: 'LAB_RESULT', channel: 'SMS', enabled: false },
  ]);
});
