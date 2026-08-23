import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

import { AuthService } from '../auth/auth.service';

interface DashboardConfigResponse {
  mergedPermissions?: string[] | null;
}

/**
 * Backend permission names (DashboardConfigService vocabulary) that gate the
 * same UI features as a differently-named frontend permission. Grants from
 * the backend are expanded through this map so nav gating keeps working even
 * though the two vocabularies drifted apart historically.
 */
const BACKEND_PERMISSION_ALIASES: Record<string, string[]> = {
  'View Lab Orders': ['View Lab'],
  'View Lab Results': ['View Lab'],
  'View Schedules': ['View Staff Schedules'],
  'Administer Medication': ['Administer Medications'],
};

@Injectable({ providedIn: 'root' })
export class PermissionService {
  private readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);

  /**
   * Permissions granted by the backend for the signed-in user
   * (GET /me/dashboard-config → mergedPermissions). Null until loaded;
   * checks then fall back to the static role map below.
   */
  private readonly backendPermissions = signal<ReadonlySet<string> | null>(null);

  /**
   * Bumped by clear()/loadFromBackend() so a dashboard-config response that
   * lands AFTER a logout (or a newer reload) is discarded instead of
   * repopulating permissions for a session that no longer exists.
   */
  private loadGeneration = 0;

  /**
   * Static fallback only — the authoritative source is the backend
   * (loadFromBackend()). Kept so the UI still renders a sensible nav when the
   * dashboard-config call fails or has not resolved yet. Backend grants are
   * UNIONED with this map, so a role missing here still gets its server-side
   * permissions instead of a gutted sidebar.
   */
  private readonly ROLE_PERMISSIONS: Record<string, string[]> = {
    ROLE_SUPER_ADMIN: ['*'],
    ROLE_HOSPITAL_ADMIN: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'Create Appointments',
      'Cancel Appointments',
      'Reschedule Appointments',
      'View Patient Records',
      'Access All Patient Records',
      'Update Patient Records',
      'Register Patients',
      // Clinical
      'Create Encounters',
      'Admit Patients',
      'Create Prescriptions',
      'View Prescriptions',
      'Document Nursing Notes',
      'Access Nurse Station',
      'Request Imaging Studies',
      'View Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      // Consent & record sharing
      'View Record Sharing',
      'Manage Patient Consents',
      // Admin
      'View Staff',
      'Manage Staff',
      'View Staff Schedules',
      'Manage Staff Schedules',
      'View Departments',
      'Manage Departments',
      'View Roles',
      'Manage Roles',
      'View Audit Logs',
      'View Billing',
      'Manage Billing',
      'Create Invoice',
      'Record Payment',
      'Process Refund',
      'View Billing Reports',
      'Email Invoice',
      'Delete Invoice',
      'View Lab',
      'Order Lab Tests',
      'View Notifications',
      'View Hospitals',
      'Manage Hospitals',
    ],
    ROLE_DOCTOR: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Register Patients',
      // Clinical — gates nav items
      'Create Encounters',
      'Admit Patients',
      'Create Prescriptions',
      'View Prescriptions',
      'Document Nursing Notes',
      'Request Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      // Consent & record sharing
      'View Record Sharing',
      'Manage Patient Consents',
      // Lab / Lab orders
      'Order Lab Tests',
      'View Lab',
      // Scheduling
      'View Staff Schedules',
      'Manage Staff Schedules',
      // Billing (billable status awareness)
      'View Billing Summary',
      // Notifications
      'View Notifications',
    ],
    // ROLE_PHYSICIAN and ROLE_SURGEON inherit the same clinical permissions as ROLE_DOCTOR
    ROLE_PHYSICIAN: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Register Patients',
      'Create Encounters',
      'Admit Patients',
      'Create Prescriptions',
      'View Prescriptions',
      'Document Nursing Notes',
      'Request Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      'View Record Sharing',
      'Manage Patient Consents',
      'Order Lab Tests',
      'View Lab',
      'View Staff Schedules',
      'Manage Staff Schedules',
      'View Notifications',
    ],
    ROLE_SURGEON: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Register Patients',
      'Create Encounters',
      'Admit Patients',
      'Create Prescriptions',
      'View Prescriptions',
      'Document Nursing Notes',
      'Request Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      'View Record Sharing',
      'Manage Patient Consents',
      'Order Lab Tests',
      'View Lab',
      'View Staff Schedules',
      'Manage Staff Schedules',
      'View Notifications',
    ],
    ROLE_NURSE: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Register Patients',
      // Clinical
      'Create Encounters',
      'Document Nursing Notes',
      'Access Nurse Station',
      'Create Prescriptions',
      'View Prescriptions',
      'Request Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      'Admit Patients',
      // Record sharing (read-only — cannot manage consents)
      'View Record Sharing',
      // Scheduling
      'View Staff Schedules',
      'Manage Staff Schedules',
      'Check-in Patients',
      // Vitals / meds
      'Update Vital Signs',
      'Administer Medications',
      'View Lab',
      // Notifications
      'View Notifications',
    ],
    ROLE_MIDWIFE: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Register Patients',
      // Clinical
      'Create Encounters',
      'Admit Patients',
      'Document Nursing Notes',
      'Access Nurse Station',
      'Create Prescriptions',
      'View Prescriptions',
      'Create Referrals',
      'Create Treatment Plans',
      'Request Imaging Studies',
      'View Imaging Studies',
      // Lab
      'View Lab',
      'Order Lab Tests',
      // Record sharing (read-only — cannot manage consents)
      'View Record Sharing',
      // Scheduling
      'View Staff Schedules',
      'Manage Staff Schedules',
      // Maternity-specific
      'Perform Prenatal Assessments',
      'Create Birth Plans',
      'Perform Postpartum Care',
      // Notifications
      'View Notifications',
    ],
    ROLE_RECEPTIONIST: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'Create Appointments',
      'Cancel Appointments',
      'Reschedule Appointments',
      'Check-in Patients',
      'Register Patients',
      'View Patient Records',
      'Update Patient Contact Info',
      // Staff lookup for scheduling
      'View Staff',
      'View Staff Schedules',
      'View Departments',
      // Billing (co-pay collection)
      'View Billing',
      'View Billing Summary',
      // Notifications
      'View Notifications',
    ],
    ROLE_LAB_SCIENTIST: [
      'View Dashboard',
      'View Lab',
      'Process Lab Tests',
      'View Patient Records',
      'View Notifications',
    ],
    // Backend Role enum uses ROLE_LAB_TECHNICIAN — alias to same permissions
    ROLE_LAB_TECHNICIAN: [
      'View Dashboard',
      'View Lab',
      'Process Lab Tests',
      'View Patient Records',
      'View Notifications',
    ],
    ROLE_LAB_MANAGER: [
      'View Dashboard',
      'View Lab',
      'Process Lab Tests',
      'View Patient Records',
      'View Staff',
      'View Staff Schedules',
      'View Departments',
      'View Notifications',
    ],
    ROLE_LAB_DIRECTOR: [
      'View Dashboard',
      'View Lab',
      'Process Lab Tests',
      'View Patient Records',
      'View Staff',
      'View Staff Schedules',
      'View Departments',
      'View Audit Logs',
      'View Notifications',
      'Manage Patient Consents',
    ],
    ROLE_QUALITY_MANAGER: [
      'View Dashboard',
      'View Lab',
      'Process Lab Tests',
      'View Patient Records',
      'View Staff',
      'View Staff Schedules',
      'View Departments',
      'View Audit Logs',
      'View Notifications',
      'Manage Patient Consents',
    ],
    ROLE_PHARMACIST: [
      'View Dashboard',
      'View Prescriptions',
      'Dispense Medications',
      'Verify Dispense',
      'View Patient Records',
      'View Notifications',
      // Medication catalog
      'View Medication Catalog',
      'Manage Medication Catalog',
      // Pharmacy registry
      'View Pharmacy Registry',
      // Inventory + stock
      'View Inventory',
      'Manage Inventory',
      'Receive Goods',
      'Adjust Stock',
      'Route Stock',
      // Pharmacy financials (read + checkout)
      'View Pharmacy Checkout',
      'View Pharmacy Claims',
    ],
    // ── Pharmacy sub-roles (backend: SecurityConstants) ─────────────
    ROLE_STORE_MANAGER: [
      'View Dashboard',
      'View Medication Catalog',
      'View Pharmacy Registry',
      'View Inventory',
      'Manage Inventory',
      'Receive Goods',
      'Adjust Stock',
      'View Notifications',
    ],
    ROLE_INVENTORY_CLERK: [
      'View Dashboard',
      'View Medication Catalog',
      'View Inventory',
      'Receive Goods',
      'Adjust Stock',
      'View Notifications',
    ],
    ROLE_PHARMACY_VERIFIER: [
      'View Dashboard',
      'View Prescriptions',
      'Dispense Medications',
      'Verify Dispense',
      'Route Stock',
      'View Patient Records',
      'View Notifications',
    ],
    ROLE_CLAIMS_REVIEWER: [
      'View Dashboard',
      'View Pharmacy Claims',
      'Manage Pharmacy Claims',
      'View Billing',
      'View Billing Summary',
      'View Notifications',
    ],
    ROLE_RADIOLOGIST: [
      'View Dashboard',
      'View Appointments',
      'View Patient Records',
      'View Imaging Studies',
      'Request Imaging Studies',
      'View Lab',
      'View Notifications',
    ],
    ROLE_PATIENT: [
      'View Dashboard',
      'View My Appointments',
      'Cancel My Appointments',
      'Reschedule My Appointments',
      'View My Medications',
      'Request Medication Refills',
      'View My Lab Results',
      'View My Vitals',
      'View My Immunizations',
      'View My Billing',
      'View Billing Summary',
      'View My Encounters',
      'View My Profile',
      'View My Health Summary',
      'View My Care Team',
      'View My Prescriptions',
      'View My Records',
      'View My Sharing',
      'View My Summaries',
      'View My Access Log',
      'Manage My Consents',
      'View Notifications',
    ],
    // ── Additional backend roles ──────────────────────────────────
    ROLE_ANESTHESIOLOGIST: [
      'View Dashboard',
      'View Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Create Encounters',
      'View Prescriptions',
      'View Lab',
      'View Imaging Studies',
      'View Staff Schedules',
      'View Notifications',
    ],
    // Finance roles deliberately do NOT get 'View Patient Records': the
    // backend denies them GET /patients at both the SecurityConfig matcher
    // and the controller @PreAuthorize, and billing DTOs already embed the
    // patient identity an invoice needs. Granting it here only produced a
    // dead Patients nav entry and a guaranteed 403 from the dashboard's
    // recent-patients widget.
    ROLE_BILLING_SPECIALIST: [
      'View Dashboard',
      'View Billing',
      'Manage Billing',
      'View Billing Summary',
      'Create Invoice',
      'Record Payment',
      'Process Refund',
      'Email Invoice',
      'View Notifications',
    ],
    ROLE_PHYSIOTHERAPIST: [
      'View Dashboard',
      'View Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Create Encounters',
      'Create Treatment Plans',
      'View Staff Schedules',
      'View Notifications',
    ],
    ROLE_STAFF: [
      'View Dashboard',
      'View Appointments',
      'View Staff Schedules',
      'View Notifications',
    ],
    ROLE_ACCOUNTANT: [
      'View Dashboard',
      'View Billing',
      'View Billing Summary',
      'Record Payment',
      'View Billing Reports',
      'View Notifications',
    ],
  };

  /**
   * Fetch the signed-in user's permission set from the backend. Fire-and-forget:
   * checks work off the static fallback map until the response lands, then the
   * backend grants (expanded through BACKEND_PERMISSION_ALIASES) are unioned in.
   * Reads go through a signal, so computed() consumers re-evaluate on arrival.
   */
  loadFromBackend(): void {
    this.backendPermissions.set(null);
    if (!this.auth.isAuthenticated()) return;
    const generation = ++this.loadGeneration;
    this.http.get<DashboardConfigResponse>('/me/dashboard-config').subscribe({
      next: (cfg) => {
        if (generation !== this.loadGeneration) return; // clear()/reload happened meanwhile
        // NOTE: the backend unions permissions across ALL of the user's active
        // assignments regardless of hospital, so grants here can be broader
        // than the active hospital's role. Route guards + backend authz remain
        // the enforcement layer; this only drives nav visibility.
        const merged = cfg?.mergedPermissions ?? [];
        if (merged.length === 0) return;
        const expanded = new Set<string>(merged);
        for (const name of merged) {
          for (const alias of BACKEND_PERMISSION_ALIASES[name] ?? []) {
            expanded.add(alias);
          }
        }
        this.backendPermissions.set(expanded);
      },
      error: () => {
        // Static fallback map remains in effect.
      },
    });
  }

  /** Drop backend-loaded permissions (call on logout / user switch). */
  clear(): void {
    this.loadGeneration++;
    this.backendPermissions.set(null);
  }

  hasPermission(permission: string): boolean {
    if (this.backendPermissions()?.has(permission)) {
      return true;
    }
    const roles = this.auth.getRoles();
    for (const role of roles) {
      const perms = this.ROLE_PERMISSIONS[role];
      if (perms?.includes('*') || perms?.includes(permission)) {
        return true;
      }
    }
    return false;
  }

  hasAnyPermission(...permissions: string[]): boolean {
    return permissions.some((p) => this.hasPermission(p));
  }

  hasAllPermissions(...permissions: string[]): boolean {
    return permissions.every((p) => this.hasPermission(p));
  }
}
