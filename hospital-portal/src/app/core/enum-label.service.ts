import { Injectable, OnDestroy, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

/**
 * The enum-label vocabulary and its three-tier lookup, with no Angular
 * template machinery attached.
 *
 * {@link EnumLabelPipe} is the usual way to reach it and delegates here. The
 * service exists because two callers need the *same* label from TypeScript,
 * where a pipe is the wrong shape:
 *
 *  - `OrganizationListComponent` filters on the Type column's rendered label,
 *    so the search box matches what is on screen rather than the wire token
 *    behind it. It used to get there by listing `EnumLabelPipe` in the
 *    component's `providers` and injecting it — the only such construction in
 *    the portal, and one that quietly gave that instance its own
 *    `ChangeDetectorRef`, its own `onLangChange` subscription and a memo
 *    separate from the template's.
 *  - anything else that has to compare, sort or search on a label.
 *
 * Lookup order (see {@link lookup}) is unchanged, and so is the memo: the key
 * carries the language, so a cached French label can never be returned for
 * Spanish even before the `onLangChange` clear below runs.
 */
@Injectable({ providedIn: 'root' })
export class EnumLabelService implements OnDestroy {
  private readonly translate = inject(TranslateService);
  /** Memoised lookups: key = `${lang}|${domain ?? ''}|${value}`. */
  private readonly memo = new Map<string, string>();
  private readonly langSub: Subscription;
  private readonly translationSub: Subscription;

  constructor() {
    // Two events, for two different reasons.
    //
    // onLangChange: the memo key already carries the language, so this one is
    // about bounding memory rather than correctness — a stale entry is
    // unreachable, not wrong. Clearing keeps the map the size of one locale.
    //
    // onTranslationChange: this one IS correctness, and it matters more now
    // that the memo is a singleton. A bundle merged after first paint — a
    // lazily registered feature bundle, a retry after a failed loader fetch —
    // fires only this event, and the key does not change. Under the old
    // per-pipe memo a Title-Cased English fallback cached before the bundle
    // arrived died with that pipe instance; here it would be served to every
    // binding in the app for the rest of the session.
    this.langSub = this.translate.onLangChange.subscribe(() => this.memo.clear());
    this.translationSub = this.translate.onTranslationChange.subscribe(() => this.memo.clear());
  }

  ngOnDestroy(): void {
    this.langSub.unsubscribe();
    this.translationSub.unsubscribe();
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
    /* Tier 2 is a first-match scan over EVERY group below, not a per-domain
     * map, so trimming a group here changes what other domains render for
     * the same value. Trimming this one to the 8 EncounterType sends took
     * WALK_IN, PRE_OP, POST_OP and PRE_ADMISSION out of the pool entirely, and
     * changed what ROUTINE resolves to ("Routine Visit" -> "Routine", since it
     * is still defined under consultationUrgency, taskPriority and
     * referralUrgency). None is reachable — there are no domain-less enumLabel
     * calls, and no domain whose group omits them — so nothing changed on
     * screen. Check that again before the next trim. */
    encounterType: {
      CONSULTATION: 'Consultation',
      FOLLOW_UP: 'Follow-Up',
      EMERGENCY: 'Emergency',
      SURGERY: 'Surgery',
      LAB: 'Laboratory',
      OUTPATIENT: 'Outpatient',
      INPATIENT: 'Inpatient',
      TELEHEALTH: 'Telehealth',
    },

    /* ── Statuses (generic catch-all — used by many callers) ── */
    status: {
      SCHEDULED: 'Scheduled',
      CONFIRMED: 'Confirmed',
      IN_PROGRESS: 'In Progress',
      COMPLETED: 'Completed',
      NOT_STARTED: 'Not Started',
      CONFIRMED_UNDERSTANDING: 'Understood',
      NEEDS_CLARIFICATION: 'Needs Clarification',
      FEEDBACK_PROVIDED: 'Feedback Given',
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

    /* ── Phase 2 — Lab / admin / referrals / signatures ───── */
    labOrderStatus: {
      ORDERED: 'Ordered',
      PENDING: 'Pending',
      COLLECTED: 'Collected',
      RECEIVED: 'Received',
      IN_PROGRESS: 'In Progress',
      RESULTED: 'Resulted',
      VERIFIED: 'Verified',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
    },
    abnormalFlag: {
      NORMAL: 'Normal',
      ABNORMAL: 'Abnormal',
      CRITICAL: 'Critical',
    },
    validationStudyType: {
      PRECISION: 'Precision',
      ACCURACY: 'Accuracy',
      REFERENCE_RANGE: 'Reference Range',
      METHOD_COMPARISON: 'Method Comparison',
      INTERFERENCE: 'Interference',
      CARRYOVER: 'Carryover',
      LINEARITY: 'Linearity',
    },
    admissionStatus: {
      PENDING: 'Pending',
      ACTIVE: 'Active',
      ON_LEAVE: 'On Leave',
      AWAITING_DISCHARGE: 'Awaiting Discharge',
      DISCHARGED: 'Discharged',
      CANCELLED: 'Cancelled',
      TRANSFERRED: 'Transferred',
      DECEASED: 'Deceased',
    },
    bedStatus: {
      AVAILABLE: 'Available',
      OCCUPIED: 'Occupied',
      RESERVED: 'Reserved',
      MAINTENANCE: 'Maintenance',
      OUT_OF_SERVICE: 'Out of Service',
    },
    wardType: {
      GENERAL: 'General',
      SURGICAL: 'Surgical',
      MATERNITY: 'Maternity',
      PEDIATRIC: 'Pediatric',
      ICU: 'ICU',
      CCU: 'CCU',
      NICU: 'NICU',
      PSYCHIATRIC: 'Psychiatric',
      ISOLATION: 'Isolation',
      PRIVATE: 'Private',
      SEMI_PRIVATE: 'Semi-Private',
      EMERGENCY: 'Emergency',
      RECOVERY: 'Recovery',
    },
    /* Isolation precautions (bed board). The prettifier would render these
     * correctly in English, but without a group the badges can never be
     * translated, so give them a real i18n path. */
    isolationPrecautionType: {
      CONTACT: 'Contact',
      DROPLET: 'Droplet',
      AIRBORNE: 'Airborne',
      PROTECTIVE: 'Protective',
    },
    bloodProductType: {
      WHOLE_BLOOD: 'Whole Blood',
      PACKED_RED_CELLS: 'Packed Red Cells',
      FRESH_FROZEN_PLASMA: 'Fresh Frozen Plasma',
      PLATELETS: 'Platelets',
      CRYOPRECIPITATE: 'Cryoprecipitate',
    },
    /* Acronym modalities (CT, MRI, PET, XRAY, DEXA) are mangled by the
     * Title-Case prettifier ("Ct", "Mri"), so they need explicit labels. */
    imagingModality: {
      XRAY: 'X-Ray',
      CT: 'CT',
      MRI: 'MRI',
      ULTRASOUND: 'Ultrasound',
      MAMMOGRAPHY: 'Mammography',
      FLUOROSCOPY: 'Fluoroscopy',
      PET: 'PET',
      NUCLEAR_MEDICINE: 'Nuclear Medicine',
      INTERVENTIONAL_RADIOLOGY: 'Interventional Radiology',
      DEXA: 'DEXA',
      OTHER: 'Other',
    },
    dispenseStatus: {
      PENDING: 'Pending',
      COMPLETED: 'Completed',
      PARTIAL: 'Partial',
      CANCELLED: 'Cancelled',
    },
    imagingOrderStatus: {
      DRAFT: 'Draft',
      ORDERED: 'Ordered',
      PENDING_AUTHORIZATION: 'Pending Authorization',
      SCHEDULED: 'Scheduled',
      IN_PROGRESS: 'In Progress',
      COMPLETED: 'Completed',
      RESULTS_AVAILABLE: 'Results Available',
      CANCELLED: 'Cancelled',
    },
    imagingReportStatus: {
      DRAFT: 'Draft',
      PRELIMINARY: 'Preliminary',
      FINAL: 'Final',
      ADDENDUM: 'Addendum',
      CORRECTED: 'Corrected',
      AMENDED: 'Amended',
      CANCELLED: 'Cancelled',
      ERROR: 'Error',
    },
    treatmentPlanStatus: {
      DRAFT: 'Draft',
      IN_REVIEW: 'In Review',
      REVISIONS_REQUIRED: 'Revisions Required',
      APPROVED: 'Approved',
      ARCHIVED: 'Archived',
      CANCELLED: 'Cancelled',
    },
    /* TreatmentPlanTaskStatus is a different enum than TreatmentPlanStatus —
     * follow-up items inside a plan use this 4-value workflow. Adding a
     * dedicated group avoids the prettifier-fallthrough that Copilot flagged
     * on PR #262 (PENDING / IN_PROGRESS aren't in TREATMENT_PLAN_STATUS). */
    treatmentPlanTaskStatus: {
      PENDING: 'Pending',
      IN_PROGRESS: 'In Progress',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
    },
    referralStatus: {
      DRAFT: 'Draft',
      SUBMITTED: 'Submitted',
      ACKNOWLEDGED: 'Acknowledged',
      SCHEDULED: 'Scheduled',
      IN_PROGRESS: 'In Progress',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
      REJECTED: 'Rejected',
      EXPIRED: 'Expired',
    },
    referralUrgency: {
      ROUTINE: 'Routine',
      PRIORITY: 'Priority',
      URGENT: 'Urgent',
      EMERGENCY: 'Emergency',
    },
    referralType: {
      CONSULTATION: 'Consultation',
      SHARED_CARE: 'Shared Care',
      TRANSFER_OF_CARE: 'Transfer of Care',
      EMERGENCY_TRANSFER: 'Emergency Transfer',
    },
    mtmReviewStatus: {
      DRAFT: 'Draft',
      COMPLETED: 'Completed',
      REFERRED: 'Referred',
    },
    signatureStatus: {
      PENDING: 'Pending',
      SIGNED: 'Signed',
      REVOKED: 'Revoked',
      EXPIRED: 'Expired',
      INVALID: 'Invalid',
    },
    invoiceStatus: {
      DRAFT: 'Draft',
      SENT: 'Sent',
      PARTIALLY_PAID: 'Partially Paid',
      PAID: 'Paid',
      CANCELLED: 'Cancelled',
    },
    allergyVerificationStatus: {
      UNCONFIRMED: 'Unconfirmed',
      PROVISIONAL: 'Provisional',
      CONFIRMED: 'Confirmed',
      REFUTED: 'Refuted',
      ENTERED_IN_ERROR: 'Entered in Error',
    },
    dischargeDisposition: {
      HOME: 'Home',
      HOME_WITH_HOME_HEALTH: 'Home with Home Health',
      SKILLED_NURSING_FACILITY: 'Skilled Nursing Facility',
      LONG_TERM_CARE_FACILITY: 'Long-term Care Facility',
      REHABILITATION_FACILITY: 'Rehabilitation Facility',
      HOSPICE_HOME: 'Hospice - Home',
      HOSPICE_FACILITY: 'Hospice - Facility',
      PSYCHIATRIC_FACILITY: 'Psychiatric Facility',
      AGAINST_MEDICAL_ADVICE: 'Against Medical Advice',
      LEFT_WITHOUT_BEING_SEEN: 'Left Without Being Seen',
      TRANSFERRED_TO_ANOTHER_HOSPITAL: 'Transfer to Another Hospital',
      EXPIRED: 'Deceased',
      OTHER: 'Other',
    },

    /* ── Phase 3 — Audit / internal ───────────────────────── */
    auditStatus: {
      SUCCESS: 'Success',
      FAILURE: 'Failure',
      PENDING: 'Pending',
      IN_PROGRESS: 'In Progress',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
      REJECTED: 'Rejected',
      APPROVED: 'Approved',
      ERROR: 'Error',
    },
    actorType: {
      USER: 'User',
      SYSTEM: 'System',
    },
    /* All 130 AuditEventType values are keyed under PORTAL.ENUM.AUDIT_EVENT_TYPE
     * and checked by scripts/check-i18n-enum-coverage.mjs, so this block is a
     * safety net for a missing bundle, not the translation of record. */
    auditEventType: {
      LOGIN: 'Login',
      LOGOUT: 'Logout',
      LOGIN_FAILURE: 'Login Failure',
      PASSWORD_CHANGED: 'Password Changed',
      MFA_CHALLENGE: 'MFA Challenge',
      MFA_FAILURE: 'MFA Failure',
      MFA_VERIFIED: 'MFA Verified',
      ACCOUNT_LOCKED: 'Account Locked',
      ACCOUNT_UNLOCKED: 'Account Unlocked',
      USER_CREATE: 'User Created',
      USER_UPDATE: 'User Updated',
      USER_DELETE: 'User Deleted',
      USER_DISABLE: 'User Disabled',
      USER_ENABLE: 'User Enabled',
      ROLE_ASSIGNED: 'Role Assigned',
      ROLE_REVOKED: 'Role Revoked',
      PATIENT_ACCESS: 'Patient Accessed',
      PATIENT_EXPORT: 'Patient Exported',
      BREAK_GLASS_ACCESS: 'Break-Glass Access',
      CONSENT_GRANTED: 'Consent Granted',
      CONSENT_REVOKED: 'Consent Revoked',
      APPOINTMENT_CREATED: 'Appointment Created',
      PRESCRIPTION_CREATED: 'Prescription Created',
      LAB_ORDER_CREATED: 'Lab Order Created',
      IMAGING_ORDER_CREATED: 'Imaging Order Created',
      IMPERSONATION_STARTED: 'Impersonation Started',
      IMPERSONATION_ENDED: 'Impersonation Ended',
      DATA_EXPORT: 'Data Export',
      OTHER: 'Other',
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

    /* ── Job titles ───────────────────────────────────────── */
    /* Declaration order, which is JobTitle.java's own — the legacy
     * LAB_SCIENTIST / HOSPITAL_ADMIN pairs sit beside the canonical
     * spellings they duplicate. */
    jobTitle: {
      DOCTOR: 'Doctor',
      PHYSICIAN: 'Physician',
      NURSE_PRACTITIONER: 'Nurse Practitioner',
      NURSE: 'Nurse',
      MIDWIFE: 'Midwife',
      HOSPITAL_ADMIN: 'Hospital Admin',
      PATIENT: 'Patient',
      VISITOR: 'Visitor',
      SUPER_ADMIN: 'Super Admin',
      ADMINISTRATIVE_STAFF: 'Administrative Staff',
      TECHNICIAN: 'Technician',
      PHARMACIST: 'Pharmacist',
      LAB_TECHNICIAN: 'Lab Technician',
      LAB_SCIENTIST: 'Lab Scientist',
      LAB_DIRECTOR: 'Lab Director',
      QUALITY_MANAGER: 'Quality Manager',
      RECEPTIONIST: 'Receptionist',
      SURGEON: 'Surgeon',
      HOSPITAL_ADMINISTRATOR: 'Hospital Administrator',
      LABORATORY_SCIENTIST: 'Laboratory Scientist',
      RADIOLOGIST: 'Radiologist',
      ANESTHESIOLOGIST: 'Anesthesiologist',
      PHYSIOTHERAPIST: 'Physiotherapist',
      PSYCHOLOGIST: 'Psychologist',
      SOCIAL_WORKER: 'Social Worker',
      BILLING_SPECIALIST: 'Billing Specialist',
      IT_SUPPORT: 'IT Support',
      CLEANING_STAFF: 'Cleaning Staff',
      SECURITY_PERSONNEL: 'Security Personnel',
      HUMAN_RESOURCES: 'Human Resources',
      ADMINISTRATIVE_ASSISTANT: 'Administrative Assistant',
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

    /* ── Patient education categories ─────────────────────── */
    educationCategory: {
      PRENATAL_CARE: 'Prenatal Care',
      NUTRITION: 'Nutrition',
      EXERCISE: 'Exercise',
      LABOR_AND_DELIVERY: 'Labor & Delivery',
      POSTPARTUM_CARE: 'Postpartum Care',
      BREASTFEEDING: 'Breastfeeding',
      NEWBORN_CARE: 'Newborn Care',
      MENTAL_HEALTH: 'Mental Health',
      WARNING_SIGNS: 'Warning Signs',
      BIRTH_PLAN: 'Birth Plan',
      PRENATAL_VITAMINS: 'Prenatal Vitamins',
      MANAGING_DISCOMFORT: 'Managing Discomfort',
      HIGH_RISK_PREGNANCY: 'High-Risk Pregnancy',
      ULTRASOUND_SCANS: 'Ultrasound & Scans',
      GENETIC_SCREENING: 'Genetic Screening',
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
      VIEW_APPOINTMENTS: 'Appointments',
      VIEW_MEDICATIONS: 'Medications',
      VIEW_LAB_RESULTS: 'Lab Results',
      VIEW_BILLING: 'Billing',
      VIEW_RECORDS: 'Health Records',
      'VIEW_APPOINTMENTS,VIEW_MEDICATIONS,VIEW_LAB_RESULTS':
        'Appointments, Medications & Lab Results',
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

  /** The locale-aware label for one raw enum value, or `''` for a blank one. */
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
      const group = EnumLabelService.toUpperSnake(domain);
      const path = `PORTAL.ENUM.${group}.${value}`;
      const translated = this.translate.instant(path);
      // ngx-translate returns the key path verbatim when no translation exists.
      if (typeof translated === 'string' && translated && translated !== path) {
        return translated;
      }
    }

    // 2) In-memory English LABELS
    if (domain) {
      const domainMap = EnumLabelService.LABELS[domain];
      if (domainMap?.[value]) return domainMap[value];
    }
    // Then every other group. 24 call sites pass the generic 'status' domain
    // for values that only encounterStatus / labOrderStatus / ... define, and
    // their curated casing ("Ready for Discharge") lives in those siblings.
    // Restricting this scan to domain-less calls dropped them to the Title-Case
    // prettifier. The scan's known hazard — EXPIRED is "Deceased" under
    // dischargeDisposition and "Expired" under status, decided by declaration
    // order — is real but pre-existing; the fix for it is the right domain at
    // those call sites, not a narrower fallback here.
    for (const map of Object.values(EnumLabelService.LABELS)) {
      if (map[value]) return map[value];
    }

    // 3) Prettify — UPPER_SNAKE_CASE → Title Case
    return value
      .split('_')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
      .join(' ');
  }
}
