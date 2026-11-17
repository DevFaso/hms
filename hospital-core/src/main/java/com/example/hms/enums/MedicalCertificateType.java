package com.example.hms.enums;

/** QA D961: what a certificate issued from a visit attests. */
public enum MedicalCertificateType {
    /** Arrêt de travail: the patient's condition requires N days off work, from a start date. */
    SICK_LEAVE,
    /** Certificat de présence: the patient was seen in consultation on the visit day. */
    ATTENDANCE,
    /** Certificat médical: a statement in the doctor's words (the remarks), dated and signed. */
    GENERAL
}
