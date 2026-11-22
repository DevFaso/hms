import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { walk } from './lib/walk.mjs';

const reportIdMessageWithOrder =
  /new ResourceNotFoundException\(\s*(?:MSG_REPORT_NOT_FOUND|"imaging\.report\.notFound")\s*,\s*(?:imagingOrderId|orderId)\b/;

test('D3358: an order lookup never describes its identifier as a missing report id', () => {
  const sourceRoot = fileURLToPath(new URL('../../hospital-core/src/main/java', import.meta.url));
  const sources = walk(sourceRoot, ['.java']);
  assert.ok(sources.length > 100);
  for (const file of sources) {
    assert.doesNotMatch(readFileSync(file, 'utf8'), reportIdMessageWithOrder, file);
  }
  assert.match(
    'new ResourceNotFoundException(MSG_REPORT_NOT_FOUND, imagingOrderId)',
    reportIdMessageWithOrder,
  );
  assert.match(
    'new ResourceNotFoundException("imaging.report.notFound", orderId)',
    reportIdMessageWithOrder,
  );
  for (const suffix of ['', '_en', '_fr', '_es']) {
    const bundle = readFileSync(
      new URL(
        `../../hospital-core/src/main/resources/i18n/messages-lab${suffix}.properties`,
        import.meta.url,
      ),
      'utf8',
    );
    const message = bundle.match(/^imaging\.report\.forOrder\.notFound=(.*)$/m)?.[1];
    assert.ok(message);
    assert.equal((message.match(/\{0\}/g) ?? []).length, 1, suffix);
  }
});
