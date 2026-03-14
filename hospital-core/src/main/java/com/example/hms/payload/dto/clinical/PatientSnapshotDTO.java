package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Compact patient snapshot for the slide-out drawer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientSnapshotDTO {

    private UUID patientId;
    private String name;
    private int age;
    private String sex;
    private String mrn;
    private List<String> allergies;
    private String codeStatus;
    private List<String> activeDiagnoses;
    private List<MedicationItem> activeMedications;
    private List<VitalItem> recentVitals;
    private List<LabItem> latestLabs;
    private List<OrderItem> pendingOrders;
    private List<NoteItem> recentNotes;
    private List<CareTeamMember> careTeam;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MedicationItem {
        private String name;
        private String dose;
        private String frequency;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VitalItem {
        private String type;
        private String value;
        private String timestamp;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LabItem {
        private String test;
        private String value;
        private String flag;
        private String date;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItem {
        private String type;
        private String description;
        private String orderedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NoteItem {
        private String author;
        private String type;
        private String date;
        private String snippet;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CareTeamMember {
        private String role;
        private String name;
    }
}
