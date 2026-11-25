import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { PORTAL_DIR } from './template-gate.mjs';

test('admin screenshot manifest covers every documented PNG with its actual hash', () => {
  const dir = resolve(PORTAL_DIR, '../docs/ui/c-ui-admin');
  const manifest = JSON.parse(readFileSync(resolve(dir, 'screenshots.json'), 'utf8'));
  const readme = readFileSync(resolve(dir, 'README.md'), 'utf8');
  const documented = [
    ...new Set([...readme.matchAll(/\]\(([^)]+\.png)\)/g)].map((m) => m[1])),
  ].sort();
  const files = readdirSync(dir)
    .filter((file) => file.endsWith('.png'))
    .sort();
  assert.deepEqual(manifest.images.map((image) => image.file).sort(), documented);
  assert.deepEqual(files, documented);
  for (const image of manifest.images)
    assert.equal(
      image.sha256,
      createHash('sha256')
        .update(readFileSync(resolve(dir, image.file)))
        .digest('hex'),
      image.file,
    );
});
