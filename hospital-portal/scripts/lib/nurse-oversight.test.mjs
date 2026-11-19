import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = new URL('../../src/app/nurse-station/', import.meta.url);
const ts = readFileSync(new URL('nurse-station.ts', root), 'utf8');
const html = readFileSync(new URL('nurse-station.html', root), 'utf8');
const css = readFileSync(new URL('nurse-station.scss', root), 'utf8');
const gates = {
  recordVitals: 'canDocumentCare',
  submitVitals: 'canDocumentCare',
  administerMedication: 'canDocumentCare',
  promptHoldRefuse: 'canDocumentCare',
  confirmHoldRefuse: 'canDocumentCare',
  confirmEarlyDose: 'canDocumentCare',
  openCareNote: 'canDocumentCare',
  submitCareNote: 'canDocumentCare',
  openHandoffCreate: 'canCreateNursing',
  submitCreateHandoff: 'canCreateNursing',
  openTaskCreate: 'canCreateNursing',
  submitCreateTask: 'canCreateNursing',
  completeTask: 'canDocumentCare',
  completeHandoff: 'canDocumentCare',
  openTriage: 'canTriage',
  openTriageForEncounter: 'canTriage',
  openIntake: 'canCreateNursing',
};
for (const [name, gate] of Object.entries(gates)) {
  test(`nursing entry point ${name} requires its clinical role before opening or writing`, () => {
    assert.match(
      ts,
      new RegExp(`${name}\\([^\\n]*\\): void \\{\\s*if \\(!this\\.${gate}\\(\\)\\) return;`),
    );
  });
}
test('all nursing card/list launcher siblings are role-gated, while chart reads remain available', () => {
  for (const match of html.matchAll(/<button\b[\s\S]*?<\/button>/g)) {
    const click = /\(click\)="(\w+)\(/.exec(match[0]);
    if (!click || !gates[click[1]] || /^(submit|confirm)/.test(click[1])) continue;
    const gate = gates[click[1]];
    const before = html.slice(0, match.index);
    const lastIf = before.lastIndexOf('@if');
    assert.match(before.slice(lastIf), new RegExp(`^@if \\(${gate}\\(\\)\\) \\{\\s*$`));
  }
  assert.match(html, /class="action-btn action-view"/);
});
test('every workboard sibling contains wrapping actions and a shrinkable card', () => {
  assert.match(css, /\.workboard-card-actions\s*\{[^}]*flex-wrap:\s*wrap/s);
  assert.match(
    css,
    /\.workboard-card-actions \.action-btn\s*\{[^}]*min-width:\s*0;[^}]*white-space:\s*normal/s,
  );
  assert.match(css, /minmax\(min\(100%,\s*22rem\),\s*1fr\)/);
});

test('bedside eMAR sibling requires a clinical role at verification and the shared write boundary', () => {
  const source = readFileSync(new URL('emar/emar.component.ts', root), 'utf8');
  const template = readFileSync(new URL('emar/emar.component.html', root), 'utf8');
  for (const name of ['verify', 'sendAdministration']) {
    assert.match(
      source,
      new RegExp(
        `${name}\\([^\\n]*\\): void \\{\\s*if \\(!this\\.canDocumentCare\\(\\)\\) return;`,
      ),
    );
  }
  assert.match(template, /@if \(canDocumentCare\(\)\) \{/);
  assert.match(source, /hasAnyActiveRole\(\['ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_DOCTOR'\]\)/);
});

test('triage requires an active nurse role rather than expanded administrative authorities', () => {
  assert.match(
    ts,
    /canTriage\(\): boolean \{\s*return this\.roleContext\.hasAnyActiveRole\(\['ROLE_NURSE'\]\);/,
  );
});

// The raw-enum guard pin covers only these two traced human-entered names.
test('pinned nursing name options remain free-text department and clinician choices', () => {
  assert.equal([...html.matchAll(/<option \[value\]="name">/g)].length, 2);
  for (const list of ['boardDepartments', 'boardClinicians']) {
    assert.ok(html.includes(`@for (name of ${list}(); track name)`));
  }
  const dto = readFileSync(
    new URL(
      '../../../hospital-core/src/main/java/com/example/hms/payload/dto/nurse/NurseWorkboardPatientDTO.java',
      import.meta.url,
    ),
    'utf8',
  );
  assert.match(dto, /String departmentName;/);
  assert.match(dto, /String attendingDoctor;/);
});
