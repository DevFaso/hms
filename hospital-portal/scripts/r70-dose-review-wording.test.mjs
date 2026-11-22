import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';

// The review can be required even after a successful WHO weight-band evaluation.
// Cover both explanations and all shipping locales, including the server fallback.
test('paediatric review explanations do not claim that every dose was unevaluable (D3307)', () => {
  const misleading =
    /could not judge|cannot judge|ne peut pas juger|no pudo evaluar|no puede evaluar/i;
  for (const locale of ['en', 'fr', 'es']) {
    const portal = JSON.parse(
      readFileSync(new URL(`../src/assets/i18n/${locale}.json`, import.meta.url)),
    );
    assert.doesNotMatch(portal.PRESCRIPTIONS.DOSE_REVIEW.HINT, misleading, locale);
  }
  for (const suffix of ['', '_en', '_fr', '_es']) {
    const bundle = readFileSync(
      new URL(
        `../../hospital-core/src/main/resources/i18n/messages-clinical${suffix}.properties`,
        import.meta.url,
      ),
      'utf8',
    );
    const explanation = bundle.match(/^prescription\.pediatricReview\.requiredToSign=(.*)$/m)?.[1];
    assert.ok(explanation);
    assert.doesNotMatch(explanation, misleading, suffix);
    assert.ok(!explanation.includes('?'), `corrupted translation: ${suffix}`);
  }
});
