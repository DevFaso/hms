/**
 * QA D2120 (r59): a super-admin with no clinical role was offered, and the server accepted,
 * programme enrolment and visits. User decision 2026-11-14: a clinical act needs a clinical
 * role the caller holds; the super-admin reads clinical data and never records it. The server
 * gates every clinical write with ClinicalActor.holdsOwnRole (SuperAdminClinicalWriteGuardTest).
 *
 * The portal side of the class: a component permission (`readonly canX = ...` or
 * `canX(): boolean`) that offers an ACTION to ROLE_SUPER_ADMIN alongside a clinical role. A
 * read permission (its name says See, View, Read, List, Download, Print or Access) is not an
 * action and is skipped. A non-clinical action that names both (configuration, demographics)
 * is pinned in the baseline with its reason.
 */
const CLINICAL = new Set([
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_SURGEON',
  'ROLE_RADIOLOGIST',
  'ROLE_ANESTHESIOLOGIST',
  'ROLE_PHYSIOTHERAPIST',
  'ROLE_LAB_SCIENTIST',
  'ROLE_LAB_TECHNICIAN',
  'ROLE_PHARMACIST',
]);
const READ_NAME = /(See|View|Read|List|Download|Print|Access)/;
const CALL = /hasAnyActiveRole(?:Exactly)?\(\s*\[([^\]]*)\]/g;
const MEMBER =
  /^\s*(?:(?:private|protected|public|readonly|static)\s+)*(?:([A-Za-z0-9_]+)\s*=|(?:get\s+)?([A-Za-z0-9_]+)\s*\(\)\s*:\s*boolean\s*\{)/gm;

export function superAdminClinicalGateFindings(source) {
  const findings = [];
  for (const call of source.matchAll(CALL)) {
    const roles = [...call[1].matchAll(/'(ROLE_[A-Z_]+)'/g)].map((m) => m[1]);
    if (!roles.includes('ROLE_SUPER_ADMIN') || !roles.some((r) => CLINICAL.has(r))) continue;
    const before = source.slice(Math.max(0, call.index - 300), call.index);
    let name = null;
    for (const m of before.matchAll(MEMBER)) name = m[1] ?? m[2];
    if (!name || READ_NAME.test(name)) continue;
    findings.push({
      line: source.slice(0, call.index).split('\n').length,
      rule: 'super-admin-clinical-action',
      anchor: name,
      message: `${name} offers a clinical action to ROLE_SUPER_ADMIN (QA D2120: a super-admin never records clinical data)`,
    });
  }
  return findings;
}
