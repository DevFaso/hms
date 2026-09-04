/**
 * Sidebar grouping.
 *
 * <p>The nav is ~90 entries across staff and patient roles. Flat, it is a
 * scroll-and-hunt list where "Lab Results" sits eight rows from "Laboratory"
 * and "Bed Board" nowhere near "Admissions". Grouping is what makes it
 * scannable; the search box below the header is what makes it fast once
 * someone knows the module's name.
 *
 * <p><b>Why a route map rather than a field on each item.</b> The nav items in
 * `shell.ts` are declared across several role-conditional branches and each
 * carries comments explaining the exact `RoleGuard` list it mirrors — the
 * result of a role audit that found entries landing users on 403 pages. Adding
 * a property to ninety literals would touch every one of those and bury the
 * gating rationale in churn. The grouping is presentation; it belongs beside
 * the presentation, in one table you can read top to bottom.
 *
 * <p>Nothing here filters. A route's group decides only where it renders, never
 * whether it renders — permissions and roles remain the single gate, exactly as
 * before.
 */

/** Group ids, in the order they render. */
export const NAV_GROUP_IDS = [
  // Staff
  'MY_DAY',
  'PATIENTS_FLOW',
  'CARE',
  'PHARMACY',
  'DIAGNOSTICS',
  'BILLING',
  'INSIGHTS',
  'ADMINISTRATION',
  'PLATFORM',
  // Patient portal
  'MY_CARE',
  'MY_HEALTH',
  'MY_RECORDS',
  'MY_BILLING',
  // Fallback
  'OTHER',
] as const;

export type NavGroupId = (typeof NAV_GROUP_IDS)[number];

/**
 * The group a route belongs to.
 *
 * <p>Any route missing from this map renders under {@link OTHER_GROUP} rather
 * than disappearing — a nav entry that vanishes because someone forgot a line
 * here would be the "built but unreachable" failure this codebase keeps
 * producing. `shell.spec.ts` fails on an unmapped route, so the fallback is a
 * safety net and not a place things quietly live.
 */
export const NAV_GROUP_BY_ROUTE: Readonly<Record<string, NavGroupId>> = {
  // ── MY DAY ───────────────────────────────────────────────────────────
  '/dashboard': 'MY_DAY',
  '/in-basket': 'MY_DAY',
  '/appointments': 'MY_DAY',
  '/on-call': 'MY_DAY',
  // Labelled "Scheduling" for anyone who manages rotas and "Availability" for
  // everyone else, but it is the same question On-Call answers — who is
  // working when — so it belongs beside it rather than under Administration.
  '/scheduling': 'MY_DAY',
  '/digital-signatures': 'MY_DAY',

  // ── PATIENTS & FLOW ──────────────────────────────────────────────────
  '/patients': 'PATIENTS_FLOW',
  '/patient-tracker': 'PATIENTS_FLOW',
  '/reception': 'PATIENTS_FLOW',
  '/registrations': 'PATIENTS_FLOW',
  '/encounters': 'PATIENTS_FLOW',
  '/admissions': 'PATIENTS_FLOW',
  '/bed-board': 'PATIENTS_FLOW',
  '/bed-management': 'PATIENTS_FLOW',
  '/discharge': 'PATIENTS_FLOW',
  '/reception/empi-candidates': 'PATIENTS_FLOW',

  // ── CARE ─────────────────────────────────────────────────────────────
  '/consultations': 'CARE',
  '/treatment-plans': 'CARE',
  '/procedure-orders': 'CARE',
  '/referrals': 'CARE',
  '/maternity': 'CARE',
  '/nurse-station': 'CARE',
  '/emar': 'CARE',
  '/patient-education': 'CARE',
  '/registries': 'CARE',
  '/panels': 'CARE',
  '/consent-management': 'CARE',

  // ── MEDICATIONS & PHARMACY ───────────────────────────────────────────
  '/prescriptions': 'PHARMACY',
  '/refills': 'PHARMACY',
  '/medication-history': 'PHARMACY',
  '/pharmacy/drug-interactions': 'PHARMACY',
  '/medication-catalog': 'PHARMACY',
  '/pharmacy-registry': 'PHARMACY',
  '/pharmacy/inventory': 'PHARMACY',
  '/pharmacy/goods-receipt': 'PHARMACY',
  '/pharmacy/stock-adjustment': 'PHARMACY',
  '/pharmacy/dispensing': 'PHARMACY',
  '/pharmacy/stock-routing': 'PHARMACY',
  '/pharmacy/claims': 'PHARMACY',
  '/pharmacy/checkout': 'PHARMACY',
  '/pharmacy/mtm': 'PHARMACY',

  // ── DIAGNOSTICS ──────────────────────────────────────────────────────
  '/lab': 'DIAGNOSTICS',
  '/lab-results': 'DIAGNOSTICS',
  '/lab-approval-queue': 'DIAGNOSTICS',
  '/lab-qc-dashboard': 'DIAGNOSTICS',
  '/lab-ops-dashboard': 'DIAGNOSTICS',
  '/lab-test-config': 'DIAGNOSTICS',
  '/lab-staff': 'DIAGNOSTICS',
  '/lab-instruments': 'DIAGNOSTICS',
  '/lab-inventory': 'DIAGNOSTICS',
  '/lab-outbox': 'DIAGNOSTICS',
  '/microbiology': 'DIAGNOSTICS',
  '/transfusions': 'DIAGNOSTICS',
  '/imaging': 'DIAGNOSTICS',

  // ── BILLING ──────────────────────────────────────────────────────────
  '/billing': 'BILLING',

  // ── INSIGHTS ─────────────────────────────────────────────────────────
  '/analytics': 'INSIGHTS',
  '/reports': 'INSIGHTS',
  '/morbidity': 'INSIGHTS',

  // ── ADMINISTRATION ───────────────────────────────────────────────────
  '/staff': 'ADMINISTRATION',
  '/departments': 'ADMINISTRATION',
  '/slot-admin': 'ADMINISTRATION',
  '/users': 'ADMINISTRATION',
  '/hospitals': 'ADMINISTRATION',
  '/organizations': 'ADMINISTRATION',
  '/roles': 'ADMINISTRATION',
  '/admin': 'ADMINISTRATION',
  '/admin-assignments': 'ADMINISTRATION',
  '/admin-governance': 'ADMINISTRATION',
  '/feature-flags': 'ADMINISTRATION',
  // The same entry resolves to one path or the other depending on whether the
  // active role is super admin, so both spellings need a home.
  '/audit-logs': 'ADMINISTRATION',

  // ── PLATFORM (super-admin) ───────────────────────────────────────────
  '/super-admin': 'PLATFORM',
  '/super-admin/platform': 'PLATFORM',
  '/super-admin/integrations': 'PLATFORM',
  '/super-admin/integration-messages': 'PLATFORM',
  '/super-admin/cost': 'PLATFORM',
  '/super-admin/audit-search': 'PLATFORM',
  '/super-admin/audit-logs': 'PLATFORM',
  '/super-admin/emergency': 'PLATFORM',
  '/super-admin/subscriptions': 'PLATFORM',
  '/super-admin/data-residency': 'PLATFORM',

  // ── Patient portal ───────────────────────────────────────────────────
  '/my-appointments': 'MY_CARE',
  '/my-care-team': 'MY_CARE',
  '/my-visits': 'MY_CARE',
  '/my-summaries': 'MY_CARE',

  '/my-medications': 'MY_HEALTH',
  '/my-lab-results': 'MY_HEALTH',
  '/my-vitals': 'MY_HEALTH',
  '/my-medical-history': 'MY_HEALTH',
  '/my-education': 'MY_HEALTH',

  '/my-records': 'MY_RECORDS',
  '/my-documents': 'MY_RECORDS',
  '/my-sharing': 'MY_RECORDS',
  '/my-family-access': 'MY_RECORDS',

  '/my-billing': 'MY_BILLING',
  '/my-pharmacy-invoices': 'MY_BILLING',
};

export const OTHER_GROUP: NavGroupId = 'OTHER';

/** Translation key for a group heading. */
export function navGroupTranslationKey(id: NavGroupId): string {
  return `NAV.GROUP.${id}`;
}

/** The group a route renders under, falling back rather than hiding it. */
export function navGroupForRoute(route: string): NavGroupId {
  return NAV_GROUP_BY_ROUTE[route] ?? OTHER_GROUP;
}
