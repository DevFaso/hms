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
