-- V263 (QA D1393, round 33): cancelling an admission closed nothing on its stay
-- encounter, so the encounter stayed open for ever and took every later order
-- of the patient (PrescriptionServiceImpl.resolveEncounterContext), each then
-- refused at the pharmacy as an order of an ended stay. The code now ends the
-- encounter at the cancel and the lookup skips ended stays; this repairs the
-- rows those cancels left behind. Repaired in the data rather than only read
-- around, because an open encounter of a stay that never happened also sits on
-- worklists and trackers, and a misfiled order would stay refused for ever.
--
-- 1. An order written AFTER the cancel is an outpatient order filed on the
--    wrong encounter. It moves, at any status, to its own finished outpatient
--    anchor, as resolveEncounterContext makes one today (a COMPLETED
--    CONSULTATION with Encounter.PRESCRIPTION_ANCHOR_NOTE, its prescriber's
--    staff row and assignment). Such an order, stuck at the pharmacy, BECOMES
--    DISPENSABLE: its status is not touched. One audit row each (ids only).
--    The cancel's time is admissions.cancelled_at (V260); a cancel from before
--    V260 has none, so the admission's updated_at stands in: nothing writes a
--    cancelled admission after its cancel.
--
-- 2. An order written BEFORE the cancel, still live and not marked take-home,
--    has no stay to belong to. It ends as the cancel now ends it
--    (StayOrderDiscontinuation.discontinueOrdersOfCancelledStay): DISCONTINUED,
--    with a fixed reason (no PHI), its open back order CANCELLED, one audit row.
--    As V233: an order holding a prepared fill or an open partner offer is left
--    to the application, which alone can put stock back or tell the partner.
--    EndedStayOrderGuard refuses it, and it ends when that hold goes
--    (StayOrderDiscontinuation.discontinueIfStayEnded). A take-home order is
--    left alone.
--
-- 3. The encounter of a cancelled admission that is still open becomes
--    CANCELLED, as the cancel now leaves it, with one audit row. No bill and no
--    discharge summary: a cancelled stay never happened.
--
-- Only encounters whose admission is CANCELLED are read: a discharged stay's
-- orders were V233's. Idempotent: a second run finds nothing, since step 1
-- moved its orders, step 2 ended its orders and step 3 closed the encounters
-- (an order step 2 leaves for a hold is counted again, and written nowhere).
-- One DO block, splitStatements="false".
DO $$
DECLARE
    moved_count INTEGER;
    ended_count INTEGER;
    back_order_count INTEGER;
    held_count INTEGER;
    closed_count INTEGER;
BEGIN
    WITH misfiled AS (
        SELECT p.id AS rx_id, p.patient_id, p.staff_id, p.hospital_id, p.assignment_id,
               p.created_at, gen_random_uuid() AS anchor_id
          FROM clinical.prescriptions p
          JOIN clinical.encounters e ON e.id = p.encounter_id
          JOIN public.admissions a ON a.id = e.admission_id
         WHERE a.status = 'CANCELLED'
           AND p.created_at > COALESCE(a.cancelled_at, a.updated_at)
    ), anchors AS (
        INSERT INTO clinical.encounters (
            id, created_at, updated_at, patient_id, staff_id, hospital_id, assignment_id,
            department_id, encounter_type, encounter_date, checkout_timestamp, status, notes, code)
        SELECT m.anchor_id, now(), now(), m.patient_id, m.staff_id, m.hospital_id, m.assignment_id,
               (SELECT d.id
                  FROM hospital.staff s
                  JOIN hospital.departments d ON d.id = s.department_id
                 WHERE s.id = m.staff_id AND d.hospital_id = m.hospital_id),
               'CONSULTATION', m.created_at, m.created_at, 'COMPLETED',
               'Auto-generated for prescription entry',
               'ENC-V263-' || upper(substr(replace(m.anchor_id::text, '-', ''), 1, 20))
          FROM misfiled m
        RETURNING 1
    ), moved AS (
        UPDATE clinical.prescriptions p
           SET encounter_id = m.anchor_id,
               updated_at = now()
          FROM misfiled m
         WHERE p.id = m.rx_id
        RETURNING p.id, p.hospital_id
    ), audited AS (
        INSERT INTO support.audit_event_logs (
            id, created_at, updated_at, event_timestamp,
            user_id, actor_type, actor_label,
            event_type, status, event_description, details,
            target_entity_type, target_resource_id, hospital_name)
        SELECT gen_random_uuid(), now(), now(), now(),
               NULL, 'SYSTEM', 'Migration V263',
               'PRESCRIPTION_UPDATED', 'SUCCESS',
               'Order written after its admission was cancelled moved to an outpatient anchor by migration V263 (QA D1393)',
               'encounter moved; status unchanged',
               'Prescription', x.id::text, h.name
          FROM moved x
          LEFT JOIN hospital.hospitals h ON h.id = x.hospital_id
        RETURNING 1
    )
    SELECT count(*) INTO moved_count FROM audited;

    WITH candidates AS (
        SELECT p.id, p.status AS old_status
          FROM clinical.prescriptions p
          JOIN clinical.encounters e ON e.id = p.encounter_id
          JOIN public.admissions a ON a.id = e.admission_id
         WHERE a.status = 'CANCELLED'
           AND p.take_home = FALSE
           AND p.status IN ('DRAFT', 'PENDING_SIGNATURE', 'SIGNED', 'TRANSMITTED',
                            'TRANSMISSION_FAILED', 'PENDING_CLARIFICATION',
                            'PENDING_STOCK', 'PARTIALLY_FILLED', 'PARTNER_REJECTED')
    ), held AS (
        SELECT c.id
          FROM candidates c
         WHERE EXISTS (SELECT 1
                         FROM clinical.dispenses d
                        WHERE d.prescription_id = c.id
                          AND d.status = 'PENDING')
            OR EXISTS (SELECT 1
                         FROM clinical.prescription_routing_decisions r
                        WHERE r.prescription_id = c.id
                          AND r.routing_type = 'PARTNER'
                          AND r.status IN ('PENDING', 'ACCEPTED'))
    ), ended AS (
        UPDATE clinical.prescriptions p
           SET status = 'DISCONTINUED',
               withdrawal_reason = 'Arrêtée : l''hospitalisation a été annulée',
               updated_at = now()
          FROM candidates c
         WHERE p.id = c.id
           AND NOT EXISTS (SELECT 1 FROM held h WHERE h.id = c.id)
        RETURNING p.id, p.hospital_id, c.old_status
    ), back_orders_closed AS (
        UPDATE clinical.prescription_routing_decisions r
           SET status = 'CANCELLED',
               updated_at = now()
          FROM ended x
         WHERE r.prescription_id = x.id
           AND r.routing_type = 'BACKORDER'
           AND r.status IN ('PENDING', 'ACCEPTED')
        RETURNING 1
    ), audited AS (
        INSERT INTO support.audit_event_logs (
            id, created_at, updated_at, event_timestamp,
            user_id, actor_type, actor_label,
            event_type, status, event_description, details,
            target_entity_type, target_resource_id, hospital_name)
        SELECT gen_random_uuid(), now(), now(), now(),
               NULL, 'SYSTEM', 'Migration V263',
               'PRESCRIPTION_DISCONTINUED', 'SUCCESS',
               'Order of a cancelled admission discontinued by migration V263 (QA D1393)',
               'status ' || x.old_status || ' -> DISCONTINUED; not take-home',
               'Prescription', x.id::text, h.name
          FROM ended x
          LEFT JOIN hospital.hospitals h ON h.id = x.hospital_id
        RETURNING 1
    )
    SELECT (SELECT count(*) FROM audited),
           (SELECT count(*) FROM back_orders_closed),
           (SELECT count(*) FROM held)
      INTO ended_count, back_order_count, held_count;

    WITH closed AS (
        UPDATE clinical.encounters e
           SET status = 'CANCELLED',
               checkout_timestamp = COALESCE(e.checkout_timestamp, a.cancelled_at, a.updated_at),
               updated_at = now()
          FROM public.admissions a
         WHERE a.id = e.admission_id
           AND a.status = 'CANCELLED'
           AND e.status NOT IN ('COMPLETED', 'CANCELLED', 'LEFT_WITHOUT_BEING_SEEN')
        RETURNING e.id, e.hospital_id
    ), audited AS (
        INSERT INTO support.audit_event_logs (
            id, created_at, updated_at, event_timestamp,
            user_id, actor_type, actor_label,
            event_type, status, event_description, details,
            target_entity_type, target_resource_id, hospital_name)
        SELECT gen_random_uuid(), now(), now(), now(),
               NULL, 'SYSTEM', 'Migration V263',
               'ENCOUNTER_UPDATE', 'SUCCESS',
               'Encounter of a cancelled admission closed by migration V263 (QA D1393)',
               'status -> CANCELLED',
               'ENCOUNTER', x.id::text, h.name
          FROM closed x
          LEFT JOIN hospital.hospitals h ON h.id = x.hospital_id
        RETURNING 1
    )
    SELECT count(*) INTO closed_count FROM audited;

    RAISE NOTICE 'V263: % order(s) written after the cancel moved to an outpatient anchor; % order(s) of cancelled stays discontinued (% open back order(s) cancelled), % left for a prepared fill or an open partner offer; % encounter(s) of cancelled stays closed',
        moved_count, ended_count, back_order_count, held_count, closed_count;
END
$$;
