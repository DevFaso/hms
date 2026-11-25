import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { PORTAL_DIR } from './template-gate.mjs';

test('clinical screenshot manifest includes every committed PNG and its current hash', () => {
  const dir = resolve(PORTAL_DIR, '../docs/ui/c-ui-clinical');
  const manifest = JSON.parse(readFileSync(resolve(dir, 'manifest.json'), 'utf8'));
  const files = readdirSync(dir)
    .filter((file) => file.endsWith('.png'))
    .sort();
  assert.deepEqual(manifest.screenshots.map((image) => image.file).sort(), files);
  for (const image of manifest.screenshots)
    assert.equal(
      image.sha256,
      createHash('sha256')
        .update(readFileSync(resolve(dir, image.file)))
        .digest('hex'),
      image.file,
    );
});
