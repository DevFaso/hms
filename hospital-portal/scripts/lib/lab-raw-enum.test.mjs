// QA D963 guard: the lab screens show no wire token.
//
// The order form offered ROUTINE / URGENT / STAT / ASAP in every language and
// the order detail read "CANAL DE COMMANDE PORTAL". Both sites were pinned in
// scripts/i18n-raw-enums-baseline.json, which is how a raw render stays green:
// the global gate accepts any pinned site. For the laboratory screens a pin is
// now allowed only for a field a person TYPES (a reason, a category, an
// expected answer) or a protocol code shown as sent (an HL7 message type), each
// named here with why. A new lab enum render cannot be pinned away; it has to
// go through | enumLabel.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const scripts = join(dirname(fileURLToPath(import.meta.url)), '..');

/** Lab sites that may render raw, and why. Shrink it; never grow it with an enum. */
const LAB_FREE_TEXT = {
  'src/app/lab/lab-approval-queue/lab-approval-queue.html::def.category':
    'LabTestDefinition.category is a label typed by the lab (QA D836)',
  'src/app/lab/lab-approval-queue/lab-approval-queue.html::def.rejectionReason':
    'a reviewer typed it',
  'src/app/lab/lab-inventory/lab-inventory.html::item.category':
    'LabInventoryItem.category is typed by the lab',
  'src/app/lab/lab-outbox/lab-outbox.html::d.message.messageType':
    'an HL7 message type (ORU^R01), shown as sent',
  'src/app/lab/lab-outbox/lab-outbox.html::row.messageType': 'an HL7 message type, shown as sent',
  'src/app/lab/lab-outbox/lab-outbox.html::sibling.messageType':
    'an HL7 message type, shown as sent',
  'src/app/lab/lab-release-worklist/lab-release-worklist.html::row.observationResultStatus':
    'an HL7 table-0085 code the screen does not name, shown as sent',
  'src/app/lab/lab-results/lab-results.html::r.expectedResult':
    'the expected answer typed on the test definition (QA D834)',
  'src/app/lab/lab-test-config/lab-test-config.html::def.category':
    'LabTestDefinition.category is a label typed by the lab',
  'src/app/lab/lab-test-config/lab-test-config.html::def.expectedResult':
    'the expected answer typed on the test definition',
  'src/app/lab/lab.html::o.cancellationReason': 'a person typed the cancellation or void reason',
  'src/app/lab/lab.html::rs.rejectionReason':
    'the lab typed why it rejected the specimen (QA D962)',
  'src/app/lab/lab.html::s.rejectionReason': 'the lab typed why it rejected the specimen (QA D962)',
};

test('QA D963: a lab template pins no raw render except a typed or protocol field', () => {
  const baseline = JSON.parse(readFileSync(join(scripts, 'i18n-raw-enums-baseline.json'), 'utf8'));
  const labPins = baseline.pinned.filter((pin) => pin.startsWith('src/app/lab/'));
  assert.ok(labPins.length > 0, 'the baseline must still be read');
  const unexplained = labPins.filter((pin) => !(pin in LAB_FREE_TEXT));
  assert.deepEqual(unexplained, [], 'pipe these through | enumLabel instead of pinning them');
});

test('QA D963: the lab priority, channel and specimen status are declared enum domains', () => {
  const domains = JSON.parse(readFileSync(join(scripts, 'i18n-enum-domains.json'), 'utf8'));
  for (const domain of ['labOrderPriority', 'labOrderChannel', 'labSpecimenStatus']) {
    assert.ok(domains[domain], `${domain} must be declared`);
  }
});
