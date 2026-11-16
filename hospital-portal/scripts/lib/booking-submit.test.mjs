import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { bookingSubmitViolations } from './booking-submit-scan.mjs';

const fixture = (body, service = 'AppointmentService', call = 'create') => `
class Example {
  private renamed = inject(${service});
  private bookingRequestId = createBookingRequestId();
  submit() { ${body} this.renamed.${call}(payload); }
}`;
const guarded = `if (this.pending()) return;
this.pending.set(true);
const payload = { bookingRequestId: this.bookingRequestId };`;

test('booking guard finds missing submit guards through renamed service fields', () => {
  assert.equal(bookingSubmitViolations(fixture('')).violations.length, 1);
  assert.equal(
    bookingSubmitViolations(fixture('', 'PatientPortalService', 'bookAppointment')).violations
      .length,
    1,
  );
  assert.equal(bookingSubmitViolations(fixture(guarded)).violations.length, 0);
});

test('booking guard rejects a fresh key on every retry and a guard after the request', () => {
  assert.equal(
    bookingSubmitViolations(fixture(`${guarded} crypto.randomUUID();`)).violations.length,
    1,
  );
  assert.equal(
    bookingSubmitViolations(fixture('') + 'if (this.pending()) return;').violations.length,
    1,
  );
  assert.equal(
    bookingSubmitViolations(fixture(`${guarded} createBookingRequestId();`)).violations.length,
    1,
  );
  assert.equal(
    bookingSubmitViolations(
      fixture(guarded).replace('= createBookingRequestId()', '= crypto.randomUUID()'),
    ).violations.length,
    1,
  );
});

test('every portal booking caller guards submit and preserves request identity', () => {
  function files(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
      const path = join(directory, entry.name);
      return entry.isDirectory() ? files(path) : [path];
    });
  }
  let calls = 0;
  const violations = [];
  for (const file of files(fileURLToPath(new URL('../../src/app', import.meta.url)))) {
    if (!file.endsWith('.ts') || file.endsWith('.spec.ts')) continue;
    const result = bookingSubmitViolations(readFileSync(file, 'utf8'), file);
    calls += result.calls;
    violations.push(...result.violations);
  }
  assert.equal(calls, 2, 'update the booking inventory when adding another booking caller');
  assert.deepEqual(violations, []);
});
