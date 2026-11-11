import { Pipe, PipeTransform } from '@angular/core';

/** The fields the line under a patient's name is built from. */
export interface PatientSublineSource {
  mrn?: string | null;
  email?: string | null;
  phoneNumberPrimary?: string | null;
}

/**
 * QA D903/D912: the line under a patient's name, in every picker option and
 * selected-patient chip, identifies the patient AT THIS HOSPITAL — the MRN the
 * server resolved for the request's hospital scope. Contact details are only
 * the fallback for a record that carries no MRN, and a record with none of the
 * three gives an empty string, so the caller renders no line at all rather
 * than an empty one or a dangling separator.
 */
export function patientSubline(p: PatientSublineSource | null | undefined): string {
  if (!p) return '';
  return p.mrn?.trim() || p.email?.trim() || p.phoneNumberPrimary?.trim() || '';
}

/**
 * `@if (p | patientSubline; as sub) { … {{ sub }} … }` — {@link patientSubline}
 * for the hand-written pickers that do not use `app-patient-picker`.
 */
@Pipe({ name: 'patientSubline', standalone: true })
export class PatientSublinePipe implements PipeTransform {
  transform(p: PatientSublineSource | null | undefined): string {
    return patientSubline(p);
  }
}
