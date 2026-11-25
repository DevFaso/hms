package com.example.hms.security.chart;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * QA D3863: what is left of a restricted chart's records inside an answer the
 * caller may otherwise read, as JSON, whatever DTO built it.
 *
 * <p><b>Lists keep the row, masked</b> (the decision recorded on the PR). A row
 * is the chart's when its {@code patientId}, or its {@code patient.id}, is a
 * blocked patient. Leaving it out would make an occupied bed look free on the
 * bed board and a visit vanish from the day's list; so the row stays, keeps
 * who, where, when and status (an allow-list, {@link #keepsInRow}), loses
 * everything else, nested records included, and says
 * {@code "chartRestricted": true} so the portal can offer break-the-glass.
 *
 * <p>A patient record itself (an object whose {@code id} is the blocked
 * patient) keeps its identity: the name, the numbers, the contact details the
 * desk and the chart header need. Its clinical fields go, by the same
 * vocabulary {@code ReceptionistClinicalReadGuardTest} holds for the desk.
 */
public final class RestrictedChartMasker {

    /** The flag a masked object carries. */
    public static final String MARKER = "chartRestricted";
    static final String PATIENT_ID = "patientId";
    static final String PATIENT = "patient";
    static final String ID = "id";

    /**
     * A field name that reads as clinical: never kept in a masked row, even
     * where the shape rules below would. Deliberately broad (a masked row
     * that loses a harmless field costs nothing).
     */
    static final Pattern CLINICAL_IN_ROW = Pattern.compile(
        "(?i).*(chronic|condition|risk|flag|allerg|diagnos|vital|medic|prescri|result|imaging|narrative|organism"
            + "|acuity|esi|soap|assess|complaint|clinical|histor|careteam|reason|note|instruction|treat|symptom"
            + "|immuni|vaccin|procedure|blood|gestat|antibody|triage|isolation|description|summary|course|test"
            + "|specimen|culture|dose|drug|surg|lmp|edd|pregnan|deliver|labo|death|deceas|onset|icd|loinc|snomed"
            + "|cpt|code|diet|pain|score|sever|text|comment|finding|impression|plan|order|sensitiv|hiv|program"
            + "|problem|alert|critical|urgen|priority|precaution|reaction|allergy|transfus|newborn|apgar|fetal"
            + "|study|modality|body|indication|content|title|subject|message|oxygen|saturation|spo2|pulse|heart"
            + "|pressure|weight|height|temp|glucose).*");

    /**
     * The receptionist vocabulary (RCPT-CLIN): off a patient's identity. The death flags stay, as for
     * the desk: they decide whether care may be given at all.
     */
    static final Pattern CLINICAL_ON_IDENTITY = Pattern.compile(
        "(?i).*(chronic|condition|risk|flag|allerg|diagnos|vital|medication|prescri|labresult|imaging|narrative"
            + "|organism|acuity|esiscore|soap|assessment|chiefcomplaint|problem|complaint|clinical|historysummary"
            + "|careteamnotes|reason|notes?|followupinstruction|treatment|symptom|immuniz|procedure|bloodtype"
            + "|gestation|bloodgroup|abogroup|rhfactor|antibody|triage|isolation|highrisk|encounterdescription"
            + "|visitdescription|hospitalcourse|encountersummary|sensitiv|program|pregnan).*");

    /** Off a patient's identity by exact name: the header's last vital signs. */
    static final Set<String> VITALS_ON_IDENTITY = Set.of("hr", "bp", "spo2", "rr", "temp", "temperature", "weight",
        "height", "bmi", "lastvitals");

    /** Who, where, organisation: the last word of a kept id, name, status or type. */
    private static final List<String> STRUCTURAL = List.of(
        "patient", "encounter", "visit", "admission", "appointment", "hospital", "department", "ward", "unit",
        "room", "bed", "location", "facility", "organization", "organisation", "staff", "user", "doctor",
        "provider", "practitioner", "physician", "nurse", "midwife", "attending", "clinician", "author",
        "assignment", "registration", "createdby", "updatedby", "checkin", "queue");

    private static final Set<String> IDENTITY = Set.of("id", "uuid", "mrn", "firstname", "lastname", "middlename",
        "fullname", "displayname", "gender", "sex", "dateofbirth", "birthdate", "age", "status", "state", "type",
        "createdby", "updatedby", MARKER.toLowerCase(Locale.ROOT));

    private static final List<String> TEMPORAL_SUFFIXES = List.of("at", "date", "time", "timestamp", "datetime");
    /** Longest first: {@code patientDisplayName} is read as "patient" + "displayname", not "patientdisplay" + "name". */
    private static final List<String> LABEL_SUFFIXES = List.of("displayname", "fullname", "number", "status",
        "label", "name", "type", "ids", "mrn", "id");

    private RestrictedChartMasker() {
    }

    /** Every UUID that may name a patient in this tree: {@code patientId} values and object {@code id}s. */
    public static void collectCandidateIds(JsonNode node, Set<UUID> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            addUuid(node.get(PATIENT_ID), out);
            addUuid(node.get(ID), out);
            for (Map.Entry<String, JsonNode> child : node.properties()) {
                if (child.getValue().isContainer()) {
                    collectCandidateIds(child.getValue(), out);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectCandidateIds(element, out);
            }
        }
    }

    /** The patient an object is a record of: its {@code patientId}, else its {@code patient.id}. */
    public static UUID patientOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        UUID direct = uuid(node.get(PATIENT_ID));
        if (direct != null) {
            return direct;
        }
        JsonNode patient = node.get(PATIENT);
        return patient != null && patient.isObject() ? uuid(patient.get(ID)) : null;
    }

    /** True when the object is the patient record of one of {@code blocked}. */
    public static boolean isIdentityOf(JsonNode node, Set<UUID> blocked) {
        UUID id = node != null && node.isObject() ? uuid(node.get(ID)) : null;
        return id != null && blocked.contains(id);
    }

    /** Masks, in place, every record of a {@code blocked} patient in the tree. */
    public static void mask(JsonNode node, Set<UUID> blocked) {
        if (node == null || blocked.isEmpty()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                mask(element, blocked);
            }
            return;
        }
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        // The patient record first: PatientMapper fills both its id and its patientId with the
        // patient's own id, and the record keeps its identity (name, contact, status), not a row's.
        if (isIdentityOf(object, blocked)) {
            shapeIdentity(object, blocked);
            return;
        }
        UUID owner = patientOf(object);
        if (owner != null && blocked.contains(owner)) {
            maskRow(object, blocked);
            return;
        }
        for (Map.Entry<String, JsonNode> child : object.properties()) {
            if (child.getValue().isContainer()) {
                mask(child.getValue(), blocked);
            }
        }
    }

    /** A row of a blocked chart: who, where, when and status; the patient's identity; the marker. */
    static void maskRow(ObjectNode row, Set<UUID> blocked) {
        List<String> keep = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : row.properties()) {
            if (keepsInRow(field.getKey(), field.getValue())) {
                keep.add(field.getKey());
            }
        }
        List<String> emptied = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : row.properties()) {
            if (field.getValue().isArray() && !keep.contains(field.getKey())) {
                emptied.add(field.getKey());
            }
        }
        JsonNode patient = row.get(PATIENT);
        if (patient instanceof ObjectNode patientObject) {
            shapeIdentity(patientObject, blocked);
            keep.add(PATIENT);
        }
        row.retain(keep);
        // The lists stay lists, empty: a screen that reads their length keeps working.
        emptied.forEach(row::putArray);
        row.put(MARKER, true);
    }

    /** A patient's own record: identity stays, clinical fields go, nested records are masked. */
    static void shapeIdentity(ObjectNode patient, Set<UUID> blocked) {
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : patient.properties()) {
            String name = field.getKey();
            if (CLINICAL_ON_IDENTITY.matcher(name).matches()
                || VITALS_ON_IDENTITY.contains(name.toLowerCase(Locale.ROOT))) {
                drop.add(name);
            } else if (field.getValue().isContainer()) {
                mask(field.getValue(), blocked);
            }
        }
        for (String name : drop) {
            if (patient.get(name).isArray()) {
                patient.putArray(name);
            } else {
                patient.remove(name);
            }
        }
        patient.put(MARKER, true);
    }

    /** The masked-row allow-list: a scalar whose name is an identifier, a who/where label, a status, a type or a time. */
    static boolean keepsInRow(String name, JsonNode value) {
        if (name == null || CLINICAL_IN_ROW.matcher(name).matches()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (MARKER.equalsIgnoreCase(name)) {
            return true;
        }
        boolean scalar = value == null || value.isValueNode();
        if (isTemporal(lower)) {
            // A time may serialise as an array of numbers.
            return scalar || (value.isArray() && allNumbers(value));
        }
        if (!scalar || (value.isBoolean())) {
            return false;
        }
        if (IDENTITY.contains(lower)) {
            return true;
        }
        for (String suffix : LABEL_SUFFIXES) {
            if (lower.endsWith(suffix) && lower.length() > suffix.length()) {
                String stem = lower.substring(0, lower.length() - suffix.length());
                return STRUCTURAL.stream().anyMatch(stem::endsWith);
            }
        }
        return false;
    }

    private static boolean isTemporal(String lower) {
        return TEMPORAL_SUFFIXES.stream().anyMatch(s -> lower.endsWith(s) && lower.length() > s.length());
    }

    private static boolean allNumbers(JsonNode array) {
        for (JsonNode element : array) {
            if (!element.isNumber()) {
                return false;
            }
        }
        return true;
    }

    private static void addUuid(JsonNode node, Set<UUID> out) {
        UUID id = uuid(node);
        if (id != null) {
            out.add(id);
        }
    }

    static UUID uuid(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        String text = node.stringValue();
        if (text == null || text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
