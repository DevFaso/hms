package com.example.hms.enums;

public enum JobTitle {
    DOCTOR("Doctor"),
    PHYSICIAN("Physician"),
    NURSE_PRACTITIONER("Nurse Practitioner"),
    NURSE("Nurse"),
    MIDWIFE("Midwife"),
    HOSPITAL_ADMIN("Hospital Admin"),
    PATIENT("Patient"),
    VISITOR("Visitor"),
    SUPER_ADMIN("Super Admin"),
    ADMINISTRATIVE_STAFF("Administrative Staff"),
    TECHNICIAN("Technician"),
    PHARMACIST("Pharmacist"),
    LAB_TECHNICIAN("Lab Technician"),
    RECEPTIONIST("Receptionist"),
    SURGEON("Surgeon"),
    HOSPITAL_ADMINISTRATOR("Hospital Administrator"),
    LABORATORY_SCIENTIST("Laboratory Scientist"),
    RADIOLOGIST("Radiologist"),
    ANESTHESIOLOGIST("Anesthesiologist"),
    PHYSIOTHERAPIST("Physiotherapist"),
    PSYCHOLOGIST("Psychologist"),
    SOCIAL_WORKER("Social Worker"),
    BILLING_SPECIALIST("Billing Specialist"),
    IT_SUPPORT("IT Support"),
    CLEANING_STAFF("Cleaning Staff"),
    SECURITY_PERSONNEL("Security Personnel"),
    HUMAN_RESOURCES("Human Resources"),
    ADMINISTRATIVE_ASSISTANT("Administrative Assistant");

    private final String title;

    JobTitle(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}

