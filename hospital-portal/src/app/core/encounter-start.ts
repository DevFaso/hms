/**
 * QA D1085: who may start (pick up) a visit waiting for a clinician. One rule
 * for every surface that offers "Démarrer", mirroring the server:
 * EncounterController#startEncounter and EncounterServiceImpl.startEncounter.
 *
 * - a super-admin always;
 * - a visit assigned to a clinician: only that clinician (the server refuses
 *   everyone else, doctor or midwife, "not the assigned clinician");
 * - an unassigned visit: a doctor (a midwife who is not also a doctor leads
 *   only the visits booked with her, QA D1015).
 */
export interface StartActor {
  superAdmin: boolean;
  /** Holds (in the acting role) ROLE_DOCTOR or an equivalent physician role. */
  doctor: boolean;
  /** Holds (in the acting role) ROLE_MIDWIFE. */
  midwife: boolean;
  /** The signed-in user's id. */
  userId: string | null;
}

export function mayStartVisit(
  actor: StartActor,
  assignedUserId: string | null | undefined,
): boolean {
  if (actor.superAdmin) return true;
  if (!actor.doctor && !actor.midwife) return false;
  if (assignedUserId) return !!actor.userId && assignedUserId === actor.userId;
  return actor.doctor;
}

/**
 * QA D1099 (round 19): who may end an examination in progress ("Terminer
 * l'examen", "Prêt pour la sortie" and "Sortie" from IN_PROGRESS), the mirror
 * of who starts it, as the server rules (EncounterClosureRules.requireExaminingClinician):
 *
 * - a super-admin always;
 * - a visit assigned to a clinician: only that clinician (a midwife ended a
 *   doctor's examination from the tracker);
 * - an unassigned visit: a doctor.
 */
export function mayEndExamination(
  actor: StartActor,
  assignedUserId: string | null | undefined,
): boolean {
  if (actor.superAdmin) return true;
  if (assignedUserId) return !!actor.userId && assignedUserId === actor.userId;
  return actor.doctor;
}

/**
 * QA D1108 (round 19): a visit no clinician has seen yet, which may be closed
 * as "left without being seen" (EncounterClosureRules.NEVER_SEEN, the
 * close-unseen endpoint's acceptance rule). The tracker offered it only
 * before triage, while the server accepts it until the patient is seen.
 * Every LWBS surface asks this one rule (scripts/lib/author-only-actions.test.mjs).
 */
const NEVER_SEEN_STATUSES: ReadonlySet<string> = new Set([
  'SCHEDULED',
  'ARRIVED',
  'TRIAGE',
  'WAITING_FOR_PHYSICIAN',
]);

export function isNeverSeenVisit(status: string | null | undefined): boolean {
  return !!status && NEVER_SEEN_STATUSES.has(status);
}
