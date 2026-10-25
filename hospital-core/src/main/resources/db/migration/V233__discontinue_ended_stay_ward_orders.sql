-- V233 (QA D792, residual of D757): ward orders of stays discharged before
-- the D757 fix (a discharge now discontinues its stay's ward orders) are
-- still live: SIGNED, PARTIALLY_FILLED or DRAFT on a stay that has ended,
-- among them morphine. The pharmacy refuses them on read and on every action;
-- this ends them in the data too, as the discharge would have.
--
-- An order is ended when ALL hold:
--   * it is live: DRAFT, PENDING_SIGNATURE, SIGNED, TRANSMITTED,
--     TRANSMISSION_FAILED, PENDING_CLARIFICATION, PENDING_STOCK or
--     PARTIALLY_FILLED (StayOrderDiscontinuation.LIVE_STATUSES), or
--     PARTNER_REJECTED: a partner refused it (by SMS, recorded by a
--     pharmacist, or the timeout sweep), so it is back in the in-house work
--     queue with no offer left that could ever end it. SENT_TO_PARTNER and
--     PARTNER_ACCEPTED are left to the application, which can tell the
--     partner (an order a partner still holds ends when its offer closes);
--   * it is NOT marked take_home (V231): a take-home order outlives its stay;
--   * it belongs to a stay that has ended, by the rule the discharge applies
--     (InpatientOrderEnd.orderOfStay; InpatientOrderEnd.endedWithAnEndedStay
--     is this same rule in Java):
--       1. filed on a stay's own encounter (V193 admission_id) and that
--          encounter is closed (COMPLETED / CANCELLED) or its admission is
--          DISCHARGED / CANCELLED; or
--       2. filed on an INPATIENT encounter with no admission link (V193 did
--          not backfill admission_id), written at the same hospital between
--          the admission and the discharge of an ended stay of the patient; or
--       3. filed on an EMERGENCY encounter still open at that admission,
--          written at most 24 hours before it and before the discharge (the
--          department's orders carry on to the ward, QA D702/D712).
--   Every order names an encounter: clinical.prescriptions.encounter_id is
--   NOT NULL since V1, and an order written with no encounter is filed on the
--   patient's open stay encounter, or an outpatient anchor, before it is saved
--   (PrescriptionServiceImpl.resolveEncounterContext). So the inner join
--   leaves no order out.
--
-- Left alone, and counted in the NOTICE by shape: an order with a prepared
-- fill waiting at the counter (a PENDING dispense holds stock and maybe a
-- payment) or an open partner offer (a PARTNER routing decision PENDING /
-- ACCEPTED). Voiding those has side effects (stock back on the shelf, payment
-- void, the partner told) that only the application performs. Until then
-- EndedStayOrderGuard, which applies the rule above to every shape, refuses
-- each of them at the hand-over, dispense, verification, queue claim, routing
-- and SMS dispatch. Each is ended by the application the moment its hold goes
-- (StayOrderDiscontinuation.discontinueIfStayEnded): the pharmacist cancels the
-- prepared fill at the counter (cancel-ready), or the partner offer is
-- accepted, refused, timed out or recorded as a no-show. It then leaves the
-- work queue DISCONTINUED, with the reason and audit row below.
-- An open BACK ORDER is no hold (nobody outside is told, and only a fill,
-- which the guard refuses, would close it): the order is ended and its open
-- back-order decision CANCELLED with it. The NOTICE shapes:
--   closed_stay_encounter  rule 1, encounter closed;
--   open_stay_encounter    rule 1, encounter open, admission ended;
--   unlinked_inpatient     rule 2, INPATIENT encounter;
--   emergency_carry_over   rule 3.
--
-- Each ended order: status DISCONTINUED, its open back order CANCELLED (the
-- decision's updated_at now), the reason the discharge writes
-- (withdrawal_reason, plaintext is read as is by EncryptedStringConverter;
-- a fixed sentence, no PHI), updated_at now. Audit trail: one
-- support.audit_event_logs row per order, SYSTEM actor (V9), event
-- PRESCRIPTION_DISCONTINUED, target Prescription + id, the previous status in
-- details. Ids and statuses only: no patient, no drug name.
--
-- Idempotent: a second run finds no live order of an ended stay and writes no
-- row. Counts only in the NOTICE. One DO block, splitStatements="false".
DO $$
DECLARE
    ended_count INTEGER;
    back_order_count INTEGER;
    held_count INTEGER;
    held_closed INTEGER;
    held_open INTEGER;
    held_inpatient INTEGER;
    held_emergency INTEGER;
BEGIN
    WITH candidates AS (
        SELECT p.id, p.status AS old_status,
               CASE
                   WHEN e.admission_id IS NOT NULL
                        AND e.status IN ('COMPLETED', 'CANCELLED') THEN 'closed_stay_encounter'
                   WHEN e.admission_id IS NOT NULL THEN 'open_stay_encounter'
                   WHEN e.encounter_type = 'INPATIENT' THEN 'unlinked_inpatient'
                   ELSE 'emergency_carry_over'
               END AS shape
          FROM clinical.prescriptions p
          JOIN clinical.encounters e ON e.id = p.encounter_id
         WHERE p.take_home = FALSE
           AND p.status IN ('DRAFT', 'PENDING_SIGNATURE', 'SIGNED', 'TRANSMITTED',
                            'TRANSMISSION_FAILED', 'PENDING_CLARIFICATION',
                            'PENDING_STOCK', 'PARTIALLY_FILLED', 'PARTNER_REJECTED')
           AND (
                (e.admission_id IS NOT NULL
                 AND (e.status IN ('COMPLETED', 'CANCELLED')
                      OR EXISTS (SELECT 1
                                   FROM public.admissions a
                                  WHERE a.id = e.admission_id
                                    AND a.status IN ('DISCHARGED', 'CANCELLED'))))
             OR (e.admission_id IS NULL
                 AND e.encounter_type = 'INPATIENT'
                 AND EXISTS (SELECT 1
                               FROM public.admissions a
                              WHERE a.patient_id = p.patient_id
                                AND a.hospital_id = p.hospital_id
                                AND a.actual_discharge_date_time IS NOT NULL
                                AND p.created_at >= a.admission_date_time
                                AND p.created_at <= a.actual_discharge_date_time))
             OR (e.admission_id IS NULL
                 AND e.encounter_type = 'EMERGENCY'
                 AND EXISTS (SELECT 1
                               FROM public.admissions a
                              WHERE a.patient_id = p.patient_id
                                AND a.hospital_id = p.hospital_id
                                AND a.actual_discharge_date_time IS NOT NULL
                                AND p.created_at >= a.admission_date_time - INTERVAL '24 hours'
                                AND p.created_at <= a.actual_discharge_date_time
                                AND (e.checkout_timestamp IS NULL
                                     OR e.checkout_timestamp >= a.admission_date_time)))
           )
    ), held AS (
        SELECT c.id, c.shape
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
               withdrawal_reason = 'Arrêtée à la sortie : prescription du séjour terminé',
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
               NULL, 'SYSTEM', 'Migration V233',
               'PRESCRIPTION_DISCONTINUED', 'SUCCESS',
               'Ward order of an ended stay discontinued by migration V233 (QA D792): discharged before ward orders ended with the stay',
               'status ' || x.old_status || ' -> DISCONTINUED; not take-home',
               'Prescription', x.id::text, h.name
          FROM ended x
          LEFT JOIN hospital.hospitals h ON h.id = x.hospital_id
        RETURNING 1
    )
    SELECT (SELECT count(*) FROM audited),
           (SELECT count(*) FROM back_orders_closed),
           (SELECT count(*) FROM held),
           (SELECT count(*) FROM held WHERE shape = 'closed_stay_encounter'),
           (SELECT count(*) FROM held WHERE shape = 'open_stay_encounter'),
           (SELECT count(*) FROM held WHERE shape = 'unlinked_inpatient'),
           (SELECT count(*) FROM held WHERE shape = 'emergency_carry_over')
      INTO ended_count, back_order_count, held_count, held_closed, held_open, held_inpatient, held_emergency;

    RAISE NOTICE 'V233: % ward order(s) of ended stays discontinued (% open back order(s) cancelled), % left for a prepared fill or an open partner offer (closed_stay_encounter %, open_stay_encounter %, unlinked_inpatient %, emergency_carry_over %)',
        ended_count, back_order_count, held_count, held_closed, held_open, held_inpatient, held_emergency;
END
$$;
