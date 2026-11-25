package com.example.hms.security.chart;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.PatientResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("D3863: a restricted chart's records are masked inside any answer")
class RestrictedChartMaskerTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final UUID RESTRICTED = UUID.fromString("0d1e2f30-0000-4000-8000-000000000001");
    private static final UUID OPEN = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static JsonNode tree(String json) {
        return JSON.readTree(json.replace('\'', '"'));
    }

    @Test
    @DisplayName("a visit row of a restricted chart keeps who, where, when and status, and loses the diagnosis")
    void aVisitRowIsMasked() {
        JsonNode list = tree("""
            [{'id':'aaaaaaaa-0000-0000-0000-000000000001','patientId':'%s','patientName':'Test Patient',
              'staffName':'Dr A','departmentName':'Médecine','status':'IN_PROGRESS','encounterType':'CONSULTATION',
              'encounterDate':'2026-11-25T10:00:00','code':'ENC-1','notes':'DX-SENSITIVE note',
              'chiefComplaint':'fatigue','dischargeDiagnoses':'B20','esiScore':3,'notePendingSignature':true,
              'note':{'assessment':'DX-SENSITIVE','plan':'ART'},'noteEntries':[{'assessment':'DX-SENSITIVE'}]},
             {'id':'aaaaaaaa-0000-0000-0000-000000000002','patientId':'%s','notes':'open chart',
              'chiefComplaint':'cough'}]
            """.formatted(RESTRICTED, OPEN));

        RestrictedChartMasker.mask(list, Set.of(RESTRICTED));

        JsonNode masked = list.get(0);
        assertThat(masked.propertyNames()).containsExactlyInAnyOrder("id", "patientId", "patientName", "staffName",
            "departmentName", "status", "encounterType", "encounterDate", "noteEntries", "chartRestricted");
        assertThat(masked.get("noteEntries").isArray()).isTrue();
        assertThat(masked.get("noteEntries")).isEmpty();
        assertThat(masked.get("chartRestricted").asBoolean()).isTrue();
        assertThat(masked.toString()).doesNotContain("DX-SENSITIVE").doesNotContain("B20").doesNotContain("fatigue");

        JsonNode untouched = list.get(1);
        assertThat(untouched.get("notes").asString()).isEqualTo("open chart");
        assertThat(untouched.has("chartRestricted")).isFalse();
    }

    @Test
    @DisplayName("a row attributed through a nested patient keeps that patient's identity only")
    void aRowWithANestedPatient() {
        JsonNode row = tree("""
            {'id':'aaaaaaaa-0000-0000-0000-000000000003','title':'Note: DX-SENSITIVE',
             'patient':{'id':'%s','firstName':'Test','lastName':'Patient','mrn':'MRN-0001',
                        'allergies':['penicillin'],'bloodType':'O+','chronicConditions':['DX-SENSITIVE']}}
            """.formatted(RESTRICTED));

        RestrictedChartMasker.mask(row, Set.of(RESTRICTED));

        assertThat(row.has("title")).isFalse();
        JsonNode patient = row.get("patient");
        assertThat(patient.get("firstName").asString()).isEqualTo("Test");
        assertThat(patient.get("mrn").asString()).isEqualTo("MRN-0001");
        assertThat(patient.has("bloodType")).isFalse();
        assertThat(patient.get("allergies")).isEmpty();
        assertThat(patient.get("chronicConditions")).isEmpty();
        assertThat(row.toString()).doesNotContain("DX-SENSITIVE").doesNotContain("penicillin");
    }

    @Test
    @DisplayName("the patient record itself keeps its identity and the restricted flag, without clinical fields")
    void thePatientRecordKeepsItsIdentity() {
        JsonNode patient = tree("""
            {'id':'%s','firstName':'Test','lastName':'Patient','phoneNumberPrimary':'+226 00',
             'address':{'city':'Ouagadougou'},'chartRestricted':true,'chartRestrictionReason':'VIP',
             'medicalHistorySummary':'DX-SENSITIVE history','lastVitals':{'hr':80},'hr':80,'bp':'120/80',
             'flags':['DX-SENSITIVE'],'careTeamNotes':'sensitive'}
            """.formatted(RESTRICTED));

        RestrictedChartMasker.mask(patient, Set.of(RESTRICTED));

        assertThat(patient.get("firstName").asString()).isEqualTo("Test");
        assertThat(patient.get("phoneNumberPrimary").asString()).isEqualTo("+226 00");
        assertThat(patient.get("address").get("city").asString()).isEqualTo("Ouagadougou");
        assertThat(patient.get("chartRestricted").asBoolean()).isTrue();
        assertThat(patient.has("chartRestrictionReason")).isFalse();
        assertThat(patient.has("medicalHistorySummary")).isFalse();
        assertThat(patient.has("lastVitals")).isFalse();
        assertThat(patient.has("hr")).isFalse();
        assertThat(patient.has("bp")).isFalse();
        assertThat(patient.has("careTeamNotes")).isFalse();
        assertThat(patient.get("flags")).isEmpty();
    }

    @Test
    @DisplayName("a real patient record (id and patientId both the patient) keeps identity, contact and status")
    void aMappedPatientRecordKeepsItsIdentity() {
        Patient patient = Patient.builder()
            .firstName("Test").lastName("Patient")
            .phoneNumberPrimary("+226 00 00 00 01")
            .addressLine1("Secteur 1").city("Ouagadougou")
            .emergencyContactName("Contact Person").emergencyContactPhone("+226 00 00 00 02")
            .bloodType("O+").medicalHistorySummary("DX-SENSITIVE history").careTeamNotes("DX-SENSITIVE note")
            .build();
        patient.setId(RESTRICTED);
        patient.setChartRestricted(true);
        PatientResponseDTO dto = new PatientMapper(TestMessageSources.bundles()).toPatientDTO(patient);
        assertThat(dto.getId()).isEqualTo(RESTRICTED);
        JsonNode record = JsonMapper.builder().findAndAddModules().build().valueToTree(dto);
        assertThat(record.get("patientId").asString()).as("the mapper fills both ids").isEqualTo(RESTRICTED.toString());

        RestrictedChartMasker.mask(record, Set.of(RESTRICTED));

        assertThat(record.get("firstName").asString()).isEqualTo("Test");
        assertThat(record.get("phoneNumberPrimary").asString()).isEqualTo("+226 00 00 00 01");
        assertThat(record.get("addressLine1").asString()).isEqualTo("Secteur 1");
        assertThat(record.get("emergencyContactName").asString()).isEqualTo("Contact Person");
        assertThat(record.get("emergencyContactPhone").asString()).isEqualTo("+226 00 00 00 02");
        assertThat(record.get("active").asBoolean()).as("not shown as inactive").isTrue();
        assertThat(record.has("deceased")).isTrue();
        assertThat(record.get("chartRestricted").asBoolean()).isTrue();
        assertThat(record.has("bloodType")).isFalse();
        assertThat(record.toString()).doesNotContain("DX-SENSITIVE");
    }

    @Test
    @DisplayName("rows nested in a board are masked where they are; the board itself stays")
    void boardsKeepTheirShape() {
        JsonNode board = tree("""
            {'wards':[{'name':'Ward A','beds':[
                {'bedNumber':'A1','occupant':{'patientId':'%s','patientName':'Test','primaryDiagnosis':'DX-SENSITIVE',
                                              'admittedAt':'2026-11-25T08:00:00'}},
                {'bedNumber':'A2','occupant':{'patientId':'%s','primaryDiagnosis':'asthma'}}]}]}
            """.formatted(RESTRICTED, OPEN));

        RestrictedChartMasker.mask(board, Set.of(RESTRICTED));

        JsonNode beds = board.get("wards").get(0).get("beds");
        assertThat(beds).hasSize(2);
        JsonNode occupant = beds.get(0).get("occupant");
        assertThat(occupant.get("patientName").asString()).isEqualTo("Test");
        assertThat(occupant.get("admittedAt").asString()).isEqualTo("2026-11-25T08:00:00");
        assertThat(occupant.has("primaryDiagnosis")).isFalse();
        assertThat(beds.get(1).get("occupant").get("primaryDiagnosis").asString()).isEqualTo("asthma");
    }

    @Test
    @DisplayName("the candidate ids are every patientId and every object id, nested included")
    void candidateIds() {
        JsonNode body = tree("""
            {'content':[{'id':'aaaaaaaa-0000-0000-0000-000000000001','patientId':'%s'}],
             'patient':{'id':'%s'},'count':2,'label':'not-a-uuid'}
            """.formatted(RESTRICTED, OPEN));
        Set<UUID> ids = new HashSet<>();
        RestrictedChartMasker.collectCandidateIds(body, ids);
        assertThat(ids).containsExactlyInAnyOrder(RESTRICTED, OPEN,
            UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"));
        assertThat(RestrictedChartMasker.patientOf(body)).isEqualTo(OPEN);
    }

    @Test
    @DisplayName("the row allow-list keeps identifiers, who and where, status, type and times, and nothing clinical")
    void theAllowList() {
        JsonNode text = tree("'x'");
        assertThat(RestrictedChartMasker.keepsInRow("patientId", text)).isTrue();
        assertThat(RestrictedChartMasker.keepsInRow("attendingDoctorName", text)).isTrue();
        assertThat(RestrictedChartMasker.keepsInRow("patientDisplayName", text)).isTrue();
        assertThat(RestrictedChartMasker.keepsInRow("roomNumber", text)).isTrue();
        assertThat(RestrictedChartMasker.keepsInRow("admissionStatus", text)).isTrue();
        assertThat(RestrictedChartMasker.keepsInRow("createdAt", text)).isTrue();
        assertThat(RestrictedChartMasker.keepsInRow("diagnosisName", text)).isFalse();
        assertThat(RestrictedChartMasker.keepsInRow("hivStatus", text)).isFalse();
        assertThat(RestrictedChartMasker.keepsInRow("testName", text)).isFalse();
        assertThat(RestrictedChartMasker.keepsInRow("medicationName", text)).isFalse();
        assertThat(RestrictedChartMasker.keepsInRow("icd10Code", text)).isFalse();
        assertThat(RestrictedChartMasker.keepsInRow("oxygenSat", tree("97"))).isFalse();
        assertThat(RestrictedChartMasker.keepsInRow("lmpDate", text)).isFalse();
        assertThat(RestrictedChartMasker.keepsInRow("critical", tree("true"))).isFalse();
        assertThat(RestrictedChartMasker.keepsInRow("summary", text)).isFalse();
    }
}
