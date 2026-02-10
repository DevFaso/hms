package com.example.hms.security.permission;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central registry for role permission templates used by the developer seeder and RBAC checks.
 */
public final class PermissionCatalog {

    private static final PermissionTemplate DEFAULT_TEMPLATE = template(Permission.VIEW_DASHBOARD);

    private static final Map<String, PermissionTemplate> TEMPLATES;

    static {
        Map<String, PermissionTemplate> templates = new LinkedHashMap<>();
        templates.put("ROLE_SUPER_ADMIN", template(PermissionGroup.SYSTEM_GLOBAL_ADMINISTRATION));
        templates.put("ROLE_HOSPITAL_ADMIN", template(PermissionGroup.HOSPITAL_ADMINISTRATION));
        templates.put(
            "ROLE_DOCTOR",
            template(
                Permission.VIEW_PATIENT_RECORDS,
                Permission.UPDATE_PATIENT_RECORDS,
                Permission.CREATE_PRESCRIPTIONS,
                PermissionGroup.LAB_TEST_MANAGEMENT,
                PermissionGroup.CLINICAL_ENCOUNTER_MANAGEMENT,
                Permission.REQUEST_IMAGING_STUDIES,
                Permission.VIEW_IMAGING_RESULTS,
                PermissionGroup.CLINICAL_TREATMENT_MANAGEMENT,
                Permission.ORDER_MEDICATIONS,
                Permission.REQUEST_CONSULTATIONS,
                Permission.VIEW_PATIENT_VITALS,
                PermissionGroup.CLINICAL_DISPOSITION,
                PermissionGroup.CLINICAL_PROCEDURE_SIGNOFF,
                Permission.ACCESS_PATIENT_ALLERGIES,
                Permission.VIEW_MEDICATION_HISTORY,
                Permission.CREATE_REFERRALS
            )
        );
        templates.put(
            "ROLE_SURGEON",
            template(
                Permission.VIEW_PATIENT_RECORDS,
                Permission.UPDATE_PATIENT_RECORDS,
                PermissionGroup.SURGICAL_OPERATIONS,
                Permission.VIEW_PATIENT_VITALS,
                Permission.ACCESS_PATIENT_ALLERGIES,
                Permission.CREATE_REFERRALS
            )
        );
        templates.put("ROLE_NURSE", template(PermissionGroup.NURSING_OPERATIONS));
        templates.put(
            "ROLE_MIDWIFE",
            template(
                Permission.VIEW_PATIENT_RECORDS,
                Permission.UPDATE_PATIENT_RECORDS,
                PermissionGroup.MATERNITY_CARE_CORE,
                PermissionGroup.LAB_TEST_MANAGEMENT,
                PermissionGroup.MATERNITY_CARE_ADVANCED
            )
        );
        templates.put("ROLE_PHARMACIST", template(PermissionGroup.PHARMACY_OPERATIONS));
        templates.put("ROLE_RADIOLOGIST", template(PermissionGroup.RADIOLOGY_OPERATIONS));
        templates.put(
            "ROLE_ANESTHESIOLOGIST",
            template(
                Permission.VIEW_PATIENT_RECORDS,
                PermissionGroup.ANESTHESIA_OPERATIONS,
                Permission.ACCESS_PATIENT_ALLERGIES,
                Permission.VIEW_MEDICATION_HISTORY
            )
        );
        templates.put("ROLE_RECEPTIONIST", template(PermissionGroup.RECEPTION_PATIENT_MANAGEMENT));
        templates.put("ROLE_BILLING_SPECIALIST", template(PermissionGroup.FINANCE_OPERATIONS));
        templates.put("ROLE_LAB_SCIENTIST", template(PermissionGroup.LAB_OPERATIONS));
        templates.put(
            "ROLE_PHYSIOTHERAPIST",
            template(Permission.VIEW_PATIENT_RECORDS, PermissionGroup.REHABILITATION_OPERATIONS)
        );
        templates.put("ROLE_PATIENT", template(PermissionGroup.PATIENT_SELF_SERVICE));
        TEMPLATES = Map.copyOf(templates);
    }

    private PermissionCatalog() {
    }

    public static List<String> permissionsForRole(String roleCode) {
        PermissionTemplate template = TEMPLATES.get(roleCode);
        if (template == null) {
            return DEFAULT_TEMPLATE.resolveDisplayNames();
        }
        return template.resolveDisplayNames();
    }

    public static Set<String> registeredRoleCodes() {
        return TEMPLATES.keySet();
    }

    public static boolean isKnownPermissionName(String name) {
        return Permission.isKnownDisplayName(name);
    }

    private static PermissionTemplate template(PermissionComponent... components) {
        return new PermissionTemplate(List.of(components));
    }

    private record PermissionTemplate(List<PermissionComponent> components) {
        List<String> resolveDisplayNames() {
            LinkedHashSet<Permission> ordered = new LinkedHashSet<>();
            for (PermissionComponent component : components) {
                ordered.addAll(component.expand());
            }
            return ordered.stream().map(Permission::displayName).toList();
        }
    }

    private interface PermissionComponent {
        List<Permission> expand();
    }

    public enum Permission implements PermissionComponent {
        ACCESS_ALL_PATIENT_RECORDS("Access All Patient Records"),
        ACCESS_HOSPITAL_AUDIT_LOGS("Access Hospital Audit Logs"),
        ACCESS_PATIENT_ALLERGIES("Access Patient Allergies"),
        ACCESS_PATIENT_HISTORY("Access Patient History"),
        ACCESS_TREATMENT_PLANS("Access Treatment Plans"),
        ADMINISTER_ANESTHESIA("Administer Anesthesia"),
        ADMINISTER_MEDICATION("Administer Medication"),
        ADMINISTER_MEDICATIONS("Administer Medications"),
        ADMIT_PATIENTS("Admit Patients"),
        ADMIT_PATIENTS_TO_SURGERY("Admit Patients to Surgery"),
        ALERT_DOCTORS("Alert Doctors"),
        ALERT_OBSTETRICIANS("Alert Obstetricians"),
        ALERT_SURGICAL_TEAM("Alert Surgical Team"),
        APPLY_PAYMENT_PLANS("Apply Payment Plans"),
        APPROVE_BILLING("Approve Billing"),
        APPROVE_LAB_RESULTS("Approve Lab Results"),
        APPROVE_LARGE_EXPENSES("Approve Large Expenses"),
        APPROVE_LEAVE_REQUESTS("Approve Leave Requests"),
        ASSIGN_STAFF_ROLES("Assign Staff Roles"),
        CALCULATE_DOSAGES("Calculate Dosages"),
        CALIBRATE_EQUIPMENT("Calibrate Equipment"),
        CANCEL_APPOINTMENTS("Cancel Appointments"),
        CANCEL_OWN_APPOINTMENTS("Cancel Own Appointments"),
        CHECK_PATIENT_ALERTS("Check Patient Alerts"),
        CHECK_IN_PATIENTS("Check-in Patients"),
        CHECK_OUT_PATIENTS("Check-out Patients"),
        COMMUNICATE_FINDINGS_TO_PHYSICIANS("Communicate Findings to Physicians"),
        COMMUNICATE_WITH_PHYSICIANS("Communicate with Physicians"),
        COMMUNICATE_WITH_PRESCRIBERS("Communicate with Prescribers"),
        COMPOUND_MEDICATIONS("Compound Medications"),
        CONFIGURE_DEPARTMENT_SETTINGS("Configure Department Settings"),
        CONSENT_TO_DATA_SHARING("Consent to Data Sharing"),
        CONSULT_ON_PAIN_MANAGEMENT("Consult on Pain Management"),
        COORDINATE_PATIENT_TRANSFERS("Coordinate Patient Transfers"),
        COORDINATE_WITH_PHYSICIANS("Coordinate with Physicians"),
        COUNSEL_PATIENTS_ON_MEDICATIONS("Counsel Patients on Medications"),
        CREATE_ANESTHESIA_PLANS("Create Anesthesia Plans"),
        CREATE_BIRTH_PLANS("Create Birth Plans"),
        CREATE_DISCHARGE_PLANS("Create Discharge Plans"),
        CREATE_ENCOUNTERS("Create Encounters"),
        CREATE_HOME_EXERCISE_PROGRAMS("Create Home Exercise Programs"),
        CREATE_INVOICES("Create Invoices"),
        CREATE_LAB_RESULTS("Create Lab Results"),
        CREATE_PHARMACEUTICAL_REPORTS("Create Pharmaceutical Reports"),
        CREATE_POST_OP_ORDERS("Create Post-op Orders"),
        CREATE_PRESCRIPTIONS("Create Prescriptions"),
        CREATE_PROCEDURE_ORDERS("Create Procedure Orders"),
        CREATE_RADIOLOGY_REPORTS("Create Radiology Reports"),
        CREATE_REFERRALS("Create Referrals"),
        CREATE_REFERRALS_TO_OB_GYN("Create Referrals to OB-GYN"),
        CREATE_SURGICAL_PLANS("Create Surgical Plans"),
        CREATE_TREATMENT_PLANS("Create Treatment Plans"),
        DELETE_HOSPITALS("Delete Hospitals"),
        DELETE_ORGANIZATIONS("Delete Organizations"),
        DISCHARGE_FROM_RECOVERY("Discharge from Recovery"),
        DISCHARGE_PATIENTS("Discharge Patients"),
        DISPENSE_MEDICATIONS("Dispense Medications"),
        DOCUMENT_ADVERSE_REACTIONS("Document Adverse Reactions"),
        DOCUMENT_ANESTHESIA_RECORDS("Document Anesthesia Records"),
        DOCUMENT_COMPLICATIONS("Document Complications"),
        DOCUMENT_DELIVERY_NOTES("Document Delivery Notes"),
        DOCUMENT_MATERNAL_HISTORY("Document Maternal History"),
        DOCUMENT_NEWBORN_ASSESSMENT("Document Newborn Assessment"),
        DOCUMENT_NURSING_NOTES("Document Nursing Notes"),
        DOCUMENT_PAIN_LEVELS("Document Pain Levels"),
        DOCUMENT_PATIENT_PROGRESS("Document Patient Progress"),
        DOCUMENT_RADIATION_DOSAGE("Document Radiation Dosage"),
        DOCUMENT_SURGICAL_PROCEDURES("Document Surgical Procedures"),
        DOCUMENT_TEST_PROCEDURES("Document Test Procedures"),
        DOCUMENT_THERAPY_SESSIONS("Document Therapy Sessions"),
        DOCUMENT_VITAL_SIGNS("Document Vital Signs"),
        DOWNLOAD_MEDICAL_RECORDS("Download Medical Records"),
        EDUCATE_PATIENTS("Educate Patients"),
        EDUCATE_PATIENTS_ON_EXERCISES("Educate Patients on Exercises"),
        FLAG_ABNORMAL_RESULTS("Flag Abnormal Results"),
        FLAG_CRITICAL_FINDINGS("Flag Critical Findings"),
        GENERATE_FINANCIAL_STATEMENTS("Generate Financial Statements"),
        GENERATE_LAB_REPORTS("Generate Lab Reports"),
        GENERATE_PATIENT_REPORTS("Generate Patient Reports"),
        GENERATE_PAYMENT_RECEIPTS("Generate Payment Receipts"),
        HANDLE_ANESTHETIC_COMPLICATIONS("Handle Anesthetic Complications"),
        HANDLE_BILLING_DISPUTES("Handle Billing Disputes"),
        INTERPRET_CT_SCANS("Interpret CT Scans"),
        INTERPRET_MRI_SCANS("Interpret MRI Scans"),
        INTERPRET_ULTRASOUNDS("Interpret Ultrasounds"),
        INTERPRET_X_RAYS("Interpret X-rays"),
        MAKE_PAYMENTS("Make Payments"),
        MANAGE_AIRWAY("Manage Airway"),
        MANAGE_ALL_HOSPITALS("Manage All Hospitals"),
        MANAGE_ALL_ORGANIZATIONS("Manage All Organizations"),
        MANAGE_ALL_USERS("Manage All Users"),
        MANAGE_BACKUPS("Manage Backups"),
        MANAGE_BED_CAPACITY("Manage Bed Capacity"),
        MANAGE_DATABASE_ACCESS("Manage Database Access"),
        MANAGE_DEPARTMENTS("Manage Departments"),
        MANAGE_DISCOUNTS("Manage Discounts"),
        MANAGE_EMERGENCY_PROTOCOLS("Manage Emergency Protocols"),
        MANAGE_EQUIPMENT_INVENTORY("Manage Equipment Inventory"),
        MANAGE_GLOBAL_CONFIGURATIONS("Manage Global Configurations"),
        MANAGE_HIGH_RISK_PREGNANCIES("Manage High-Risk Pregnancies"),
        MANAGE_HOSPITAL_SETTINGS("Manage Hospital Settings"),
        MANAGE_HOSPITAL_STAFF("Manage Hospital Staff"),
        MANAGE_IMAGING_EQUIPMENT("Manage Imaging Equipment"),
        MANAGE_INSURANCE_CLAIMS("Manage Insurance Claims"),
        MANAGE_INSURANCE_CONTRACTS("Manage Insurance Contracts"),
        MANAGE_LAB_INVENTORY("Manage Lab Inventory"),
        MANAGE_LAB_TESTS("Manage Lab Tests"),
        MANAGE_OPERATING_ROOM_SCHEDULE("Manage Operating Room Schedule"),
        MANAGE_PAIN_CONTROL("Manage Pain Control"),
        MANAGE_PATIENT_CARE_PLANS("Manage Patient Care Plans"),
        MANAGE_PERMISSIONS("Manage Permissions"),
        MANAGE_PHARMACY_STOCK("Manage Pharmacy Stock"),
        MANAGE_POST_ANESTHETIC_CARE("Manage Post-anesthetic Care"),
        MANAGE_ROLES("Manage Roles"),
        MANAGE_SYSTEM_SETTINGS("Manage System Settings"),
        MANAGE_WAITING_LIST("Manage Waiting List"),
        MEASURE_RANGE_OF_MOTION("Measure Range of Motion"),
        MONITOR_CONTROLLED_SUBSTANCES("Monitor Controlled Substances"),
        MONITOR_LABOR_PROGRESS("Monitor Labor Progress"),
        MONITOR_PATIENT_DURING_SURGERY("Monitor Patient During Surgery"),
        MONITOR_REHABILITATION_PROGRESS("Monitor Rehabilitation Progress"),
        ORDER_ASSISTIVE_DEVICES("Order Assistive Devices"),
        ORDER_LAB_TESTS("Order Lab Tests"),
        ORDER_MEDICATIONS("Order Medications"),
        ORDER_MEDICATIONS_FROM_SUPPLIERS("Order Medications from Suppliers"),
        ORDER_PRE_OP_TESTS("Order Pre-op Tests"),
        OVERRIDE_SECURITY_SETTINGS("Override Security Settings"),
        PERFORM_IMAGING_STUDIES("Perform Imaging Studies"),
        PERFORM_INTERVENTIONAL_PROCEDURES("Perform Interventional Procedures"),
        PERFORM_PHYSICAL_ASSESSMENTS("Perform Physical Assessments"),
        PERFORM_POSTPARTUM_CARE("Perform Postpartum Care"),
        PERFORM_PRE_ANESTHETIC_EVALUATION("Perform Pre-anesthetic Evaluation"),
        PERFORM_PRENATAL_ASSESSMENTS("Perform Prenatal Assessments"),
        PERFORM_QUALITY_CONTROL("Perform Quality Control"),
        PERFORM_ULTRASOUND_SCANS("Perform Ultrasound Scans"),
        PREPARE_PATIENTS_FOR_PROCEDURES("Prepare Patients for Procedures"),
        PRESCRIBE_EXERCISES("Prescribe Exercises"),
        PRINT_PATIENT_CARDS("Print Patient Cards"),
        PROCESS_CO_PAYMENTS("Process Co-payments"),
        PROCESS_PAYMENTS("Process Payments"),
        PROCESS_REFUNDS("Process Refunds"),
        PROVIDE_BREASTFEEDING_SUPPORT("Provide Breastfeeding Support"),
        RECONCILE_ACCOUNTS("Reconcile Accounts"),
        RECORD_INTAKE_OUTPUT("Record Intake Output"),
        REGISTER_PATIENTS("Register Patients"),
        REQUEST_ADDITIONAL_VIEWS("Request Additional Views"),
        REQUEST_ANESTHESIA_CONSULTATION("Request Anesthesia Consultation"),
        REQUEST_APPOINTMENTS("Request Appointments"),
        REQUEST_BLOOD_PRODUCTS("Request Blood Products"),
        REQUEST_CONSULTATIONS("Request Consultations"),
        REQUEST_IMAGING_STUDIES("Request Imaging Studies"),
        REQUEST_MEDICAL_REPORTS("Request Medical Reports"),
        REQUEST_SAMPLE_RE_COLLECTION("Request Sample Re-collection"),
        RESCHEDULE_APPOINTMENTS("Reschedule Appointments"),
        REVIEW_MEDICATION_ORDERS("Review Medication Orders"),
        SCHEDULE_APPOINTMENTS("Schedule Appointments"),
        SCHEDULE_IMAGING_APPOINTMENTS("Schedule Imaging Appointments"),
        SCHEDULE_PRENATAL_APPOINTMENTS("Schedule Prenatal Appointments"),
        SCHEDULE_SURGERIES("Schedule Surgeries"),
        SCHEDULE_THERAPY_APPOINTMENTS("Schedule Therapy Appointments"),
        SEND_APPOINTMENT_REMINDERS("Send Appointment Reminders"),
        SEND_PAYMENT_REMINDERS("Send Payment Reminders"),
        SET_BILLING_RATES("Set Billing Rates"),
        SIGN_IMAGING_REPORTS("Sign Imaging Reports"),
        SIGN_MEDICAL_REPORTS("Sign Medical Reports"),
        SIGN_OPERATIVE_REPORTS("Sign Operative Reports"),
        SUBMIT_CLAIMS_TO_INSURERS("Submit Claims to Insurers"),
        SUGGEST_MEDICATION_ALTERNATIVES("Suggest Medication Alternatives"),
        TRACK_MEDICATION_EXPIRY("Track Medication Expiry"),
        TRACK_OUTSTANDING_PAYMENTS("Track Outstanding Payments"),
        UPDATE_BILLING_CODES("Update Billing Codes"),
        UPDATE_CONTACT_INFO("Update Contact Info"),
        UPDATE_DIAGNOSES("Update Diagnoses"),
        UPDATE_DRUG_FORMULARY("Update Drug Formulary"),
        UPDATE_EMERGENCY_CONTACTS("Update Emergency Contacts"),
        UPDATE_ENCOUNTER_NOTES("Update Encounter Notes"),
        UPDATE_INVOICE_STATUS("Update Invoice Status"),
        UPDATE_LAB_RESULTS("Update Lab Results"),
        UPDATE_MEDICAL_HISTORY("Update Medical History"),
        UPDATE_MEDICATION_INVENTORY("Update Medication Inventory"),
        UPDATE_PATIENT_CONTACT_INFO("Update Patient Contact Info"),
        UPDATE_PATIENT_OBSERVATIONS("Update Patient Observations"),
        UPDATE_PATIENT_PROGRESS("Update Patient Progress"),
        UPDATE_PATIENT_RECORDS("Update Patient Records"),
        UPDATE_PATIENT_STATUS("Update Patient Status"),
        UPDATE_REFERENCE_RANGES("Update Reference Ranges"),
        UPDATE_SURGICAL_NOTES("Update Surgical Notes"),
        UPDATE_TEST_STATUS("Update Test Status"),
        UPDATE_VISIT_REASONS("Update Visit Reasons"),
        UPDATE_VITAL_SIGNS("Update Vital Signs"),
        UPDATE_WOUND_CARE_RECORDS("Update Wound Care Records"),
        VERIFY_CRITICAL_RESULTS("Verify Critical Results"),
        VERIFY_DRUG_INTERACTIONS("Verify Drug Interactions"),
        VERIFY_INSURANCE_COVERAGE("Verify Insurance Coverage"),
        VERIFY_INSURANCE_ELIGIBILITY("Verify Insurance Eligibility"),
        VERIFY_INSURANCE_INFORMATION("Verify Insurance Information"),
        VERIFY_MEDICATION_ADMINISTRATION("Verify Medication Administration"),
        VIEW_ALL_AUDIT_LOGS("View All Audit Logs"),
        VIEW_APPOINTMENTS("View Appointments"),
        VIEW_BILLING_REPORTS("View Billing Reports"),
        VIEW_BILLING_STATEMENTS("View Billing Statements"),
        VIEW_DASHBOARD("View Dashboard"),
        VIEW_DOCTOR_AVAILABILITY("View Doctor Availability"),
        VIEW_FINANCIAL_REPORTS("View Financial Reports"),
        VIEW_HOSPITAL_REPORTS("View Hospital Reports"),
        VIEW_IMAGING_ORDERS("View Imaging Orders"),
        VIEW_IMAGING_RESULTS("View Imaging Results"),
        VIEW_IMMUNIZATION_RECORDS("View Immunization Records"),
        VIEW_INSURANCE_INFORMATION("View Insurance Information"),
        VIEW_LAB_ORDERS("View Lab Orders"),
        VIEW_LAB_RESULTS("View Lab Results"),
        VIEW_MEDICAL_HISTORY("View Medical History"),
        VIEW_MEDICATION_HISTORY("View Medication History"),
        VIEW_MEDICATION_INSTRUCTIONS("View Medication Instructions"),
        VIEW_MEDICATION_ORDERS("View Medication Orders"),
        VIEW_OWN_APPOINTMENTS("View Own Appointments"),
        VIEW_OWN_LAB_RESULTS("View Own Lab Results"),
        VIEW_OWN_PRESCRIPTIONS("View Own Prescriptions"),
        VIEW_OWN_RECORDS("View Own Records"),
        VIEW_OWN_VITAL_SIGNS("View Own Vital Signs"),
        VIEW_PATIENT_DEMOGRAPHICS("View Patient Demographics"),
        VIEW_PATIENT_RECORDS("View Patient Records"),
        VIEW_PATIENT_STATISTICS("View Patient Statistics"),
        VIEW_PATIENT_VITALS("View Patient Vitals"),
        VIEW_PAYMENT_HISTORY("View Payment History"),
        VIEW_PRESCRIPTIONS("View Prescriptions"),
        VIEW_SCHEDULES("View Schedules"),
        VIEW_STAFF_SCHEDULES("View Staff Schedules"),
        VIEW_SURGICAL_HISTORY("View Surgical History"),
        VIEW_SYSTEM_ANALYTICS("View System Analytics"),
        VIEW_TEST_DEFINITIONS("View Test Definitions"),
        VIEW_TEST_RESULTS("View Test Results"),
        VIEW_TREATMENT_PLANS("View Treatment Plans");

        private final String displayName;
        private static final Map<String, Permission> BY_DISPLAY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Permission::displayName, Function.identity()));

        Permission(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        @Override
        public List<Permission> expand() {
            return List.of(this);
        }

        static boolean isKnownDisplayName(String name) {
            return BY_DISPLAY_NAME.containsKey(name);
        }
    }

    private enum PermissionGroup implements PermissionComponent {
        SYSTEM_GLOBAL_ADMINISTRATION(
            Permission.MANAGE_ALL_ORGANIZATIONS,
            Permission.MANAGE_ALL_HOSPITALS,
            Permission.MANAGE_ALL_USERS,
            Permission.MANAGE_SYSTEM_SETTINGS,
            Permission.VIEW_ALL_AUDIT_LOGS,
            Permission.MANAGE_PERMISSIONS,
            Permission.MANAGE_ROLES,
            Permission.DELETE_ORGANIZATIONS,
            Permission.DELETE_HOSPITALS,
            Permission.MANAGE_GLOBAL_CONFIGURATIONS,
            Permission.VIEW_SYSTEM_ANALYTICS,
            Permission.MANAGE_BACKUPS,
            Permission.ACCESS_ALL_PATIENT_RECORDS,
            Permission.OVERRIDE_SECURITY_SETTINGS,
            Permission.MANAGE_DATABASE_ACCESS
        ),
        HOSPITAL_ADMINISTRATION(
            Permission.MANAGE_HOSPITAL_STAFF,
            Permission.MANAGE_DEPARTMENTS,
            Permission.VIEW_HOSPITAL_REPORTS,
            Permission.MANAGE_HOSPITAL_SETTINGS,
            Permission.APPROVE_BILLING,
            Permission.VIEW_STAFF_SCHEDULES,
            Permission.ASSIGN_STAFF_ROLES,
            Permission.MANAGE_BED_CAPACITY,
            Permission.VIEW_FINANCIAL_REPORTS,
            Permission.MANAGE_EQUIPMENT_INVENTORY,
            Permission.CONFIGURE_DEPARTMENT_SETTINGS,
            Permission.VIEW_PATIENT_STATISTICS,
            Permission.APPROVE_LEAVE_REQUESTS,
            Permission.MANAGE_EMERGENCY_PROTOCOLS,
            Permission.ACCESS_HOSPITAL_AUDIT_LOGS,
            Permission.MANAGE_INSURANCE_CONTRACTS,
            Permission.SET_BILLING_RATES,
            Permission.APPROVE_LARGE_EXPENSES
        ),
        LAB_TEST_MANAGEMENT(
            Permission.ORDER_LAB_TESTS,
            Permission.VIEW_LAB_RESULTS
        ),
        CLINICAL_ENCOUNTER_MANAGEMENT(
            Permission.CREATE_ENCOUNTERS,
            Permission.UPDATE_DIAGNOSES,
            Permission.UPDATE_ENCOUNTER_NOTES
        ),
        CLINICAL_TREATMENT_MANAGEMENT(
            Permission.CREATE_TREATMENT_PLANS,
            Permission.UPDATE_MEDICAL_HISTORY
        ),
        CLINICAL_PROCEDURE_SIGNOFF(
            Permission.CREATE_PROCEDURE_ORDERS,
            Permission.SIGN_MEDICAL_REPORTS
        ),
        CLINICAL_DISPOSITION(
            Permission.DISCHARGE_PATIENTS,
            Permission.ADMIT_PATIENTS
        ),
        SURGICAL_OPERATIONS(
            Permission.SCHEDULE_SURGERIES,
            Permission.CREATE_SURGICAL_PLANS,
            Permission.DOCUMENT_SURGICAL_PROCEDURES,
            Permission.ORDER_PRE_OP_TESTS,
            Permission.REQUEST_ANESTHESIA_CONSULTATION,
            Permission.VIEW_IMAGING_RESULTS,
            Permission.UPDATE_SURGICAL_NOTES,
            Permission.CREATE_POST_OP_ORDERS,
            Permission.ADMIT_PATIENTS_TO_SURGERY,
            Permission.DISCHARGE_FROM_RECOVERY,
            Permission.SIGN_OPERATIVE_REPORTS,
            Permission.REQUEST_BLOOD_PRODUCTS,
            Permission.MANAGE_OPERATING_ROOM_SCHEDULE,
            Permission.VIEW_SURGICAL_HISTORY,
            Permission.DOCUMENT_COMPLICATIONS
        ),
        NURSING_OPERATIONS(
            Permission.VIEW_PATIENT_RECORDS,
            Permission.UPDATE_VITAL_SIGNS,
            Permission.ADMINISTER_MEDICATION,
            Permission.VIEW_SCHEDULES,
            Permission.UPDATE_PATIENT_STATUS,
            Permission.RECORD_INTAKE_OUTPUT,
            Permission.DOCUMENT_NURSING_NOTES,
            Permission.VIEW_MEDICATION_ORDERS,
            Permission.VERIFY_MEDICATION_ADMINISTRATION,
            Permission.UPDATE_PATIENT_OBSERVATIONS,
            Permission.PREPARE_PATIENTS_FOR_PROCEDURES,
            Permission.VIEW_TREATMENT_PLANS,
            Permission.ALERT_DOCTORS,
            Permission.MANAGE_PATIENT_CARE_PLANS,
            Permission.DOCUMENT_PATIENT_PROGRESS,
            Permission.UPDATE_WOUND_CARE_RECORDS,
            Permission.CHECK_PATIENT_ALERTS,
            Permission.VIEW_LAB_RESULTS,
            Permission.COORDINATE_PATIENT_TRANSFERS
        ),
        MATERNITY_CARE_CORE(
            Permission.MONITOR_LABOR_PROGRESS,
            Permission.DOCUMENT_DELIVERY_NOTES,
            Permission.PERFORM_PRENATAL_ASSESSMENTS,
            Permission.CREATE_BIRTH_PLANS,
            Permission.ADMINISTER_MEDICATIONS,
            Permission.UPDATE_VITAL_SIGNS,
            Permission.PERFORM_POSTPARTUM_CARE,
            Permission.PROVIDE_BREASTFEEDING_SUPPORT,
            Permission.SCHEDULE_PRENATAL_APPOINTMENTS,
            Permission.DOCUMENT_NEWBORN_ASSESSMENT
        ),
        MATERNITY_CARE_ADVANCED(
            Permission.CREATE_REFERRALS_TO_OB_GYN,
            Permission.EDUCATE_PATIENTS,
            Permission.MANAGE_HIGH_RISK_PREGNANCIES,
            Permission.PERFORM_ULTRASOUND_SCANS,
            Permission.DOCUMENT_MATERNAL_HISTORY,
            Permission.ALERT_OBSTETRICIANS
        ),
        PHARMACY_OPERATIONS(
            Permission.VIEW_PRESCRIPTIONS,
            Permission.DISPENSE_MEDICATIONS,
            Permission.VERIFY_DRUG_INTERACTIONS,
            Permission.UPDATE_MEDICATION_INVENTORY,
            Permission.COUNSEL_PATIENTS_ON_MEDICATIONS,
            Permission.CREATE_PHARMACEUTICAL_REPORTS,
            Permission.MONITOR_CONTROLLED_SUBSTANCES,
            Permission.REVIEW_MEDICATION_ORDERS,
            Permission.SUGGEST_MEDICATION_ALTERNATIVES,
            Permission.DOCUMENT_ADVERSE_REACTIONS,
            Permission.MANAGE_PHARMACY_STOCK,
            Permission.ORDER_MEDICATIONS_FROM_SUPPLIERS,
            Permission.VERIFY_INSURANCE_COVERAGE,
            Permission.CALCULATE_DOSAGES,
            Permission.COMPOUND_MEDICATIONS,
            Permission.TRACK_MEDICATION_EXPIRY,
            Permission.COMMUNICATE_WITH_PRESCRIBERS,
            Permission.UPDATE_DRUG_FORMULARY
        ),
        RADIOLOGY_OPERATIONS(
            Permission.VIEW_IMAGING_ORDERS,
            Permission.PERFORM_IMAGING_STUDIES,
            Permission.INTERPRET_X_RAYS,
            Permission.INTERPRET_CT_SCANS,
            Permission.INTERPRET_MRI_SCANS,
            Permission.INTERPRET_ULTRASOUNDS,
            Permission.CREATE_RADIOLOGY_REPORTS,
            Permission.SIGN_IMAGING_REPORTS,
            Permission.VIEW_PATIENT_RECORDS,
            Permission.REQUEST_ADDITIONAL_VIEWS,
            Permission.COMMUNICATE_FINDINGS_TO_PHYSICIANS,
            Permission.PERFORM_INTERVENTIONAL_PROCEDURES,
            Permission.MANAGE_IMAGING_EQUIPMENT,
            Permission.SCHEDULE_IMAGING_APPOINTMENTS,
            Permission.DOCUMENT_RADIATION_DOSAGE,
            Permission.FLAG_CRITICAL_FINDINGS,
            Permission.ACCESS_PATIENT_HISTORY
        ),
        ANESTHESIA_OPERATIONS(
            Permission.PERFORM_PRE_ANESTHETIC_EVALUATION,
            Permission.CREATE_ANESTHESIA_PLANS,
            Permission.ADMINISTER_ANESTHESIA,
            Permission.MONITOR_PATIENT_DURING_SURGERY,
            Permission.MANAGE_POST_ANESTHETIC_CARE,
            Permission.DOCUMENT_ANESTHESIA_RECORDS,
            Permission.VIEW_LAB_RESULTS,
            Permission.ORDER_PRE_OP_TESTS,
            Permission.MANAGE_PAIN_CONTROL,
            Permission.ALERT_SURGICAL_TEAM,
            Permission.MANAGE_AIRWAY,
            Permission.DOCUMENT_VITAL_SIGNS,
            Permission.HANDLE_ANESTHETIC_COMPLICATIONS,
            Permission.CONSULT_ON_PAIN_MANAGEMENT
        ),
        RECEPTION_PATIENT_MANAGEMENT(
            Permission.REGISTER_PATIENTS,
            Permission.SCHEDULE_APPOINTMENTS,
            Permission.VIEW_APPOINTMENTS,
            Permission.UPDATE_PATIENT_CONTACT_INFO,
            Permission.CHECK_IN_PATIENTS,
            Permission.CANCEL_APPOINTMENTS,
            Permission.RESCHEDULE_APPOINTMENTS,
            Permission.VIEW_PATIENT_DEMOGRAPHICS,
            Permission.PRINT_PATIENT_CARDS,
            Permission.UPDATE_EMERGENCY_CONTACTS,
            Permission.VERIFY_INSURANCE_INFORMATION,
            Permission.GENERATE_PATIENT_REPORTS,
            Permission.MANAGE_WAITING_LIST,
            Permission.SEND_APPOINTMENT_REMINDERS,
            Permission.CHECK_OUT_PATIENTS,
            Permission.UPDATE_VISIT_REASONS,
            Permission.VIEW_DOCTOR_AVAILABILITY
        ),
        FINANCE_OPERATIONS(
            Permission.CREATE_INVOICES,
            Permission.PROCESS_PAYMENTS,
            Permission.VIEW_BILLING_REPORTS,
            Permission.MANAGE_INSURANCE_CLAIMS,
            Permission.UPDATE_INVOICE_STATUS,
            Permission.GENERATE_PAYMENT_RECEIPTS,
            Permission.PROCESS_REFUNDS,
            Permission.VIEW_PAYMENT_HISTORY,
            Permission.SUBMIT_CLAIMS_TO_INSURERS,
            Permission.TRACK_OUTSTANDING_PAYMENTS,
            Permission.APPLY_PAYMENT_PLANS,
            Permission.SEND_PAYMENT_REMINDERS,
            Permission.RECONCILE_ACCOUNTS,
            Permission.GENERATE_FINANCIAL_STATEMENTS,
            Permission.VERIFY_INSURANCE_ELIGIBILITY,
            Permission.UPDATE_BILLING_CODES,
            Permission.PROCESS_CO_PAYMENTS,
            Permission.MANAGE_DISCOUNTS,
            Permission.HANDLE_BILLING_DISPUTES
        ),
        LAB_OPERATIONS(
            Permission.VIEW_LAB_ORDERS,
            Permission.CREATE_LAB_RESULTS,
            Permission.UPDATE_LAB_RESULTS,
            Permission.MANAGE_LAB_TESTS,
            Permission.VIEW_TEST_DEFINITIONS,
            Permission.APPROVE_LAB_RESULTS,
            Permission.REQUEST_SAMPLE_RE_COLLECTION,
            Permission.UPDATE_TEST_STATUS,
            Permission.CALIBRATE_EQUIPMENT,
            Permission.PERFORM_QUALITY_CONTROL,
            Permission.DOCUMENT_TEST_PROCEDURES,
            Permission.VERIFY_CRITICAL_RESULTS,
            Permission.MANAGE_LAB_INVENTORY,
            Permission.UPDATE_REFERENCE_RANGES,
            Permission.GENERATE_LAB_REPORTS,
            Permission.FLAG_ABNORMAL_RESULTS,
            Permission.COMMUNICATE_WITH_PHYSICIANS
        ),
        REHABILITATION_OPERATIONS(
            Permission.CREATE_TREATMENT_PLANS,
            Permission.DOCUMENT_THERAPY_SESSIONS,
            Permission.UPDATE_PATIENT_PROGRESS,
            Permission.SCHEDULE_THERAPY_APPOINTMENTS,
            Permission.PERFORM_PHYSICAL_ASSESSMENTS,
            Permission.PRESCRIBE_EXERCISES,
            Permission.MONITOR_REHABILITATION_PROGRESS,
            Permission.CREATE_DISCHARGE_PLANS,
            Permission.ORDER_ASSISTIVE_DEVICES,
            Permission.EDUCATE_PATIENTS_ON_EXERCISES,
            Permission.COORDINATE_WITH_PHYSICIANS,
            Permission.VIEW_MEDICAL_HISTORY,
            Permission.DOCUMENT_PAIN_LEVELS,
            Permission.MEASURE_RANGE_OF_MOTION,
            Permission.CREATE_HOME_EXERCISE_PROGRAMS
        ),
        PATIENT_SELF_SERVICE(
            Permission.VIEW_OWN_RECORDS,
            Permission.VIEW_OWN_APPOINTMENTS,
            Permission.REQUEST_APPOINTMENTS,
            Permission.VIEW_OWN_LAB_RESULTS,
            Permission.UPDATE_CONTACT_INFO,
            Permission.VIEW_OWN_PRESCRIPTIONS,
            Permission.VIEW_OWN_VITAL_SIGNS,
            Permission.DOWNLOAD_MEDICAL_RECORDS,
            Permission.VIEW_BILLING_STATEMENTS,
            Permission.MAKE_PAYMENTS,
            Permission.VIEW_MEDICATION_INSTRUCTIONS,
            Permission.ACCESS_TREATMENT_PLANS,
            Permission.VIEW_IMMUNIZATION_RECORDS,
            Permission.UPDATE_EMERGENCY_CONTACTS,
            Permission.REQUEST_MEDICAL_REPORTS,
            Permission.VIEW_TEST_RESULTS,
            Permission.CANCEL_OWN_APPOINTMENTS,
            Permission.VIEW_INSURANCE_INFORMATION,
            Permission.CONSENT_TO_DATA_SHARING
        );

        private final List<Permission> permissions;

        PermissionGroup(Permission... permissions) {
            this.permissions = List.of(permissions);
        }

        @Override
        public List<Permission> expand() {
            return permissions;
        }
    }
}
