import { Injectable, OnDestroy, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

/** A UUID anywhere in a string (QA D672). */
const UUID_IN_TEXT = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

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
    /* ── Staff specialisations (users/user-list.ts) ───────── */
    specialization: {
      CARDIOLOGY: 'Cardiology',
      CLINICAL_PHARMACY: 'Clinical Pharmacy',
      DERMATOLOGY: 'Dermatology',
      GENERAL_NURSE: 'General Nursing',
      GENERAL_PRACTICE: 'General Practice',
      GYNECOLOGY: 'Gynecology',
      HOSPITAL_PHARMACY: 'Hospital Pharmacy',
      ICU_NURSE: 'ICU Nursing',
      LAB_TECHNICIAN: 'Laboratory Technician',
      MICROBIOLOGY: 'Microbiology',
      NEUROLOGY: 'Neurology',
      ONCOLOGY: 'Oncology',
      ORTHOPEDICS: 'Orthopedics',
      OTHER: 'Other',
      PATHOLOGY: 'Pathology',
      PEDIATRICS: 'Pediatrics',
      PEDIATRIC_NURSE: 'Pediatric Nursing',
      PSYCHIATRY: 'Psychiatry',
      RADIOLOGY: 'Radiology',
      SURGICAL_NURSE: 'Surgical Nursing',
    },
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
      OTHER: 'Other',
      DELIVERY: 'Delivery room',
      PATIENT_REPORTED: 'Reported by the patient',
      MANUAL: 'Manual entry',
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
      LEFT_WITHOUT_BEING_SEEN: 'Left without being seen',
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
    /** QA D963: LabOrderServiceImpl.LAB_PRIORITIES. */
    labOrderPriority: {
      ROUTINE: 'Routine',
      URGENT: 'Urgent',
      STAT: 'STAT (immediate)',
    },
    /** QA D963: LabOrderChannel. */
    labOrderChannel: {
      ELECTRONIC: 'Electronic',
      PORTAL: 'Portal',
      PHONE: 'Phone',
      FAX: 'Fax',
      EMAIL: 'Email',
      WRITTEN: 'Written',
      WALK_IN: 'Walk-In',
      OTHER: 'Other',
    },
    /** QA D963 / D962: LabSpecimenStatus. */
    labSpecimenStatus: {
      PENDING: 'Pending',
      COLLECTED: 'Collected',
      IN_TRANSIT: 'In transit',
      RECEIVED: 'Received',
      PROCESSING: 'Processing',
      COMPLETED: 'Completed',
      REJECTED: 'Rejected',
    },
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
      ENTERED_IN_ERROR: 'Entered in error',
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
      PENDING: 'Ready for collection',
      COMPLETED: 'Completed',
      PARTIAL: 'Partial',
      CANCELLED: 'Cancelled',
    },

    /* ── Stock-out routing — the prescriber's fill history reads both (G11) ── */
    routingType: {
      PARTNER: 'Partner pharmacy',
      PRINT: 'Printed for the patient',
      BACKORDER: 'Back order',
    },
    routingDecisionStatus: {
      PENDING: 'Pending',
      ACCEPTED: 'Accepted',
      REJECTED: 'Rejected',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
    },

    /* ── Community-pharmacy SMS dispatch (G7). Two String columns rather
       than enums — scripts/i18n-enum-domains.json names what writes them. ── */
    prescriptionDispatchChannel: {
      SMS: 'SMS',
    },
    prescriptionDispatchStatus: {
      SENT: 'Sent',
      FAILED: 'Failed',
    },
    fulfillmentMode: {
      OUTPATIENT_HOSPITAL: 'Outpatient hospital',
      INPATIENT_DISPENSARY: 'Inpatient dispensary',
      PARTNER_PHARMACY: 'Partner pharmacy',
      COMMUNITY: 'Community pharmacy',
      MAIL_ORDER: 'Mail order',
      MOBILE_OUTREACH: 'Mobile unit',
    },
    imagingLaterality: {
      LEFT: 'Left',
      RIGHT: 'Right',
      BILATERAL: 'Bilateral',
      MIDLINE: 'Midline',
      NOT_APPLICABLE: 'Not applicable',
    },
    imagingOrderPriority: {
      ROUTINE: 'Routine',
      URGENT: 'Urgent',
      STAT: 'STAT (immediate)',
    },
    admissionType: {
      EMERGENCY: 'Emergency',
      ELECTIVE: 'Elective',
      URGENT: 'Urgent',
      NEWBORN: 'Newborn',
      TRANSFER: 'Transfer',
      OBSERVATION: 'Observation',
      DAY_CASE: 'Day case',
      LABOR_DELIVERY: 'Labour and delivery',
      PSYCHIATRIC: 'Psychiatric',
    },
    stockTransactionType: {
      RECEIPT: 'Receipt',
      DISPENSE: 'Dispense',
      ADJUSTMENT: 'Adjustment',
      TRANSFER: 'Transfer',
      RETURN: 'Return',
    },
    pharmacyType: {
      HOSPITAL_DISPENSARY: 'Hospital dispensary',
      COMMUNITY_PHARMACY: 'Community pharmacy',
      PARTNER_PHARMACY: 'Partner pharmacy',
    },
    procedureUrgency: {
      ROUTINE: 'Routine',
      URGENT: 'Urgent',
      EMERGENT: 'Emergent',
      ELECTIVE: 'Elective',
    },
    labSeverityFlag: {
      NORMAL: 'Normal',
      LOW: 'Low',
      HIGH: 'High',
      CRITICAL: 'Critical',
      ABNORMAL: 'Abnormal',
      UNSPECIFIED: 'Not graded',
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
    invoiceItemCategory: {
      SERVICE: 'Service',
      MEDICATION: 'Medication',
      LAB_TEST: 'Lab Test',
      GENERAL: 'General',
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
      LAB_ORDER_UPDATED: 'Lab Order Updated',
      LAB_ORDER_CANCELLED: 'Lab Order Cancelled',
      LAB_ORDER_ENTERED_IN_ERROR: 'Lab Order Entered in Error',
      LAB_SPECIMEN_UPDATED: 'Lab Specimen Updated',
      LAB_RESULT_CREATED: 'Lab Result Created',
      LAB_RESULT_ACKNOWLEDGED: 'Lab Result Acknowledged',
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
      PROVIDER_ADMIN: 'Provider Administrator',
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

    /* Tier-2 nets for the domains that had none. Appended last on purpose:
     * the cross-group scan in lookup() returns the FIRST group that defines a
     * value, so a group added here can only fill a gap, never change the label
     * an earlier group already gives. scripts/lib/enum-labels-net.test.mjs
     * fails when a declared domain has no group. */
    /* ── Patient chart timeline categories (PatientServiceImpl CATEGORY_*) ── */
    timelineCategory: {
      ALLERGY: 'Allergy',
      ENCOUNTER: 'Encounter',
      IMAGING: 'Imaging',
      LAB_RESULT: 'Lab result',
      PRESCRIPTION: 'Prescription',
      PROCEDURE: 'Procedure',
    },

    /* ── Immunization status ───────────────────────────────── */
    immunizationStatus: {
      COMPLETED: 'Completed',
      DEFERRED: 'Deferred',
      ENTERED_IN_ERROR: 'Entered in Error',
      NOT_DONE: 'Not Done',
      REFUSED: 'Refused',
    },

    /* ── MAR task status ───────────────────────────────────── */
    marTaskStatus: {
      COMPLETED: 'Completed',
      DUE: 'Due',
      OVERDUE: 'Overdue',
    },

    /* ── Newborn alert types ───────────────────────────────── */
    newbornAlertType: {
      APGAR: 'Apgar',
      CARDIOVASCULAR: 'Cardiovascular',
      GENERAL: 'General',
      GLUCOSE: 'Glucose',
      OXYGENATION: 'Oxygenation',
      RESPIRATORY: 'Respiratory',
      THERMOREGULATION: 'Thermoregulation',
    },

    /* ── Notification types (Notification.type, a String column) ─── */
    notificationType: {
      APPOINTMENT_REMINDER: 'Appointment Reminder',
      BREAK_GLASS_OPT_OUT_OVERRIDE: 'Emergency access to your record',
      CRITICAL_IMAGING_FINDING: 'Critical Imaging Finding',
      CRITICAL_IMAGING_FINDING_ESCALATION: 'Critical Imaging Finding Escalation',
      CRITICAL_LAB_RESULT: 'Critical Lab Result',
      CRITICAL_LAB_RESULT_ESCALATION: 'Critical Lab Result Escalation',
      DISCHARGE_SUMMARY: 'Discharge Summary',
      LAB_ORDER_RECEIVED: 'Lab Order Received',
      LAB_RESULT: 'Lab result',
      LAB_SPECIMEN_REJECTED: 'Specimen rejected',
      LICENSE_EXPIRY: 'License Expiry',
      MEDICATION_REFILL: 'Medication Refill',
      NEWS2_ESCALATION: 'NEWS2 Escalation',
      OBSTETRIC_ALERT: 'Urgent Obstetric Alert',
      OBSTETRIC_ALERT_ESCALATION: 'Urgent Obstetric Alert Escalation',
      PHARMACY_EVENT: 'Pharmacy Update',
      POSITIVE_CULTURE_RESULT: 'Positive Culture Result',
      PRO_CRITICAL_ITEM: 'Critical PRO Item',
      PRO_CRITICAL_ITEM_ESCALATION: 'Critical PRO Item Escalation',
      PRO_SCREEN_POSITIVE: 'Positive PRO Screen',
      REORDER_ALERT: 'Reorder Alert',
    },

    /* ── Order source (snapshot drawer + nurse station board) ─── */
    orderSourceType: {
      IMAGING: 'Imaging',
      LAB: 'Lab',
      PROCEDURE: 'Procedure',
      VITALS: 'Vitals',
    },

    /* ── Provider facilities (D5) ─────────────────────────── */
    facilityType: {
      HOSPITAL: 'Hospital',
      PHARMACY: 'Pharmacy',
      LABORATORY: 'Laboratory',
    },
    hospitalLifecycleState: {
      ACTIVE: 'Active',
      SUSPENDED: 'Suspended',
      ARCHIVED: 'Archived',
      PENDING_PURGE: 'Purge scheduled',
      PURGED: 'Purged',
    },
    providerVerificationStatus: {
      SUBMITTED: 'Submitted',
      VERIFIED: 'Verified',
      REJECTED: 'Rejected',
      REVOKED: 'Revoked',
    },

    /* ── Organization types ────────────────────────────────── */
    organizationType: {
      ACADEMIC_CENTER: 'Academic Center',
      ACADEMIC_MEDICAL_CENTER: 'Academic Medical Center',
      AMBULATORY: 'Ambulatory Network',
      COMMUNITY_HOSPITAL: 'Community Hospital',
      GOVERNMENT_AGENCY: 'Government Agency',
      HEALTHCARE_NETWORK: 'Healthcare Network',
      HEALTH_SYSTEM: 'Health System',
      HOSPITAL: 'Hospital',
      HOSPITAL_CHAIN: 'Hospital Chain',
      LABORATORY: 'Laboratory',
      PEDIATRIC_SYSTEM: 'Pediatric System',
      PHARMACY: 'Pharmacy',
      PRIVATE_PRACTICE: 'Private Practice',
      REGIONAL_NETWORK: 'Regional Network',
      RESEARCH_INSTITUTION: 'Research Institution',
      SPECIALTY_GROUP: 'Specialty Group',
      SPECIALTY_NETWORK: 'Specialty Network',
    },

    /* ── Platform release status ───────────────────────────── */
    platformReleaseStatus: {
      CANCELLED: 'Cancelled',
      COMPLETED: 'Completed',
      IN_PROGRESS: 'In Progress',
      SCHEDULED: 'Scheduled',
    },

    /* ── Platform service status ───────────────────────────── */
    platformServiceStatus: {
      ACTIVE: 'Active',
      DECOMMISSIONED: 'Decommissioned',
      INACTIVE: 'Inactive',
      PENDING: 'Pending',
      PILOT: 'Pilot',
      UNKNOWN: 'Unknown',
    },

    /* ── Postpartum alert types ────────────────────────────── */
    postpartumAlertType: {
      EDUCATION: 'Education',
      FOLLOW_UP: 'Follow-Up',
      HEMORRHAGE: 'Hemorrhage',
      INFECTION: 'Infection',
      PAIN: 'Pain',
      PSYCHOSOCIAL: 'Psychosocial',
    },

    /* ── Procedure order status ────────────────────────────── */
    procedureOrderStatus: {
      CANCELLED: 'Cancelled',
      COMPLETED: 'Completed',
      IN_PROGRESS: 'In Progress',
      ORDERED: 'Ordered',
      POSTPONED: 'Postponed',
      PRE_OP_CLEARANCE_PENDING: 'Pre-Op Clearance Pending',
      READY_FOR_PROCEDURE: 'Ready for Procedure',
      SCHEDULED: 'Scheduled',
    },

    /* ── Nurse handoff direction (QA D235) ─────────────────── */
    handoffDirection: {
      BREAK_COVER: 'Break cover',
      PROCEDURE: 'To a procedure or exam',
      SHIFT_CHANGE: 'Shift change',
      WARD_TRANSFER: 'Ward transfer',
    },

    /* ── Vitals round type ─────────────────────────────────── */
    vitalTaskType: {
      FULL_SET: 'Full Set',
      NEWS2_HIGH: 'NEWS2 high: every 15 min',
      NEWS2_MEDIUM: 'NEWS2 medium: hourly',
      ROUTINE: 'Routine',
    },

    /* ── Staff scheduling and employment ──────────────────── */
    shiftType: {
      MORNING: 'Morning',
      AFTERNOON: 'Afternoon',
      EVENING: 'Evening',
      NIGHT: 'Night',
      ON_CALL: 'On-Call',
      FLEX: 'Flex',
    },
    leaveType: {
      VACATION: 'Vacation',
      SICK: 'Sick Leave',
      EMERGENCY: 'Emergency',
      TRAINING: 'Training',
      UNPAID: 'Unpaid Leave',
      OTHER: 'Other',
    },
    employmentType: {
      FULL_TIME: 'Full-Time',
      PART_TIME: 'Part-Time',
      CONTRACT: 'Contract',
      LOCUM: 'Locum',
      INTERN: 'Intern',
    },
    specimenType: {
      BLOOD: 'Blood',
      WHOLE_BLOOD: 'Whole blood',
      SERUM: 'Serum',
      URINE: 'Urine',
      STOOL: 'Stool',
      CSF: 'Cerebrospinal fluid',
      SPUTUM: 'Sputum',
      SWAB: 'Swab',
      TISSUE: 'Tissue',
      OTHER: 'Other',
    },
    platformServiceType: {
      EHR: 'Electronic health record',
      BILLING: 'Billing',
      INVENTORY: 'Inventory',
      LIMS: 'Laboratory information system',
      ANALYTICS: 'Analytics',
      CLINICAL_ANALYTICS: 'Clinical analytics',
      REMOTE_MONITORING: 'Remote monitoring',
      PEDIATRIC_MESSAGING: 'Paediatric messaging',
      ORTHO_IMAGING: 'Orthopaedic imaging',
      RESP_TELEMED: 'Respiratory telemedicine',
    },
    // QA D871: the ADT intake form's default acuity.
    acuityLevel: {
      LEVEL_1_MINIMAL: 'Level 1 - minimal',
      LEVEL_2_MODERATE: 'Level 2 - moderate',
      LEVEL_3_MAJOR: 'Level 3 - major',
      LEVEL_4_SEVERE: 'Level 4 - severe',
      LEVEL_5_CRITICAL: 'Level 5 - critical',
    },
    // QA D528/D663: tier-2 English for the domains #928 declared.
    profileType: {
      STAFF: 'Staff',
      PATIENT: 'Patient',
    },
    medicationCategory: {
      ANALGESIC: 'Analgesic',
      ANTIBIOTIC: 'Antibiotic',
      ANTIMALARIAL: 'Antimalarial',
      ANTIPYRETIC: 'Antipyretic',
      ANTI_INFLAMMATORY: 'Anti-inflammatory',
      ANTIHYPERTENSIVE: 'Antihypertensive',
      ANTIDIABETIC: 'Antidiabetic',
      ANTIRETROVIRAL: 'Antiretroviral',
      ANTIFUNGAL: 'Antifungal',
      ANTIPARASITIC: 'Antiparasitic',
      ANTIHISTAMINE: 'Antihistamine',
      ANTICOAGULANT: 'Anticoagulant',
      VITAMIN: 'Vitamin',
      VACCINE: 'Vaccine',
      OTHER: 'Other',
    },
    trackerAcuity: {
      'ESI-1': 'ESI 1 - Resuscitation',
      'ESI-2': 'ESI 2 - Emergent',
      'ESI-3': 'ESI 3 - Urgent',
      'ESI-4': 'ESI 4 - Less urgent',
      'ESI-5': 'ESI 5 - Non-urgent',
      EMERGENT: 'Emergent',
      URGENT: 'Urgent',
      ROUTINE: 'Routine',
      LOW: 'Low',
    },
  };

  /**
   * QA D528/D619: an audit row names its entity as a token — `BILLING_INVOICE`
   * on a write audit, `BillingInvoice` from older emitters. Both become the
   * keyed AUDIT_ENTITY_TYPE label. Anything that is not token-shaped (a
   * resource someone named) is returned as it is; `'Unknown Resource'` and a
   * blank are `null`, so a caller can hide the separator.
   */
  auditEntityLabel(raw: string | null | undefined): string | null {
    const name = raw?.trim();
    if (!name || name === 'Unknown Resource') return null;
    // QA D672: several writers record the row's id as its resource name
    // ("19ffd52b-…", "Patient 19ffd52b-…"); an id is not a label.
    if (UUID_IN_TEXT.test(name)) return null;
    const upperSnake = /^[A-Z][A-Z0-9_]*$/.test(name);
    const pascal = /^(?:[A-Z][a-z0-9]+)+$/.test(name);
    if (!upperSnake && !pascal) return name;
    const code = name.replaceAll(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
    return this.transform(code, 'auditEntityType');
  }

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

  /**
   * QA D908: the label defined for the value — in the locale files or the
   * curated English map — or `null` when there is none, never the Title-Case
   * prettification {@link transform} falls back to. A caller that keeps an
   * unlabelled token verbatim asks this instead of comparing the label with
   * the prettified code: "Activate" is ACTIVATE's real English label and
   * reads exactly like the prettified code, so that comparison showed the
   * raw token to English readers.
   */
  knownLabel(value: string | null | undefined, domain?: string): string | null {
    if (!value) return null;
    const lang = this.translate.getCurrentLang() || this.translate.getFallbackLang() || 'en';
    // '?' cannot start a domain name, so this never collides with the other keys.
    const memoKey = `${lang}|?${domain ?? ''}|${value}`;
    const cached = this.memo.get(memoKey);
    if (cached !== undefined) return cached || null;
    const result = this.lookupKnown(value, domain);
    this.memo.set(memoKey, result ?? '');
    return result;
  }

  /**
   * QA D836: the label for a FREE-TEXT value that may also be one of the
   * domain's curated codes — a lab sample type is typed by the lab ("EDTA",
   * "LCR", "Sang total (EDTA)") but older rows carry a code ("SERUM").
   * The domain's translated or curated label wins when the value is one of
   * its codes; anything else is shown exactly as typed. Never prettified:
   * a one-word abbreviation looks like a code, and title-casing it read
   * "Edta" / "Lcr". Other enum groups are not scanned either — a typed word
   * that happens to be a status code elsewhere is still the lab's text.
   */
  labelOrAsTyped(value: string | null | undefined, domain: string): string {
    if (!value) return '';
    const lang = this.translate.getCurrentLang() || this.translate.getFallbackLang() || 'en';
    // '=' cannot start a domain name, so this never collides with transform()'s keys.
    const memoKey = `${lang}|=${domain}|${value}`;
    const cached = this.memo.get(memoKey);
    if (cached !== undefined) return cached;

    const path = `PORTAL.ENUM.${EnumLabelService.toUpperSnake(domain)}.${value}`;
    const translated: unknown = this.translate.instant(path);
    let result = value;
    if (typeof translated === 'string' && translated && translated !== path) {
      result = translated;
    } else {
      const curated = EnumLabelService.LABELS[domain]?.[value];
      if (curated) result = curated;
    }
    this.memo.set(memoKey, result);
    return result;
  }

  /** Convert camelCase domain → UPPER_SNAKE_CASE for the JSON path lookup. */
  private static toUpperSnake(camel: string): string {
    return camel.replaceAll(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
  }

  private static readonly ENUM_ROOT = ['PORTAL', 'ENUM'].join('.');

  /** QA D836: what an enum code looks like (letters, digits, underscores, one word). */
  private static readonly CODE_LIKE = /^\w+$/;

  /** The value's label in the generic STATUS group, else in the first group that has it. */
  private lookupAnyGroup(value: string): string | null {
    // The whole subtree, not a leaf key: held in a constant so the
    // referenced-keys gate does not read it as a missing string.
    const groups: unknown = this.translate.instant(EnumLabelService.ENUM_ROOT);
    if (!groups || typeof groups !== 'object') return null;
    const byGroup = groups as Record<string, unknown>;
    const ordered = ['STATUS', ...Object.keys(byGroup).filter((g) => g !== 'STATUS')];
    for (const group of ordered) {
      const entries = byGroup[group];
      if (entries && typeof entries === 'object') {
        const label = (entries as Record<string, unknown>)[value];
        if (typeof label === 'string' && label) return label;
      }
    }
    return null;
  }

  /** Three-tier lookup. See class JSDoc. */
  private lookup(value: string, domain?: string): string {
    const known = this.lookupKnown(value, domain);
    if (known) return known;

    // 3) Prettify — UPPER_SNAKE_CASE → Title Case. QA D836: only a code is
    // prettified. Free text someone typed ("Sang total (EDTA)", a lab sample
    // type) is shown as typed: title-casing it read "Sang total (edta)".
    if (!EnumLabelService.CODE_LIKE.test(value)) {
      return value;
    }
    return value
      .split('_')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
      .join(' ');
  }

  /** Tiers 1 and 2 of {@link lookup}: a defined label, or `null`. */
  private lookupKnown(value: string, domain?: string): string | null {
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

    // 1b) i18n in any other group. A domain with no locale group of its own
    // (or a domain-less call) used to drop straight to the English LABELS, so
    // a French page read "Draft" although PORTAL.ENUM.STATUS.DRAFT exists.
    const translatedElsewhere = this.lookupAnyGroup(value);
    if (translatedElsewhere) return translatedElsewhere;

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
    return null;
  }
}
