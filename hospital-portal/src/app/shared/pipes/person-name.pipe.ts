import { Pipe, PipeTransform } from '@angular/core';

/** The parts of a person's name a record carries. */
export interface PersonNameParts {
  firstName?: string | null;
  middleName?: string | null;
  lastName?: string | null;
}

/**
 * QA D1036 / D926: a patient's full name, middle name included, each part
 * once and single-spaced ("Pélagie Wendkouni Kiemdé"). Templates wrote
 * `{{ p.firstName }} {{ p.lastName }}`, so a middle name captured at
 * registration was never shown, and two patients who share a first and a
 * last name looked identical in every picker.
 */
export function personName(p: PersonNameParts | null | undefined): string {
  if (!p) return '';
  return [p.firstName, p.middleName, p.lastName]
    .map((part) => (part ?? '').trim())
    .filter((part) => part !== '')
    .join(' ');
}

@Pipe({ name: 'personName', standalone: true })
export class PersonNamePipe implements PipeTransform {
  transform(p: PersonNameParts | null | undefined): string {
    return personName(p);
  }
}
