import { PortalPrescription } from '../../services/patient-portal.service';

/** Prescriptions that were stopped before use are not handed to a pharmacy. */
const NOT_PRINTABLE = new Set(['CANCELLED', 'DISCONTINUED']);

/** QA D2353: whether the patient can open this prescription as a document. */
export function isPrintablePrescription(rx: PortalPrescription): boolean {
  return !NOT_PRINTABLE.has(rx.status);
}

/**
 * QA D2353: the medicines one prescription document lists. A visit's
 * prescriptions by the same prescriber are one "ordonnance" (as on paper);
 * a prescription with no visit stands alone. Stopped ones are left out.
 */
export function prescriptionDocumentItems(
  rx: PortalPrescription,
  all: readonly PortalPrescription[],
): PortalPrescription[] {
  if (!rx.encounterId) return [rx];
  const items = all.filter(
    (other) =>
      other.encounterId === rx.encounterId &&
      other.prescribedBy === rx.prescribedBy &&
      isPrintablePrescription(other),
  );
  return items.length ? items : [rx];
}
