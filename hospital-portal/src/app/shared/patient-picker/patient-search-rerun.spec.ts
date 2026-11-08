import { signal } from '@angular/core';
import { fakeAsync, tick } from '@angular/core/testing';
import { Subject, of } from 'rxjs';

import { AdmissionsComponent } from '../../admissions/admissions';
import { ConsultationsComponent } from '../../consultations/consultations';
import { EncountersComponent } from '../../encounters/encounters';
import { LabComponent } from '../../lab/lab';
import { PrescriptionsComponent } from '../../prescriptions/prescriptions';
import { ReferralsComponent } from '../../referrals/referrals';
import { TreatmentPlansComponent } from '../../treatment-plans/treatment-plans';

/**
 * QA D1216 (round 23): reopen a create dialog, type the same patient name
 * ("Fatimata") as last time: no request, no suggestion — on lab,
 * prescriptions, referrals, admissions, treatment plans and consultations.
 * Each screen's typed-query stream lives as long as the component and
 * dropped a query equal to the last one (`distinctUntilChanged()`), while the
 * dialog had cleared the list it showed.
 *
 * This drives each screen's OWN search code (initPatientSearch and
 * onPatientQueryChange, called on the component's prototype with only the
 * fields they use) through two dialog openings with the same name.
 * scripts/lib/search-rerun.test.mjs keeps every debounced search of the
 * portal free of the dedupe.
 */
interface SearchScreen {
  initPatientSearch(): void;
  onPatientQueryChange(q: string): void;
}

const SCREENS: [string, { prototype: object }][] = [
  ['lab', LabComponent],
  ['prescriptions', PrescriptionsComponent],
  ['referrals', ReferralsComponent],
  ['admissions', AdmissionsComponent],
  ['treatment plans', TreatmentPlansComponent],
  ['consultations', ConsultationsComponent],
  ['encounters (sibling)', EncountersComponent],
];

describe('patient search is re-run when a dialog reopens (QA D1216)', () => {
  for (const [name, type] of SCREENS) {
    it(`${name}: the same name typed after the dialog reopened is searched again`, fakeAsync(() => {
      const list = jasmine.createSpy('list').and.returnValue(of([{ id: 'p1' }]));
      const screen = Object.assign(Object.create(type.prototype) as SearchScreen, {
        patientSearch$: new Subject<string>(),
        patientService: { list },
        patientSearchHospitalId: () => 'h1',
        patientSearchLoading: signal(false),
        patientSuggestions: signal<unknown[]>([]),
        patientDropdownOpen: signal(false),
        patientQuery: signal(''),
      });
      screen.initPatientSearch();

      screen.onPatientQueryChange('Fatimata');
      tick(300);
      expect(list).toHaveBeenCalledTimes(1);

      // The dialog closes and reopens: the screen clears what it showed.
      const s = screen as unknown as {
        patientSuggestions: ReturnType<typeof signal<unknown[]>>;
        patientDropdownOpen: ReturnType<typeof signal<boolean>>;
        patientQuery: ReturnType<typeof signal<string>>;
      };
      s.patientSuggestions.set([]);
      s.patientDropdownOpen.set(false);
      s.patientQuery.set('');

      screen.onPatientQueryChange('Fatimata');
      tick(300);
      expect(list).withContext('the second "Fatimata" reached the server').toHaveBeenCalledTimes(2);
      expect(s.patientSuggestions().length).withContext('and shows the patient').toBe(1);
    }));
  }
});
