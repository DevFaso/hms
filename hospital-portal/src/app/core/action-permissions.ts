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
 * Imaging writes (user decision 2026-11-14) need a clinical role the caller
 * holds on their own: SUPER_ADMIN and the admin roles never act on imaging.
 * QA D2120 extended the rule to every clinical write (encounters, lab orders,
 * specimens, …): no clinical action lists ROLE_SUPER_ADMIN.
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
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE'],
  },
  labOrderUpdate: {
    endpoint: 'LabOrderController#updateLabOrder',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE'],
  },
  labOrderDelete: {
    endpoint: 'LabOrderController#deleteLabOrder',
    roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'],
  },
  labOrderVoid: {
    endpoint: 'LabOrderController#voidLabOrder',
    roles: ['ROLE_LAB_MANAGER', 'ROLE_LAB_DIRECTOR', 'ROLE_SUPER_ADMIN'],
  },
  labResultDelete: {
    endpoint: 'LabResultController#deleteLabResult',
    // QA D2120 review (owner): a data fix the super-admin keeps.
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_LAB_MANAGER', 'ROLE_LAB_SCIENTIST', 'ROLE_SUPER_ADMIN'],
  },
  imagingOrderCreate: {
    endpoint: 'ImagingOrderController#createOrder',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  imagingOrderUpdate: {
    endpoint: 'ImagingOrderController#updateOrder',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  imagingOrderStatus: {
    endpoint: 'ImagingOrderController#updateStatus',
    roles: ['ROLE_DOCTOR', 'ROLE_RADIOLOGIST'],
  },
  labSpecimenCollect: {
    endpoint: 'LabSpecimenController#collectSpecimen',
    roles: [
      'ROLE_LAB_TECHNICIAN',
      'ROLE_LAB_SCIENTIST',
      'ROLE_LAB_MANAGER',
      'ROLE_LAB_DIRECTOR',
      'ROLE_QUALITY_MANAGER',
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
    ],
  },
  labSpecimenReject: {
    endpoint: 'LabSpecimenController#rejectSpecimen',
    roles: [
      'ROLE_LAB_TECHNICIAN',
      'ROLE_LAB_SCIENTIST',
      'ROLE_LAB_MANAGER',
      'ROLE_LAB_DIRECTOR',
      'ROLE_QUALITY_MANAGER',
    ],
  },
  imagingReportsByHospital: {
    endpoint: 'ImagingResultController#getReportsByHospital',
    roles: [
      'ROLE_SUPER_ADMIN',
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_ADMIN',
      'ROLE_DOCTOR',
      'ROLE_RADIOLOGIST',
    ],
  },
  imagingReportCreate: {
    endpoint: 'ImagingResultController#createReport',
    roles: ['ROLE_RADIOLOGIST', 'ROLE_DOCTOR'],
  },
  /** V317: the in-app image viewer (pixels are clinical content, user decision 2026-11-14). */
  imagingViewer: {
    endpoint: 'DicomProxyController#viewer',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_RADIOLOGIST'],
  },
  imagingReportStatus: {
    endpoint: 'ImagingResultController#updateReportStatus',
    roles: ['ROLE_RADIOLOGIST', 'ROLE_DOCTOR'],
  },
  imagingCriticalAck: {
    endpoint: 'ImagingResultController#acknowledgeCriticalResult',
    roles: ['ROLE_DOCTOR', 'ROLE_RADIOLOGIST'],
  },
  /** QA D2306 (r63): the « À signer » list of the reads waiting for the caller's signature. */
  imagingReportsToSign: {
    endpoint: 'ImagingResultController#getReportsToSign',
    roles: ['ROLE_RADIOLOGIST', 'ROLE_DOCTOR'],
  },
  /** r63: the order's reading context (age, sex, pregnancy, creatinine, prior studies). */
  imagingReadingContext: {
    endpoint: 'ImagingReadingContextController#getReadingContext',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_RADIOLOGIST'],
  },
  admissionCreate: {
    endpoint: 'AdmissionController#admitPatient',
    // A midwife admits a woman in labour only (policy 2); the form narrows to it.
    roles: ['ROLE_DOCTOR', 'ROLE_MIDWIFE'],
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
    roles: ['ROLE_RECEPTIONIST', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  encounterUpdate: {
    endpoint: 'EncounterController#update',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  encounterDelete: {
    endpoint: 'EncounterController#delete',
    roles: ['ROLE_SUPER_ADMIN'],
  },
  /**
   * QA D1463: the patient tracker's card actions. The triage form and the
   * check-out dialog open on GET /encounters/{id}, so those two need this read too.
   */
  encounterRead: {
    endpoint: 'EncounterController#getById',
    roles: [
      'ROLE_DOCTOR',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_PATIENT',
      'ROLE_RADIOLOGIST',
      'ROLE_ANESTHESIOLOGIST',
      'ROLE_PHYSIOTHERAPIST',
      'ROLE_SUPER_ADMIN',
    ],
  },
  encounterTriage: {
    endpoint: 'EncounterController#submitTriage',
    roles: ['ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_DOCTOR'],
  },
  encounterCompleteTriage: {
    endpoint: 'EncounterController#completeTriage',
    roles: ['ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_DOCTOR'],
  },
  encounterCompleteExamination: {
    endpoint: 'EncounterController#completeExamination',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  encounterReadyForDischarge: {
    endpoint: 'EncounterController#markReadyForDischarge',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  encounterCheckout: {
    endpoint: 'EncounterController#checkOut',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  // QA D1015: the midwife starts the visits booked with her (the service
  // refuses her any other one).
  encounterStart: {
    endpoint: 'EncounterController#startEncounter',
    roles: ['ROLE_DOCTOR', 'ROLE_MIDWIFE'],
  },
  // QA D954: closing a visit nobody has seen (LWBS / cancelled).
  encounterCloseUnseen: {
    endpoint: 'EncounterController#closeUnseen',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_RECEPTIONIST'],
  },
  // QA D961: a medical certificate is a physician's act. Product decision
  // 2026-10-28: any licensed physician (general practitioner, physician,
  // surgeon, anaesthesiologist, radiologist); never a nurse or a midwife.
  encounterMedicalCertificate: {
    endpoint: 'EncounterController#issueMedicalCertificate',
    roles: [
      'ROLE_DOCTOR',
      'ROLE_PHYSICIAN',
      'ROLE_SURGEON',
      'ROLE_ANESTHESIOLOGIST',
      'ROLE_RADIOLOGIST',
    ],
  },
  referralCreate: {
    endpoint: 'GeneralReferralController#createReferral',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  // QA D661: the receiving-side actions are physician-only.
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
  // QA D1026 class: the referrer withdraws it, whoever may write one (the page
  // also checks that the caller is the referrer).
  referralCancel: {
    endpoint: 'GeneralReferralController#cancelReferral',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  // QA D611/D677: signing is the prescriber's own act; nurses and midwives
  // may draft and edit, but POST /sign and /cosign admit ROLE_DOCTOR only.
  prescriptionCreate: {
    endpoint: 'PrescriptionController#create',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  prescriptionUpdate: {
    endpoint: 'PrescriptionController#update',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  prescriptionSign: {
    endpoint: 'PrescriptionController#sign',
    roles: ['ROLE_DOCTOR'],
  },
  prescriptionCosign: {
    endpoint: 'PrescriptionController#cosign',
    roles: ['ROLE_DOCTOR'],
  },
  // Policy 4: a prescriber reviews an allergy conflict on a signed order (the service narrows it to
  // a prescriber of the order's hospital).
  prescriptionAllergyReview: {
    endpoint: 'PrescriptionController#allergyReview',
    roles: ['ROLE_DOCTOR'],
  },
  allergyCreate: {
    endpoint: 'PatientController#createPatientAllergy',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST'],
  },
  allergyUpdate: {
    endpoint: 'PatientController#updatePatientAllergy',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST'],
  },
  allergyDeactivate: {
    endpoint: 'PatientController#deactivatePatientAllergy',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST'],
  },
  // QA D1224: a diagnosis entered in error is removed (with a reason) by a
  // doctor, nurse or midwife; recording or recoding one stays the doctor's.
  diagnosisRemove: {
    endpoint: 'PatientController#deletePatientDiagnosis',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  },
  // QA D965: sending the reorder alerts is the store's act, not the pharmacist's.
  inventoryReorderTrigger: {
    endpoint: 'pharmacy.InventoryController#triggerReorderAlerts',
    roles: ['ROLE_STORE_MANAGER', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
  },
  /** Labour transfer (provisional rule, 2026-11-12): the service also requires the role AT the hospital. */
  laborTransfer: {
    endpoint: 'LaborController#transferEpisode',
    roles: ['ROLE_MIDWIFE', 'ROLE_DOCTOR'],
  },
  laborArrivalWithoutHandover: {
    endpoint: 'LaborController#recordArrivalWithoutHandover',
    roles: ['ROLE_MIDWIFE', 'ROLE_DOCTOR'],
  },
  // Billing-money plan PR 2 (owner decision D-1): the price list is kept by the
  // hospital admin and the accountant only (never the cashier or the super-admin).
  priceListCreate: {
    endpoint: 'PriceListController#create',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  priceListUpdate: {
    endpoint: 'PriceListController#update',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  priceListAddPrice: {
    endpoint: 'PriceListController#addPrice',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  priceListDeactivate: {
    endpoint: 'PriceListController#deactivate',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  // Billing-money plan PR 4: the CSV import, for the same two roles.
  priceListImport: {
    endpoint: 'PriceListController#importCsv',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  // Review #1196/#1198 L4: the digital-signatures page gates every panel from
  // here (the report lookup was offered to admins the endpoint refuses). The
  // list, lookup and audit entries are reads, mirrored for the same reason.
  signatureSign: {
    endpoint: 'DigitalSignatureController#signReport',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST', 'ROLE_PHARMACIST'],
  },
  signatureRevoke: {
    endpoint: 'DigitalSignatureController#revokeSignature',
    // QA D2120 review (owner): a governance act the super-admin keeps.
    roles: ['ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
  },
  signatureListAll: {
    endpoint: 'DigitalSignatureController#getAllSignatures',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
  },
  signatureReportLookup: {
    endpoint: 'DigitalSignatureController#getSignaturesByReport',
    roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST', 'ROLE_PHARMACIST'],
  },
  signatureAuditTrail: {
    endpoint: 'DigitalSignatureController#getSignatureAuditTrail',
    roles: ['ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
  },
  // Billing-money plan PR 6 (owner decision D-1): the payer catalogue is kept
  // by the hospital admin and the accountant only (never the cashier or the
  // super-admin).
  payerCreate: {
    endpoint: 'PayerController#create',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  payerUpdate: {
    endpoint: 'PayerController#update',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  payerDeactivate: {
    endpoint: 'PayerController#deactivate',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  payerPlanCreate: {
    endpoint: 'PayerController#createPlan',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  payerPlanUpdate: {
    endpoint: 'PayerController#updatePlan',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  payerTermsAdd: {
    endpoint: 'PayerController#addTerms',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  payerRuleAdd: {
    endpoint: 'PayerController#addRule',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  payerRuleEnd: {
    endpoint: 'PayerController#endRule',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT'],
  },
  // Plan section 3.8: the "Formule" picker on the Coverage tab.
  patientInsuranceSetPayerPlan: {
    endpoint: 'PatientInsuranceController#setPayerPlan',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_BILLING_SPECIALIST', 'ROLE_RECEPTIONIST'],
  },
  // QA D2203: the desk records a patient's SMS / e-mail choices.
  patientChannelPreferencesUpdate: {
    endpoint: 'PatientChannelPreferenceController#update',
    roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_RECEPTIONIST'],
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
  /**
   * QA D788: `EncounterController#markReadyForDischarge` admits the desk too
   * (from AWAITING_RESULTS), but from IN_PROGRESS `EncounterServiceImpl` admits
   * only the clinicians of `EncounterClosureRules.callerIsClinician()`.
   */
  readyForDischargeFromInProgress: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
} as const;

export type ActionKey = keyof typeof ACTION_PERMISSIONS;

/** The roles the API admits for one action (a fresh, mutable copy). */
export function actionRoles(action: ActionKey): string[] {
  return [...ACTION_PERMISSIONS[action].roles];
}
