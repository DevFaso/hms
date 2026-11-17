// QA D1204 / D1176 guard (rounds 21–22): the lab "Nouveau résultat" picker
// offered orders the server then refused.
//
// Round 21 hid orders that already carry a result (D1176). Round 22 found the
// orders whose specimen was never taken or was rejected (LAB-000029,
// LAB-000032) still offered and refused only after "Créer". The picker's rule
// must be the server's rule, so this reads both:
//
//  - hospital-core LabResultServiceImpl.requireSpecimenTaken: the order
//    statuses a hand-entered result is refused for (no specimen);
//  - hospital-portal lab-results.ts ORDER_STATUSES_AWAITING_SPECIMEN: the
//    statuses the picker leaves out.
//
// A status the server starts refusing and the picker still offers fails here.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SERVER = join(
  here,
  '..',
  '..',
  '..',
  'hospital-core',
  'src',
  'main',
  'java',
  'com',
  'example',
  'hms',
  'service',
  'LabResultServiceImpl.java',
);
const PORTAL = join(here, '..', '..', 'src', 'app', 'lab', 'lab-results', 'lab-results.ts');

/** The statuses `requireSpecimenTaken` refuses (`committedStatus != LabOrderStatus.X && …` returns early). */
export function serverNoSpecimenStatuses(java) {
  const start = java.indexOf('void requireSpecimenTaken(');
  assert.ok(start >= 0, 'LabResultServiceImpl.requireSpecimenTaken must exist');
  const guard = java.slice(start, java.indexOf('return;', start));
  return [...guard.matchAll(/!=\s*LabOrderStatus\.(\w+)/g)].map((m) => m[1]).sort();
}

/** The statuses of the portal set named `name`. */
export function portalSet(ts, name) {
  const m = new RegExp(`${name}[^=]*=\\s*new Set\\(\\[([^\\]]*)\\]`).exec(ts);
  assert.ok(m, `${name} must be declared in lab-results.ts`);
  return [...m[1].matchAll(/'(\w+)'/g)].map((s) => s[1]).sort();
}

test('QA D1204: the result picker leaves out every status the server refuses for want of a specimen', () => {
  const server = serverNoSpecimenStatuses(readFileSync(SERVER, 'utf8'));
  assert.ok(server.length >= 2, `the server rule must be read, saw ${server.join(',')}`);
  const portal = portalSet(readFileSync(PORTAL, 'utf8'), 'ORDER_STATUSES_AWAITING_SPECIMEN');
  assert.deepEqual(
    portal,
    server,
    'the picker offers an order the server refuses (or hides one it accepts)',
  );
  assert.match(
    readFileSync(PORTAL, 'utf8'),
    /!ORDER_STATUSES_AWAITING_SPECIMEN\.has\(o\.status\)/,
    'pickableOrders must apply the set',
  );
});

test('QA D1204 guard: the server rule is read as written', () => {
  const java = `
    private static void requireSpecimenTaken(LabOrderStatus committedStatus, LabOrder labOrder) {
        if (committedStatus != LabOrderStatus.ORDERED && committedStatus != LabOrderStatus.PENDING) {
            return;
        }
        throw new BusinessException("x");
    }`;
  assert.deepEqual(serverNoSpecimenStatuses(java), ['ORDERED', 'PENDING']);
  assert.deepEqual(
    portalSet(
      "export const X: ReadonlySet<string> = new Set([\n  'PENDING',\n  'ORDERED',\n]);",
      'X',
    ),
    ['ORDERED', 'PENDING'],
  );
});
