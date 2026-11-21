/**
 * QA D1924 (r53): the roles the Imagerie page (`/imaging`) admits, so a page
 * outside it offers the way there only to them. A lab technician or a
 * pharmacist reads the chart review but not Imagerie, and would land on a
 * 403 page. `chart-review.component.spec.ts` checks this list against the
 * route's own `data.roles`.
 */
export const IMAGING_PAGE_ROLES: readonly string[] = [
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_RADIOLOGIST',
  // Policy rule 3 (2026-11-20, provisional): the imaging technician works the worklist.
  'ROLE_IMAGING_TECHNICIAN',
  // The hospital's administrator and the super-admin read Imagerie (2026-11-14).
  // Not ROLE_ADMIN, the organisation's administrator (platform admin rules 2026-11-20).
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_SUPER_ADMIN',
];

/**
 * Policy rule 3 (owner 2026-11-20, PROVISIONAL until the clinic's radiologist or medical director
 * signs it off): « Technicien d'imagerie médicale / Manipulateur ». The technician acquires,
 * documents and completes an exam; they never order one and never write, sign or add to a report.
 */
export const IMAGING_TECHNICIAN_ROLES: readonly string[] = ['ROLE_IMAGING_TECHNICIAN'];
/**
 * A doctor or a radiologist who is also a technician keeps the wider reach on a study's status
 * (`ClinicalActor.actsOnExamAsTechnician` on the server).
 */
export const IMAGING_EXAM_WIDER_ROLES: readonly string[] = ['ROLE_DOCTOR', 'ROLE_RADIOLOGIST'];
