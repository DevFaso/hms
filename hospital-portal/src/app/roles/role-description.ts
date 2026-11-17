/**
 * QA D434: the role cards printed the description stored on the role row,
 * which the migrations seeded in English, so a French administrator read
 * "Licensed physician authorized to diagnose…" under "Médecin".
 *
 * A system role whose description is still the seeded text is shown through
 * `ROLES.SYSTEM_DESC.<CODE>` in the reader's language. A description an
 * administrator wrote (a custom role, or a system role they reworded) is
 * theirs, and is shown as typed.
 */
export const SEEDED_ROLE_DESCRIPTIONS: Readonly<Record<string, string>> = {
  ROLE_ACCOUNTANT:
    'Financial accountant managing ledger entries, expense tracking, and financial reconciliation',
  ROLE_ADMIN: 'General administrative user with elevated operational privileges',
  ROLE_ANESTHESIOLOGIST:
    'Anesthesia specialist managing pre-operative assessment, sedation, and pain management',
  ROLE_BILLING_SPECIALIST:
    'Billing specialist handling invoicing, insurance claims, payment processing, and financial reports',
  ROLE_CLAIMS_REVIEWER: 'Staff reviewing and submitting pharmacy insurance claims for AMU/insurers',
  ROLE_CLEANER:
    'Facility maintenance staff responsible for sanitation and cleanliness of hospital premises',
  ROLE_DOCTOR:
    'Licensed physician authorized to diagnose, prescribe, order tests, and manage patient treatment plans',
  ROLE_HOSPITAL_ADMIN:
    'Hospital administrator responsible for managing staff, departments, billing, and hospital-level configuration',
  ROLE_INVENTORY_CLERK:
    'Pharmacy staff managing goods receipt, stock adjustments, and inventory counts',
  ROLE_LAB_DIRECTOR:
    'Laboratory Director with ultimate approval authority over lab test definitions under CLIA/CAP/ISO 15189 regulations',
  ROLE_LAB_MANAGER:
    'Lab manager overseeing technician workflows, result verification, quality control, and lab operations',
  ROLE_LAB_SCIENTIST:
    'Laboratory scientist responsible for processing specimens, running tests, and publishing results',
  ROLE_LAB_TECHNICIAN:
    'Lab technician responsible for specimen collection, receipt, bench processing, and preliminary result entry',
  ROLE_MANAGER:
    'Department or operational manager overseeing staff coordination and resource planning',
  ROLE_MIDWIFE:
    'Certified midwife specializing in maternal care, labor support, and newborn assessment',
  ROLE_MODERATOR: 'Content and activity moderator with oversight and review capabilities',
  ROLE_NURSE:
    'Registered nurse providing patient care, administering medications, and documenting vital signs',
  ROLE_PATIENT:
    'Registered patient with self-service access to medical records, appointments, and prescriptions',
  ROLE_PHARMACIST:
    'Licensed pharmacist handling medication dispensing, drug interaction checks, and inventory management',
  ROLE_PHARMACY_VERIFIER:
    'Pharmacist authorized to verify and co-sign controlled-substance dispensing',
  ROLE_PHYSICIAN:
    'General physician with clinical privileges for patient consultation and treatment',
  ROLE_PHYSIOTHERAPIST:
    'Physical therapist designing rehabilitation programs, therapy sessions, and mobility assessments',
  ROLE_PROVIDER_ADMIN: 'Administrator of an external provider facility (pharmacy or laboratory)',
  ROLE_QUALITY_MANAGER:
    'Quality/QA Manager responsible for validation documentation review and compliance with CAP/CLIA/ISO standards before Lab Director approval',
  ROLE_RADIOLOGIST:
    'Imaging specialist interpreting X-rays, CT scans, MRIs, and generating radiology reports',
  ROLE_RECEPTIONIST:
    'Front-desk receptionist managing patient check-in, appointments, and visitor coordination',
  ROLE_SECURITY:
    'Security personnel managing access control, surveillance, and facility safety protocols',
  ROLE_STAFF: 'General hospital support staff with schedule and appointment visibility',
  ROLE_STORE_MANAGER:
    'Senior pharmacy staff overseeing inventory operations, reorder thresholds, and stock reports',
  ROLE_SUPER_ADMIN:
    'System-wide super administrator with full access to all organizations, hospitals, and system settings',
  ROLE_SUPPORT:
    'Technical or customer support staff assisting users with system issues and inquiries',
  ROLE_SURGEON:
    'Surgical specialist performing operations, managing surgical plans, and post-operative care',
  ROLE_TECHNICIAN:
    'Medical or IT technician responsible for equipment maintenance and technical support',
  ROLE_USER: 'Standard system user with basic access permissions',
};

/**
 * The translation key for a role's description, or `null` when the stored
 * text is not the seeded one and must be shown as is.
 */
export function roleDescriptionKey(role: {
  code?: string | null;
  authority?: string | null;
  description?: string | null;
}): string | null {
  const code = (role.code || role.authority || '').toUpperCase();
  const seeded = SEEDED_ROLE_DESCRIPTIONS[code];
  if (!seeded || role.description?.trim() !== seeded) return null;
  return `ROLES.SYSTEM_DESC.${code.replace(/^ROLE_/, '')}`;
}
