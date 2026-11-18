import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const root = new URL('../../src/app/super-admin/', import.meta.url);
const ts = readFileSync(new URL('super-admin.ts', root), 'utf8');
const html = readFileSync(new URL('super-admin.html', root), 'utf8');
const css = readFileSync(new URL('super-admin.scss', root), 'utf8');
for (const key of [
  'consultations',
  'admissions',
  'prescriptions',
  'labOrders',
  'labResults',
  'labTestDefinitions',
  'treatmentPlans',
  'referrals',
]) {
  test(`dashboard activity ${key} uses the shared structured renderer and destination`, () => {
    assert.match(ts, new RegExp(`${key}: '/[^']+'`));
    for (const field of ['subject', 'details', 'statusLabel', 'timestampLabel'])
      assert.ok(html.includes(`row.${field}`), field);
    assert.ok(html.includes('row.hospital'));
  });
}
test('dashboard previews use page scrolling and a full-width audit section', () => {
  assert.ok(html.includes('@if (entityLabel(event.resourceName); as resource)'));

  assert.match(css, /\.workspace-grid\s*\{[^}]*grid-template-columns: minmax\(0, 1fr\)/s);
  for (const selector of ['activity-list', 'audit-list']) {
    const blocks = [...css.matchAll(new RegExp(`\\.${selector}\\s*\\{([^}]*)\\}`, 'g'))];
    for (const block of blocks) assert.doesNotMatch(block[1], /max-height|overflow-y/);
  }
  assert.ok(
    html.indexOf('dashboard-panel audit-panel') > html.indexOf('dashboard-panel clinical-activity'),
  );
});

const backend = new URL('../../../hospital-core/src/main/java/com/example/hms/', import.meta.url);
const dashboard = readFileSync(
  new URL('service/SuperAdminDashboardServiceImpl.java', backend),
  'utf8',
);
for (const repo of ['admissionRepository', 'labOrderRepository', 'prescriptionRepository']) {
  test(`${repo} scopes the dashboard preview before paging`, () => {
    assert.ok(dashboard.includes(`${repo}.findByHospital_Id(hospitalId, pageable)`));
  });
}
for (const name of ['Consultation', 'GeneralReferral']) {
  test(`${name} oversight feed honors a verified pin`, () => {
    const source = readFileSync(new URL(`service/impl/${name}ServiceImpl.java`, backend), 'utf8');
    const body = source.slice(source.indexOf('getRecentForSuperAdmin(Pageable pageable)'));
    assert.match(body.split('\n    }')[0], /superAdminPinnedHospitalIdOrNull\(\)/);
    assert.match(body.split('\n    }')[0], /findByHospital_Id\(hospitalId, pageable\)/);
  });
}

test('dashboard result count includes the reader performing-hospital scope', () => {
  assert.ok(dashboard.includes('labResultRepository.countHandledByHospital(chipScope)'));
  const repository = readFileSync(new URL('repository/LabResultRepository.java', backend), 'utf8');
  const method = repository.indexOf('long countHandledByHospital');
  const query = repository.slice(repository.lastIndexOf('@Query', method), method);
  assert.match(query, /OR r\.labOrder\.performingHospital\.id = :hospitalId/);
});
