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

export type ActionKey = keyof typeof ACTION_PERMISSIONS;

/** The roles the API admits for one action (a fresh, mutable copy). */
export function actionRoles(action: ActionKey): string[] {
  return [...ACTION_PERMISSIONS[action].roles];
}
