package com.example.hms.patient.dto;

public class MedicalHistoryDto {
    private String allergies;
    private String conditions;
    private String medications;
    private String surgeries;
    private String notes;

    public String getAllergies() {
        return allergies;
    }

    public void setAllergies(String allergies) {
        this.allergies = allergies;
    }

    public String getConditions() {
        return conditions;
    }

    public void setConditions(String conditions) {
        this.conditions = conditions;
    }

    public String getMedications() {
        return medications;
    }

    public void setMedications(String medications) {
        this.medications = medications;
    }

    public String getSurgeries() {
        return surgeries;
    }

    public void setSurgeries(String surgeries) {
        this.surgeries = surgeries;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
