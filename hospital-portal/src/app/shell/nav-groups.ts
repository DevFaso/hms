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

import {
  FacilityType,
  ProviderFacilityType,
  isProviderFacilityType,
} from '../services/provider.model';

/** Group ids, in the order they render. */
export const NAV_GROUP_IDS = [
  // Provider facility (a private pharmacy or laboratory)
  'FACILITY',
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
  '/mortality': 'PATIENTS_FLOW',
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
  '/roi': 'PATIENTS_FLOW',

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
  '/pharmacy/stock-count': 'PHARMACY',
  '/pharmacy/consumption': 'PHARMACY',
  '/pharmacy/controlled-register': 'PHARMACY',
  '/pharmacy/dispensing': 'PHARMACY',
  '/pharmacy/stock-routing': 'PHARMACY',
  '/pharmacy/claims': 'PHARMACY',
  '/pharmacy/checkout': 'PHARMACY',
  '/pharmacy/counter-sale': 'PHARMACY',
  '/pharmacy/mtm': 'PHARMACY',
  '/pharmacy/reconciliation': 'PHARMACY',
  '/pharmacy/billing-deferrals': 'PHARMACY',
  '/pharmacy/caisse': 'PHARMACY',

  // ── DIAGNOSTICS ──────────────────────────────────────────────────────
  '/lab': 'DIAGNOSTICS',
  '/lab-results': 'DIAGNOSTICS',
  '/lab-approval-queue': 'DIAGNOSTICS',
  '/lab-release-worklist': 'DIAGNOSTICS',
  '/lab-critical-pending': 'DIAGNOSTICS',
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
  '/billing/tarifs': 'BILLING',
  '/billing/organismes': 'BILLING',
  '/billing/parts-assureurs': 'BILLING',
  '/billing/bordereaux': 'BILLING',
  '/billing/reglements-assureurs': 'BILLING',
  '/billing/creances-assureurs': 'BILLING',

  // ── INSIGHTS ─────────────────────────────────────────────────────────
  '/analytics': 'INSIGHTS',
  '/reports': 'INSIGHTS',
  '/unsigned-notes': 'INSIGHTS',
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
  '/webhooks': 'ADMINISTRATION',
  '/patient-notifications': 'ADMINISTRATION',
  '/admin/integrations/dhis2': 'ADMINISTRATION',
  '/admin-governance': 'ADMINISTRATION',
  '/break-glass-review': 'ADMINISTRATION',
  // QA D4102 (r81): the register of unresolved urgent alerts.
  '/urgent-alerts/incidents': 'ADMINISTRATION',
  '/feature-flags': 'ADMINISTRATION',
  // The same entry resolves to one path or the other depending on whether the
  // active role is super admin, so both spellings need a home.
  '/audit-logs': 'ADMINISTRATION',
  // V356: the provisional rules and this hospital's sign-off of each.
  '/clinical-rules': 'ADMINISTRATION',

  // ── PLATFORM (super-admin) ───────────────────────────────────────────
  '/super-admin': 'PLATFORM',
  '/super-admin/platform': 'PLATFORM',
  '/super-admin/integrations': 'PLATFORM',
  '/super-admin/integration-messages': 'PLATFORM',
  '/admin/adt-intake-configs': 'PLATFORM',
  '/super-admin/cost': 'PLATFORM',
  '/super-admin/audit-search': 'PLATFORM',
  '/super-admin/audit-logs': 'PLATFORM',
  '/super-admin/emergency': 'PLATFORM',
  '/super-admin/subscriptions': 'PLATFORM',
  '/super-admin/data-residency': 'PLATFORM',
  '/super-admin/providers': 'PLATFORM',
  // QA r65 D2777: the super-admin's Analytics and Feature Flags entries name
  // the route the rewrite guard lands on; same groups as their legacy paths.
  '/super-admin/analytics': 'INSIGHTS',
  '/super-admin/feature-flags': 'ADMINISTRATION',

  // ── FACILITY (provider shell) ────────────────────────────────────────
  '/provider': 'FACILITY',
  '/provider/profile': 'FACILITY',
  '/provider/staff': 'FACILITY',
  '/provider/audit': 'FACILITY',

  // ── Patient portal ───────────────────────────────────────────────────
  '/my-appointments': 'MY_CARE',
  '/my-care-team': 'MY_CARE',
  '/my-visits': 'MY_CARE',
  '/my-summaries': 'MY_CARE',

  '/my-medications': 'MY_HEALTH',
  '/my-lab-results': 'MY_HEALTH',
  '/my-imaging': 'MY_HEALTH',
  '/my-vitals': 'MY_HEALTH',
  '/my-screenings': 'MY_HEALTH',
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

/**
 * QA r65 D2777: the clinical workstations not listed in a super-admin's sidebar.
 *
 * <p>The super-admin had 72 sidebar entries, about 45 of them a hospital's
 * work modules, for a role that cannot take a clinical action (scorecard S1:
 * a new encounter, a note, vitals, a lab or imaging order, a prescription
 * each answer 403 "Action clinique : elle nécessite un rôle clinique"). The
 * rule is not a matter of taste: a hospital's own administrator, the other
 * non-clinical admin, has no entry for these modules either. So the
 * super-admin's sidebar lists the hospital admin's modules, the platform's,
 * and the work queues of {@link SUPER_ADMIN_KEPT_WORK_QUEUES}.
 *
 * <p>Listing only. The entries stay in the nav model, so the sidebar's
 * search still finds each module by name, and the routes and their guards
 * are untouched: a super-admin still reads an encounter, a prescription or a
 * lab result from the patient record or a link, exactly as before.
 *
 * <p>`shell.spec.ts` holds the rule both ways: every route here is absent
 * from the hospital admin's sidebar, and every clinical-group entry a
 * super-admin has that a hospital admin lacks is either here or a recorded
 * exception.
 */
export const SUPER_ADMIN_HIDDEN_WORKSTATIONS: ReadonlySet<string> = new Set([
  // Patients & flow
  '/encounters',
  '/admissions',
  '/discharge',
  // Care
  '/consultations',
  '/treatment-plans',
  '/procedure-orders',
  '/referrals',
  '/nurse-station',
  '/emar',
  '/patient-education',
  '/registries',
  // Medications & pharmacy
  '/prescriptions',
  '/pharmacy/mtm',
  // Diagnostics
  '/lab',
  '/lab-results',
  '/microbiology',
  '/transfusions',
]);

/**
 * The clinical-group entries a super-admin keeps although a hospital admin
 * has none: each is a queue the super-admin really works in, with its reason.
 */
export const SUPER_ADMIN_KEPT_WORK_QUEUES: Readonly<Record<string, string>> = {
  '/lab-approval-queue':
    'reviews and approves lab test definitions where a new hospital has no lab director or quality manager yet (r65 set-up)',
  '/lab-release-worklist':
    'expanded into the laboratory (RoleExpansion.SUPER_ADMIN_INHERITS): the one role that may always release',
};

/** The groups that hold a hospital's clinical work (what the two lists above are drawn from). */
export const CLINICAL_WORK_GROUPS: readonly NavGroupId[] = [
  'PATIENTS_FLOW',
  'CARE',
  'PHARMACY',
  'DIAGNOSTICS',
];

/** Translation key for a group heading. */
export function navGroupTranslationKey(id: NavGroupId): string {
  return `NAV.GROUP.${id}`;
}

/** The group a route renders under, falling back rather than hiding it. */
export function navGroupForRoute(route: string): NavGroupId {
  return NAV_GROUP_BY_ROUTE[route] ?? OTHER_GROUP;
}

/**
 * The provider shell's navigation (provider plan AC-12, §6.6): a user acting
 * at a private pharmacy or laboratory sees these entries and nothing else.
 *
 * <p>This is the facility-type filter: an entry shows only for the facility
 * types it names, and an admin-only entry only to the facility's
 * PROVIDER_ADMIN. P2 adds the pharmacy offers and the laboratory pages here,
 * each naming its own type. UX only: the backend confines every provider
 * request to its allow-list whatever the nav shows.
 */
export interface ProviderNavEntry {
  icon: string;
  label: string;
  translationKey: string;
  route: string;
  facilityTypes: ProviderFacilityType[];
  adminOnly: boolean;
}

export const PROVIDER_NAV: readonly ProviderNavEntry[] = [
  {
    icon: 'storefront',
    label: 'Home',
    translationKey: 'NAV.PROVIDER_HOME',
    route: '/provider',
    facilityTypes: ['PHARMACY', 'LABORATORY'],
    adminOnly: false,
  },
  {
    icon: 'badge',
    label: 'Profile',
    translationKey: 'NAV.PROVIDER_PROFILE',
    route: '/provider/profile',
    facilityTypes: ['PHARMACY', 'LABORATORY'],
    adminOnly: false,
  },
  {
    icon: 'group',
    label: 'Staff',
    translationKey: 'NAV.PROVIDER_STAFF',
    route: '/provider/staff',
    facilityTypes: ['PHARMACY', 'LABORATORY'],
    adminOnly: true,
  },
  {
    icon: 'history',
    label: 'Audit trail',
    translationKey: 'NAV.PROVIDER_AUDIT',
    route: '/provider/audit',
    facilityTypes: ['PHARMACY', 'LABORATORY'],
    adminOnly: true,
  },
];

/** The provider nav entries a facility of this type shows to this user. */
export function providerNavEntries(
  facilityType: FacilityType | null | undefined,
  providerAdmin: boolean,
): ProviderNavEntry[] {
  if (!isProviderFacilityType(facilityType)) return [];
  return PROVIDER_NAV.filter(
    (entry) => entry.facilityTypes.includes(facilityType) && (providerAdmin || !entry.adminOnly),
  );
}
