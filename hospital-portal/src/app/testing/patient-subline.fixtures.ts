import { WritableSignal } from '@angular/core';
import { ComponentFixture } from '@angular/core/testing';
import { PatientResponse } from '../services/patient.service';

/**
 * QA D903/D912 re-test patients. Awa has an email AND this hospital's MRN;
 * Fatimata has a phone and no email; the third carries no identifier at all.
 */
export const SUBLINE_AWA = {
  id: 'p-awa',
  firstName: 'Awa',
  lastName: 'Kaboré',
  email: 'awa.kabore@example.test',
  phoneNumberPrimary: '70001001',
  mrn: 'CHW2572',
} as PatientResponse;

export const SUBLINE_FATIMATA = {
  id: 'p-fatimata',
  firstName: 'Fatimata',
  lastName: 'Zongo',
  email: '',
  phoneNumberPrimary: '70001003',
  mrn: 'CHW1444',
} as PatientResponse;

export const SUBLINE_NO_IDENTIFIER = {
  id: 'p-none',
  firstName: 'Sans',
  lastName: 'Identifiant',
  email: '',
} as PatientResponse;

/** The signals every hand-written patient typeahead in the portal exposes. */
export interface HandWrittenPatientPicker {
  selectedPatient: WritableSignal<PatientResponse | null>;
  patientSuggestions: WritableSignal<PatientResponse[]>;
  patientDropdownOpen: WritableSignal<boolean>;
}

/** What a hand-written picker rendered for the three patients above. */
export interface RenderedPickerSublines {
  chipWithEmail: string | null;
  chipPhoneOnly: string | null;
  /** The chip itself still renders for a patient with no identifier... */
  chipRenderedWithoutIdentifier: boolean;
  /** ...but with no line under the name (null), never an empty one. */
  chipWithoutIdentifier: string | null;
  options: (string | null)[];
}

/** The MRN under the name everywhere; no email, phone, or dangling ' ·'. */
export const EXPECTED_PICKER_SUBLINES: RenderedPickerSublines = {
  chipWithEmail: 'CHW2572',
  chipPhoneOnly: 'CHW1444',
  chipRenderedWithoutIdentifier: true,
  chipWithoutIdentifier: null,
  options: ['CHW2572', 'CHW1444'],
};

/**
 * Drives a hand-written picker whose form is already open in `fixture` through
 * the re-test: picks each patient in turn and reads the chip's line under the
 * name, then opens the dropdown on Awa and Fatimata and reads each option's.
 * Leaves the picker empty.
 */
export function renderPickerSublines(
  fixture: ComponentFixture<unknown>,
  picker: HandWrittenPatientPicker,
): RenderedPickerSublines {
  const el = fixture.nativeElement as HTMLElement;
  const chipSub = (): string | null =>
    el.querySelector('.picker-chip .chip-sub')?.textContent?.trim() ?? null;
  const chipFor = (p: PatientResponse): string | null => {
    picker.selectedPatient.set(p);
    fixture.detectChanges();
    return chipSub();
  };

  const chipWithEmail = chipFor(SUBLINE_AWA);
  const chipPhoneOnly = chipFor(SUBLINE_FATIMATA);
  const chipWithoutIdentifier = chipFor(SUBLINE_NO_IDENTIFIER);
  const chipRenderedWithoutIdentifier = el.querySelector('.picker-chip') !== null;

  picker.selectedPatient.set(null);
  picker.patientSuggestions.set([SUBLINE_AWA, SUBLINE_FATIMATA]);
  picker.patientDropdownOpen.set(true);
  fixture.detectChanges();
  const options = Array.from(el.querySelectorAll('.picker-option')).map(
    (o) => o.querySelector('.opt-meta')?.textContent?.trim() ?? null,
  );
  picker.patientDropdownOpen.set(false);
  picker.patientSuggestions.set([]);
  fixture.detectChanges();

  return {
    chipWithEmail,
    chipPhoneOnly,
    chipRenderedWithoutIdentifier,
    chipWithoutIdentifier,
    options,
  };
}
