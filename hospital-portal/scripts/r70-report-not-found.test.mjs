import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

test('D3358: shared report-not-found text never mislabels an order identifier as a report identifier', () => {
  for (const suffix of ['', '_en', '_fr', '_es']) {
    const bundle = readFileSync(
      new URL(
        `../../hospital-core/src/main/resources/i18n/messages-lab${suffix}.properties`,
        import.meta.url,
      ),
      'utf8',
    );
    const message = bundle.match(/^imaging\.report\.notFound=(.*)$/m)?.[1];
    assert.ok(message);
    assert.ok(!message.includes('{0}'), `an ambiguous identifier is echoed in ${suffix}`);
  }
});
