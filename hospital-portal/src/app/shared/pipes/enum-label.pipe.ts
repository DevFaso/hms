import { Pipe, PipeTransform } from '@angular/core';

/**
 * Converts raw UPPER_SNAKE_CASE enum values into human-readable labels.
 * Usage:  {{ 'BLOOD_PRESSURE' | enumLabel }}          → "Blood Pressure"
 *         {{ 'DOCTOR' | enumLabel:'role' }}            → "Doctor"
 *         {{ 'FOLLOW_UP' | enumLabel:'encounterType' }} → "Follow-Up"
 */
@Pipe({ name: 'enumLabel', standalone: true })
export class EnumLabelPipe implements PipeTransform {
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

    /* ── Statuses (shared across domains) ─────────────────── */
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

    // 1) If a specific domain is provided, look there first
    if (domain) {
      const domainMap = EnumLabelPipe.LABELS[domain];
      if (domainMap?.[value]) return domainMap[value];
    }

    // 2) Search all domains for an exact match
    for (const map of Object.values(EnumLabelPipe.LABELS)) {
      if (map[value]) return map[value];
    }

    // 3) Fallback: convert UPPER_SNAKE_CASE → Title Case
    return value
      .split('_')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
      .join(' ');
  }
}
