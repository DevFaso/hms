/**
 * QA D427/D527: the single map of the write actions the portal offers and the
 * roles the API admits for each. Every entry names the controller method whose
 * `@PreAuthorize` it mirrors; `ActionPermissionsMirrorTest` (hospital-core)
 * reads this file and fails when a list drifts from its annotation, so a
 * button is never offered to a role the endpoint refuses with a 403.
 *
 * Keep one entry per line in the `endpoint: 'Controller#method'` /
 * `roles: [...]` shape — the backend test parses it.
 *
 * Role-level only: a service-layer rule (the acting hospital, the author of a
 * draft) is checked by the page on top of the role gate.
 */
export interface ActionPermission {
  readonly endpoint: string;
  readonly roles: readonly string[];
}

export const ACTION_PERMISSIONS = {
  labOrderCreate: {
    endpoint: 'LabOrderController#createLabOrder',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_SUPER_ADMIN'],
  },
  labOrderUpdate: {
    endpoint: 'LabOrderController#updateLabOrder',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_SUPER_ADMIN'],
  },
  labOrderDelete: {
    endpoint: 'LabOrderController#deleteLabOrder',
    roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'],
  },
  imagingOrderCreate: {
    endpoint: 'ImagingOrderController#createOrder',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  imagingOrderUpdate: {
    endpoint: 'ImagingOrderController#updateOrder',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  imagingOrderStatus: {
    endpoint: 'ImagingOrderController#updateStatus',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR', 'ROLE_RADIOLOGIST'],
  },
  labSpecimenCollect: {
    endpoint: 'LabSpecimenController#collectSpecimen',
    roles: [
      'ROLE_LAB_TECHNICIAN',
      'ROLE_LAB_SCIENTIST',
      'ROLE_LAB_MANAGER',
      'ROLE_LAB_DIRECTOR',
      'ROLE_QUALITY_MANAGER',
      'ROLE_SUPER_ADMIN',
    ],
  },
  labSpecimenReceive: {
    endpoint: 'LabSpecimenController#receiveSpecimen',
    roles: [
      'ROLE_LAB_TECHNICIAN',
      'ROLE_LAB_SCIENTIST',
      'ROLE_LAB_MANAGER',
      'ROLE_LAB_DIRECTOR',
      'ROLE_QUALITY_MANAGER',
      'ROLE_SUPER_ADMIN',
    ],
  },
  imagingReportsByHospital: {
    endpoint: 'ImagingResultController#getReportsByHospital',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR', 'ROLE_RADIOLOGIST'],
  },
  imagingReportCreate: {
    endpoint: 'ImagingResultController#createReport',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_RADIOLOGIST', 'ROLE_DOCTOR'],
  },
  imagingReportStatus: {
    endpoint: 'ImagingResultController#updateReportStatus',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_RADIOLOGIST', 'ROLE_DOCTOR'],
  },
  imagingCriticalAck: {
    endpoint: 'ImagingResultController#acknowledgeCriticalResult',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR', 'ROLE_RADIOLOGIST'],
  },
  admissionCreate: {
    endpoint: 'AdmissionController#admitPatient',
    roles: ['ROLE_DOCTOR'],
  },
  admissionUpdate: {
    endpoint: 'AdmissionController#updateAdmission',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE'],
  },
  admissionApplyOrderSets: {
    endpoint: 'AdmissionController#applyOrderSets',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE'],
  },
  admissionAssignBed: {
    endpoint: 'AdmissionController#assignBed',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_SUPER_ADMIN'],
  },
  admissionUnassignBed: {
    endpoint: 'AdmissionController#unassignBed',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_SUPER_ADMIN'],
  },
  admissionDischarge: {
    endpoint: 'AdmissionController#dischargePatient',
    roles: ['ROLE_DOCTOR'],
  },
  admissionCancel: {
    endpoint: 'AdmissionController#cancelAdmission',
    roles: ['ROLE_DOCTOR'],
  },
  encounterCreate: {
    endpoint: 'EncounterController#create',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  encounterUpdate: {
    endpoint: 'EncounterController#update',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  encounterDelete: {
    endpoint: 'EncounterController#delete',
    roles: ['ROLE_SUPER_ADMIN'],
  },
  encounterStart: {
    endpoint: 'EncounterController#startEncounter',
    roles: ['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR'],
  },
  referralCreate: {
    endpoint: 'GeneralReferralController#createReferral',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  // QA D661: the receiving-side and cancel actions are physician-only.
  referralAcknowledge: {
    endpoint: 'GeneralReferralController#acknowledgeReferral',
    roles: ['ROLE_DOCTOR'],
  },
  referralSchedule: {
    endpoint: 'GeneralReferralController#scheduleReferral',
    roles: ['ROLE_DOCTOR'],
  },
  referralStart: {
    endpoint: 'GeneralReferralController#startReferral',
    roles: ['ROLE_DOCTOR'],
  },
  referralComplete: {
    endpoint: 'GeneralReferralController#completeReferral',
    roles: ['ROLE_DOCTOR'],
  },
  referralReject: {
    endpoint: 'GeneralReferralController#rejectReferral',
    roles: ['ROLE_DOCTOR'],
  },
  referralCancel: {
    endpoint: 'GeneralReferralController#cancelReferral',
    roles: ['ROLE_DOCTOR'],
  },
  allergyCreate: {
    endpoint: 'PatientController#createPatientAllergy',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST'],
  },
  allergyUpdate: {
    endpoint: 'PatientController#updatePatientAllergy',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST'],
  },
  allergyDeactivate: {
    endpoint: 'PatientController#deactivatePatientAllergy',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST'],
  },
} as const satisfies Record<string, ActionPermission>;

/**
 * Service-layer rules the role gate cannot express, applied by the page on top
 * of `actionRoles()`. Not mirrored by the backend test: each names the rule.
 */
export const SERVICE_RULE_ROLES = {
  /**
   * QA D319/D427: `GeneralReferralServiceImpl` refuses a referrer who is not a
   * physician or a midwife, although `createReferral` admits ROLE_NURSE.
   */
  referralReferrer: ['ROLE_DOCTOR', 'ROLE_MIDWIFE'],
} as const;

export type ActionKey = keyof typeof ACTION_PERMISSIONS;

/** The roles the API admits for one action (a fresh, mutable copy). */
export function actionRoles(action: ActionKey): string[] {
  return [...ACTION_PERMISSIONS[action].roles];
}
