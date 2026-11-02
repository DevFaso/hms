package com.example.hms.specification;

import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AppointmentFilterDTO;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AppointmentSpecification {

    private static final String HOSPITAL = "hospital";
    private static final String PATIENT = "patient";
    private static final String STAFF = "staff";
    private static final String DEPARTMENT = "department";
    private static final String CREATED_BY = "createdBy";
    private static final String USER = "user";
    private static final String ID = "id";
    private static final String STATUS = "status";
    private static final String APPOINTMENT_DATE = "appointmentDate";
    private static final String START_TIME = "startTime";
    private static final String END_TIME = "endTime";
    private static final String FIRST_NAME = "firstName";
    private static final String LAST_NAME = "lastName";
    private static final String EMAIL = "email";
    private static final String NAME = "name";
    private static final String REASON = "reason";
    private static final String NOTES = "notes";

    private AppointmentSpecification() {
    }

    public static Specification<Appointment> withFilter(AppointmentFilterDTO filter) {
        return (root, query, cb) -> {
            if (filter == null) {
                return cb.conjunction();
            }

            if (query != null) {
                query.distinct(true);
            }

            List<Predicate> predicates = new ArrayList<>();
            predicates.addAll(buildEqualityPredicates(filter, root, cb));
            predicates.addAll(buildTemporalPredicates(filter, root, cb));
            predicates.addAll(buildTextualPredicates(filter, root, cb, query));

            if (predicates.isEmpty()) {
                return cb.conjunction();
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    public static Specification<Appointment> inHospitals(Set<UUID> hospitalIds) {
        return (root, query, cb) -> {
            if (hospitalIds == null || hospitalIds.isEmpty()) {
                return cb.disjunction();
            }
            return root.get(HOSPITAL).get(ID).in(hospitalIds);
        };
    }

    public static Specification<Appointment> forPatientUser(UUID userId) {
        return (root, query, cb) -> {
            if (userId == null) {
                return cb.disjunction();
            }
            Join<Appointment, Patient> patient = root.join(PATIENT, JoinType.INNER);
            return cb.equal(patient.get(USER).get(ID), userId);
        };
    }

    private static List<Predicate> buildEqualityPredicates(AppointmentFilterDTO filter,
                                                           jakarta.persistence.criteria.Root<Appointment> root,
                                                           jakarta.persistence.criteria.CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();

        if (filter.getHospitalId() != null) {
            predicates.add(cb.equal(root.get(HOSPITAL).get(ID), filter.getHospitalId()));
        }

        if (filter.getDepartmentId() != null) {
            predicates.add(cb.equal(root.get(DEPARTMENT).get(ID), filter.getDepartmentId()));
        }

        if (filter.getPatientId() != null) {
            predicates.add(cb.equal(root.get(PATIENT).get(ID), filter.getPatientId()));
        }

        if (filter.getStaffId() != null) {
            predicates.add(cb.equal(root.get(STAFF).get(ID), filter.getStaffId()));
        }

        if (filter.getCreatedById() != null) {
            predicates.add(cb.equal(root.get(CREATED_BY).get(ID), filter.getCreatedById()));
        }

        if (filter.getStatuses() != null && !filter.getStatuses().isEmpty()) {
            predicates.add(root.get(STATUS).in(filter.getStatuses()));
        }

        return predicates;
    }

    private static List<Predicate> buildTemporalPredicates(AppointmentFilterDTO filter,
                                                          jakarta.persistence.criteria.Root<Appointment> root,
                                                          jakarta.persistence.criteria.CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();

        if (filter.getFromDate() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(APPOINTMENT_DATE), filter.getFromDate()));
        }

        if (filter.getToDate() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get(APPOINTMENT_DATE), filter.getToDate()));
        }

        if (filter.getFromStartTime() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(START_TIME), filter.getFromStartTime()));
        }

        if (filter.getToEndTime() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get(END_TIME), filter.getToEndTime()));
        }

        if (Boolean.TRUE.equals(filter.getUpcomingOnly())) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(APPOINTMENT_DATE), LocalDate.now()));
        }

        return predicates;
    }

    private static List<Predicate> buildTextualPredicates(AppointmentFilterDTO filter,
                                                          jakarta.persistence.criteria.Root<Appointment> root,
                                                          jakarta.persistence.criteria.CriteriaBuilder cb,
                                                          jakarta.persistence.criteria.CriteriaQuery<?> query) {
        List<Predicate> predicates = new ArrayList<>();
        JoinBundle joins = prepareJoinBundle(filter, root);

        addPatientPredicates(filter, cb, predicates, joins);
        addStaffPredicates(filter, cb, predicates, joins);
        addHospitalPredicate(filter, cb, predicates, joins);
        addSearchPredicates(filter, cb, predicates, joins, root, query);

        return predicates;
    }

    private static JoinBundle prepareJoinBundle(AppointmentFilterDTO filter,
                                                jakarta.persistence.criteria.Root<Appointment> root) {
        boolean needPatientJoins = hasText(filter.getPatientEmail())
            || hasText(filter.getPatientName())
            || hasText(filter.getSearch());

        boolean needStaffJoins = hasText(filter.getStaffEmail())
            || hasText(filter.getStaffName())
            || hasText(filter.getSearch());

        boolean needHospitalJoin = hasText(filter.getHospitalName()) || hasText(filter.getSearch());

        Join<Appointment, Patient> patientJoin = null;
        Join<Patient, User> patientUserJoin = null;
        if (needPatientJoins) {
            patientJoin = root.join(PATIENT, JoinType.LEFT);
            patientUserJoin = patientJoin.join(USER, JoinType.LEFT);
        }

        Join<Appointment, Staff> staffJoin = null;
        Join<Staff, User> staffUserJoin = null;
        if (needStaffJoins) {
            staffJoin = root.join(STAFF, JoinType.LEFT);
            staffUserJoin = staffJoin.join(USER, JoinType.LEFT);
        }

        Join<Appointment, Hospital> hospitalJoin = null;
        if (needHospitalJoin) {
            hospitalJoin = root.join(HOSPITAL, JoinType.LEFT);
        }

        return new JoinBundle(patientJoin, patientUserJoin, staffJoin, staffUserJoin, hospitalJoin);
    }

    private static void addPatientPredicates(AppointmentFilterDTO filter,
                                             jakarta.persistence.criteria.CriteriaBuilder cb,
                                             List<Predicate> predicates,
                                             JoinBundle joins) {
        if (hasText(filter.getPatientEmail()) && joins.patientUserJoin() != null) {
            predicates.add(cb.like(cb.lower(joins.patientUserJoin().get(EMAIL)), like(filter.getPatientEmail())));
        }

        if (hasText(filter.getPatientName()) && joins.patientJoin() != null) {
            String like = like(filter.getPatientName());
            predicates.add(cb.or(
                cb.like(cb.lower(joins.patientJoin().get(FIRST_NAME)), like),
                cb.like(cb.lower(joins.patientJoin().get(LAST_NAME)), like)
            ));
        }
    }

    private static void addStaffPredicates(AppointmentFilterDTO filter,
                                           jakarta.persistence.criteria.CriteriaBuilder cb,
                                           List<Predicate> predicates,
                                           JoinBundle joins) {
        if (hasText(filter.getStaffEmail()) && joins.staffUserJoin() != null) {
            predicates.add(cb.like(cb.lower(joins.staffUserJoin().get(EMAIL)), like(filter.getStaffEmail())));
        }

        if (hasText(filter.getStaffName()) && joins.staffUserJoin() != null) {
            String like = like(filter.getStaffName());
            predicates.add(cb.or(
                cb.like(cb.lower(joins.staffUserJoin().get(FIRST_NAME)), like),
                cb.like(cb.lower(joins.staffUserJoin().get(LAST_NAME)), like)
            ));
        }
    }

    private static void addHospitalPredicate(AppointmentFilterDTO filter,
                                             jakarta.persistence.criteria.CriteriaBuilder cb,
                                             List<Predicate> predicates,
                                             JoinBundle joins) {
        if (hasText(filter.getHospitalName()) && joins.hospitalJoin() != null) {
            predicates.add(cb.like(cb.lower(joins.hospitalJoin().get(NAME)), like(filter.getHospitalName())));
        }
    }

    private static void addSearchPredicates(AppointmentFilterDTO filter,
                                            jakarta.persistence.criteria.CriteriaBuilder cb,
                                            List<Predicate> predicates,
                                            JoinBundle joins,
                                            jakarta.persistence.criteria.Root<Appointment> root,
                                            jakarta.persistence.criteria.CriteriaQuery<?> query) {
        if (!hasText(filter.getSearch())) {
            return;
        }

        String localLike = like(filter.getSearch());
        List<Predicate> searchPredicates = new ArrayList<>();
        searchPredicates.add(cb.like(cb.lower(root.get(REASON)), localLike));
        searchPredicates.add(cb.like(cb.lower(root.get(NOTES)), localLike));

        if (joins.patientJoin() != null) {
            searchPredicates.add(cb.like(cb.lower(joins.patientJoin().get(FIRST_NAME)), localLike));
            searchPredicates.add(cb.like(cb.lower(joins.patientJoin().get(LAST_NAME)), localLike));
            addPatientIdentifierPredicates(filter.getSearch().trim(), cb, searchPredicates, joins.patientJoin(), localLike);
            if (query != null) {
                searchPredicates.add(mrnAtTheAppointmentsHospital(cb, query, root, joins.patientJoin(), localLike));
            }
        }
        if (joins.patientUserJoin() != null) {
            searchPredicates.add(cb.like(cb.lower(joins.patientUserJoin().get(EMAIL)), localLike));
        }
        if (joins.staffUserJoin() != null) {
            searchPredicates.add(cb.like(cb.lower(joins.staffUserJoin().get(FIRST_NAME)), localLike));
            searchPredicates.add(cb.like(cb.lower(joins.staffUserJoin().get(LAST_NAME)), localLike));
            searchPredicates.add(cb.like(cb.lower(joins.staffUserJoin().get(EMAIL)), localLike));
        }
        if (joins.hospitalJoin() != null) {
            searchPredicates.add(cb.like(cb.lower(joins.hospitalJoin().get(NAME)), localLike));
        }

        if (!searchPredicates.isEmpty()) {
            predicates.add(cb.or(searchPredicates.toArray(new Predicate[0])));
        }
    }

    /** The characters a stored phone may carry that {@code PatientMatchKeys.DIGITS_SQL_OPEN} strips. */
    private static final List<String> PHONE_SEPARATORS = List.of(" ", "+", "-", ".", "(", ")", "/");

    /**
     * QA D1310 class (round 28): a free-text search box that finds a patient
     * by name only. The appointment list's search now reads the term as the
     * patient-search router does (QA D1274): the patient's own e-mail, a birth
     * date in any accepted format, and a number against either phone.
     */
    private static void addPatientIdentifierPredicates(String term,
                                                       jakarta.persistence.criteria.CriteriaBuilder cb,
                                                       List<Predicate> searchPredicates,
                                                       Join<Appointment, Patient> patient,
                                                       String localLike) {
        searchPredicates.add(cb.like(cb.lower(patient.get(EMAIL)), localLike));
        LocalDate birthDate = com.example.hms.utility.PatientMatchKeys.birthDate(term);
        if (birthDate != null) {
            searchPredicates.add(cb.equal(patient.get("dateOfBirth"), birthDate));
            return;
        }
        if (com.example.hms.utility.PatientMatchKeys.looksLikePhone(term)) {
            String phone = com.example.hms.utility.PatientMatchKeys.phoneContainsPattern(term);
            searchPredicates.add(cb.like(digitsOf(cb, patient.get("phoneNumberPrimary")), phone));
            searchPredicates.add(cb.like(digitsOf(cb, patient.get("phoneNumberSecondary")), phone));
        }
    }

    /**
     * QA D1322 (round 29): the appointment list searched by e-mail, phone and
     * birth date but not by MRN, the number the desk reads off the card. The
     * MRN is the patient's registration at the appointment's own hospital (an
     * MRN is unique per hospital), matched as the invoice search does.
     */
    private static Predicate mrnAtTheAppointmentsHospital(jakarta.persistence.criteria.CriteriaBuilder cb,
                                                          jakarta.persistence.criteria.CriteriaQuery<?> query,
                                                          jakarta.persistence.criteria.Root<Appointment> root,
                                                          Join<Appointment, Patient> patient,
                                                          String localLike) {
        jakarta.persistence.criteria.Subquery<Integer> registration = query.subquery(Integer.class);
        jakarta.persistence.criteria.Root<com.example.hms.model.PatientHospitalRegistration> r =
            registration.from(com.example.hms.model.PatientHospitalRegistration.class);
        registration.select(cb.literal(1)).where(
            cb.equal(r.get("patient"), patient),
            cb.equal(r.get("hospital"), root.get("hospital")),
            cb.like(cb.lower(r.get("mrn")), localLike));
        return cb.exists(registration);
    }

    /** A stored phone reduced to its digits, the same separators as the shared SQL. */
    private static jakarta.persistence.criteria.Expression<String> digitsOf(
            jakarta.persistence.criteria.CriteriaBuilder cb, jakarta.persistence.criteria.Expression<String> phone) {
        jakarta.persistence.criteria.Expression<String> digits = phone;
        for (String separator : PHONE_SEPARATORS) {
            digits = cb.function("REPLACE", String.class, digits, cb.literal(separator), cb.literal(""));
        }
        return digits;
    }

    private record JoinBundle(Join<Appointment, Patient> patientJoin,
                              Join<Patient, User> patientUserJoin,
                              Join<Appointment, Staff> staffJoin,
                              Join<Staff, User> staffUserJoin,
                              Join<Appointment, Hospital> hospitalJoin) {
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String like(String value) {
        Objects.requireNonNull(value, "value");
        return "%" + value.trim().toLowerCase() + "%";
    }
}
