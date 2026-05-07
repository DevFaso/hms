import { ChangeDetectorRef, OnDestroy, Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

/**
 * Converts raw UPPER_SNAKE_CASE enum **display values** (status badges, urgency
 * pills, severity icons, role tags, etc.) into human-readable, locale-aware
 * labels.
 *
 * Lookup order on every call (designed so the UI never shows a raw key, even
 * when i18n is missing):
 *
 * 1. **i18n** — `PORTAL.ENUM.<UPPER_SNAKE_GROUP>.<VALUE>` via {@link
 *    TranslateService}, where the group name is derived from the optional
 *    `domain` argument by upper-snaking it (`prescriptionStatus` →
 *    `PRESCRIPTION_STATUS`). This is the canonical source of truth.
 * 2. **In-memory English `LABELS`** below — kept as a resilient fallback for
 *    keys not yet ported into the locale JSON, so a missing translation never
 *    renders an ENUM key on screen.
 * 3. **Prettify** — UPPER_SNAKE_CASE → Title Case (last-resort, ensures we
 *    never render `[object Object]`, `null`, or a raw key like
 *    `PRESCRIPTION_STATUS.SIGNED`).
 *
 * **The pipe only translates the *display* — never the wire value.** Callers
 * must still compare the raw enum (`if (rx.status === 'SIGNED')`) against the
 * untranslated value. Forbidden patterns are documented in the PR description.
 *
 * Usage:
 * ```html
 * {{ 'BLOOD_PRESSURE' | enumLabel }}              <!-- "Blood Pressure" -->
 * {{ rx.status | enumLabel: 'prescriptionStatus' }}<!-- "Signée" in French -->
 * {{ enc.status | enumLabel: 'encounterStatus' }}
 * {{ alert.severity | enumLabel: 'alertSeverity' }}
 * ```
 *
 * The pipe is **impure** so badges refresh on language change (matching the
 * behaviour of `@ngx-translate`'s own pipe). A small per-instance memo keyed
 * by `lang|domain|value` keeps re-evaluation cheap.
 */
@Pipe({ name: 'enumLabel', standalone: true, pure: false })
export class EnumLabelPipe implements PipeTransform, OnDestroy {
  private readonly translate = inject(TranslateService);
  /**
   * Host CDR — required so `OnPush` components (e.g. the storyboard banner)
   * re-render their badges when the user switches language at runtime.
   * `{ optional: true }` keeps unit-test wiring simple where the pipe is
   * constructed outside an Angular component (`new EnumLabelPipe()` in a
   * `runInInjectionContext`).
   */
  private readonly cdr = inject(ChangeDetectorRef, { optional: true });
  private readonly langSub: Subscription;
  /** Memoised lookups: key = `${lang}|${domain ?? ''}|${value}`. */
  private readonly memo = new Map<string, string>();

  constructor() {
    // Clear the memo whenever the user switches locale so cached French
    // labels don't leak into Spanish, etc., and ask the host to re-render
    // so OnPush components actually re-run their template (matches
    // ngx-translate's own pipe behaviour).
    this.langSub = this.translate.onLangChange.subscribe(() => {
      this.memo.clear();
      this.cdr?.markForCheck();
    });
  }

  ngOnDestroy(): void {
    this.langSub.unsubscribe();
  }

  /** English fallback labels. Authoritative source is the locale JSON files
   * under `PORTAL.ENUM.*`; this map exists so a missing translation key never
   * leaks into the UI. Keep groups alphabetically ordered within each section. */
  private static readonly LABELS: Record<string, Record<string, string>> = {
    /* ── Vital sign types ─────────────────────────────────── */
    vitalType: {
      BLOOD_PRESSURE: 'Blood Pressure',
      HEART_RATE: 'Heart Rate',
      TEMPERATURE: 'Body Temperature',
      WEIGHT: 'Weight',
      HEIGHT: 'Height',
      OXYGEN_SATURATION: 'Oxygen Saturation (SpO₂)',
      RESPIRATORY_RATE: 'Respiratory Rate',
      BMI: 'BMI',
      BLOOD_GLUCOSE: 'Blood Glucose',
    },

    /* ── Vital source ─────────────────────────────────────── */
    vitalSource: {
      NURSE_STATION: 'Nurse Station',
      CLINICAL: 'Clinical',
      HOME: 'Home Reading',
      SELF_REPORTED: 'Self-Reported',
      DEVICE: 'Connected Device',
      TRIAGE: 'Triage',
    },

    /* ── Encounter / visit types ──────────────────────────── */
    encounterType: {
      CONSULTATION: 'Consultation',
      FOLLOW_UP: 'Follow-Up',
      EMERGENCY: 'Emergency',
      ROUTINE: 'Routine Visit',
      PROCEDURE: 'Procedure',
      LAB_VISIT: 'Lab Visit',
      IMAGING: 'Imaging',
      VACCINATION: 'Vaccination',
      ADMISSION: 'Admission',
      DISCHARGE: 'Discharge',
      WALK_IN: 'Walk-In',
      TELEMEDICINE: 'Telemedicine',
      REFERRAL: 'Referral',
      PRE_ADMISSION: 'Pre-Admission',
      PRE_OP: 'Pre-Op',
      POST_OP: 'Post-Op',
      PRENATAL: 'Prenatal',
      POSTNATAL: 'Postnatal',
      DENTAL: 'Dental',
      MENTAL_HEALTH: 'Mental Health',
      REHABILITATION: 'Rehabilitation',
      SPECIALIST: 'Specialist',
      ANNUAL_PHYSICAL: 'Annual Physical',
      URGENT_CARE: 'Urgent Care',
    },

    /* ── Statuses (generic catch-all — used by many callers) ── */
    status: {
      SCHEDULED: 'Scheduled',
      CONFIRMED: 'Confirmed',
      IN_PROGRESS: 'In Progress',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
      PENDING: 'Pending',
      ACTIVE: 'Active',
      INACTIVE: 'Inactive',
      REVIEWED: 'Reviewed',
      ARRIVED: 'Arrived',
      NO_SHOW: 'No Show',
      CHECKED_IN: 'Checked In',
      DISCHARGED: 'Discharged',
      PAID: 'Paid',
      OVERDUE: 'Overdue',
      DRAFT: 'Draft',
      PARTIALLY_PAID: 'Partially Paid',
      REVOKED: 'Revoked',
      EXPIRED: 'Expired',
      APPROVED: 'Approved',
      DENIED: 'Denied',
      DISPENSED: 'Dispensed',
      REFILL_REQUESTED: 'Refill Requested',
      READY_FOR_PICKUP: 'Ready for Pickup',
      TRANSFERRED: 'Transferred',
      REJECTED: 'Rejected',
    },

    /* ── Phase 1 — Prescription status ────────────────────── */
    prescriptionStatus: {
      DRAFT: 'Draft',
      PENDING_SIGNATURE: 'Pending Signature',
      SIGNED: 'Signed',
      TRANSMITTED: 'Transmitted',
      TRANSMISSION_FAILED: 'Transmission Failed',
      CANCELLED: 'Cancelled',
      DISCONTINUED: 'Discontinued',
      PENDING_CLARIFICATION: 'Pending Clarification',
      DISPENSED: 'Dispensed',
      PARTIALLY_FILLED: 'Partially Filled',
      PENDING_STOCK: 'Pending Stock',
      REQUIRES_EXTERNAL_FILL: 'Requires External Fill',
      SENT_TO_PARTNER: 'Sent to Partner',
      PARTNER_ACCEPTED: 'Partner Accepted',
      PARTNER_REJECTED: 'Partner Rejected',
      PARTNER_DISPENSED: 'Partner Dispensed',
      PRINTED_FOR_PATIENT: 'Printed for Patient',
    },

    /* ── Phase 1 — Consultation status / urgency / type ───── */
    consultationStatus: {
      REQUESTED: 'Requested',
      ASSIGNED: 'Assigned',
      ACKNOWLEDGED: 'Acknowledged',
      SCHEDULED: 'Scheduled',
      IN_PROGRESS: 'In Progress',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
      DECLINED: 'Declined',
    },
    consultationUrgency: {
      ROUTINE: 'Routine',
      URGENT: 'Urgent',
      STAT: 'STAT',
      EMERGENCY: 'Emergency',
    },
    consultationType: {
      INPATIENT_CONSULT: 'Inpatient Consult',
      OUTPATIENT_CONSULT: 'Outpatient Consult',
      CURBSIDE_CONSULT: 'Curbside Consult',
      EMERGENCY_CONSULT: 'Emergency Consult',
      FOLLOW_UP_CONSULT: 'Follow-Up Consult',
    },

    /* ── Phase 1 — Appointment / encounter status ─────────── */
    appointmentStatus: {
      SCHEDULED: 'Scheduled',
      CONFIRMED: 'Confirmed',
      CHECKED_IN: 'Checked In',
      CANCELLED: 'Cancelled',
      COMPLETED: 'Completed',
      NO_SHOW: 'No Show',
      PENDING: 'Pending',
      RESCHEDULED: 'Rescheduled',
      IN_PROGRESS: 'In Progress',
      FAILED: 'Failed',
      UNKNOWN: 'Unknown',
    },
    encounterStatus: {
      SCHEDULED: 'Scheduled',
      ARRIVED: 'Arrived',
      TRIAGE: 'Triage',
      WAITING_FOR_PHYSICIAN: 'Waiting for Physician',
      IN_PROGRESS: 'In Progress',
      AWAITING_RESULTS: 'Awaiting Results',
      READY_FOR_DISCHARGE: 'Ready for Discharge',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
    },

    /* ── Phase 1 — Staff scheduling: leave + shift ─────────── */
    leaveStatus: {
      PENDING: 'Pending',
      APPROVED: 'Approved',
      REJECTED: 'Rejected',
      CANCELLED: 'Cancelled',
    },
    shiftStatus: {
      SCHEDULED: 'Scheduled',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
    },

    /* ── Phase 1 — Alerts / tasks ─────────────────────────── */
    alertSeverity: {
      CRITICAL: 'Critical',
      URGENT: 'Urgent',
      WARNING: 'Warning',
      INFO: 'Info',
      EXPIRED: 'Expired',
      WARN: 'Warning',
      LIFE_THREATENING: 'Life-Threatening',
      SEVERE: 'Severe',
      MODERATE: 'Moderate',
      MILD: 'Mild',
    },
    taskPriority: {
      CRITICAL: 'Critical',
      HIGH: 'High',
      NORMAL: 'Normal',
      LOW: 'Low',
      URGENT: 'Urgent',
      ROUTINE: 'Routine',
    },

    /* ── Roles ────────────────────────────────────────────── */
    role: {
      DOCTOR: 'Doctor',
      NURSE: 'Nurse',
      ADMIN: 'Admin',
      HOSPITAL_ADMIN: 'Hospital Admin',
      SUPER_ADMIN: 'Super Admin',
      RECEPTIONIST: 'Receptionist',
      LAB_TECHNICIAN: 'Lab Technician',
      PHARMACIST: 'Pharmacist',
      MIDWIFE: 'Midwife',
      PATIENT: 'Patient',
      RADIOLOGIST: 'Radiologist',
      SURGEON: 'Surgeon',
      THERAPIST: 'Therapist',
    },

    /* ── Access types ─────────────────────────────────────── */
    accessType: {
      READ: 'Viewed',
      DOWNLOAD: 'Downloaded',
      PRINT: 'Printed',
      UPDATE: 'Updated',
      CREATE: 'Created',
      DELETE: 'Deleted',
    },

    /* ── Gender ───────────────────────────────────────────── */
    gender: {
      MALE: 'Male',
      FEMALE: 'Female',
      OTHER: 'Other',
      NON_BINARY: 'Non-Binary',
      PREFER_NOT_TO_SAY: 'Prefer Not to Say',
    },

    /* ── Relationships ────────────────────────────────────── */
    relationship: {
      PARENT: 'Parent',
      SPOUSE: 'Spouse',
      CHILD: 'Child',
      CAREGIVER: 'Caregiver',
      LEGAL_GUARDIAN: 'Legal Guardian',
      SIBLING: 'Sibling',
      OTHER: 'Other',
    },

    /* ── Permissions ──────────────────────────────────────── */
    permissions: {
      ALL: 'Full Access',
      APPOINTMENTS: 'Appointments',
      LAB_RESULTS: 'Lab Results',
      MEDICATIONS: 'Medications',
      VITALS: 'Vitals',
      BILLING: 'Billing',
      'APPOINTMENTS,LAB_RESULTS,MEDICATIONS': 'Appointments, Lab Results & Medications',
    },

    /* ── Payment methods ──────────────────────────────────── */
    paymentMethod: {
      CARD: 'Credit / Debit Card',
      BANK_TRANSFER: 'Bank Transfer',
      MOBILE_MONEY: 'Mobile Money',
      CASH: 'Cash',
      INSURANCE: 'Insurance',
    },
  };

  transform(value: string | null | undefined, domain?: string): string {
    if (!value) return '';

    // ngx-translate v17 deprecated `currentLang`/`defaultLang` in favour of
    // explicit getters. Fall back to fallback-lang then 'en' so the memo key
    // is always defined even on first paint before any language is set.
    const lang = this.translate.getCurrentLang() || this.translate.getFallbackLang() || 'en';
    const memoKey = `${lang}|${domain ?? ''}|${value}`;
    const cached = this.memo.get(memoKey);
    if (cached !== undefined) return cached;

    const result = this.lookup(value, domain);
    this.memo.set(memoKey, result);
    return result;
  }

  /** Convert camelCase domain → UPPER_SNAKE_CASE for the JSON path lookup. */
  private static toUpperSnake(camel: string): string {
    return camel.replaceAll(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
  }

  /** Three-tier lookup. See class JSDoc. */
  private lookup(value: string, domain?: string): string {
    // 1) i18n — PORTAL.ENUM.<GROUP>.<VALUE>
    if (domain) {
      const group = EnumLabelPipe.toUpperSnake(domain);
      const path = `PORTAL.ENUM.${group}.${value}`;
      const translated = this.translate.instant(path);
      // ngx-translate returns the key path verbatim when no translation exists.
      if (typeof translated === 'string' && translated && translated !== path) {
        return translated;
      }
    }

    // 2) In-memory English LABELS
    if (domain) {
      const domainMap = EnumLabelPipe.LABELS[domain];
      if (domainMap?.[value]) return domainMap[value];
    }
    for (const map of Object.values(EnumLabelPipe.LABELS)) {
      if (map[value]) return map[value];
    }

    // 3) Prettify — UPPER_SNAKE_CASE → Title Case
    return value
      .split('_')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
      .join(' ');
  }
}
