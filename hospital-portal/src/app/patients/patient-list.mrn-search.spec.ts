import { signal } from '@angular/core';

import { PatientResponse } from '../services/patient.service';
import { PatientListComponent } from './patient-list';

/**
 * QA D1218 (round 23): on /patients, "CHW0410" listed 4 rows, Aïcha Kinda
 * first (MRN CHW8661, phone +22670004101): the phone match stripped the
 * letters of the MRN and compared "0410". Drives the page's own applyFilter.
 */
describe('PatientListComponent: an MRN search (QA D1218)', () => {
  const row = (mrn: string, phone: string, last: string) =>
    ({ id: mrn, mrn, phoneNumberPrimary: phone, firstName: 'X', lastName: last }) as PatientResponse;

  function filter(term: string, rows: PatientResponse[]): string[] {
    const page = Object.assign(Object.create(PatientListComponent.prototype) as PatientListComponent, {
      patients: signal(rows),
      filtered: signal<PatientResponse[]>([]),
      searchTerm: term,
      genderFilter: '',
      statusFilter: '',
      bloodTypeFilter: '',
      hospitalFilter: '',
      sortField: signal('name'),
      sortDir: signal('asc'),
    });
    page.applyFilter();
    return page.filtered().map((p) => p.mrn ?? '');
  }

  const rows = [
    row('CHW8661', '+22670004101', 'Kinda'),
    row('CHW0410', '+22665112233', 'Zoungrana'),
    row('CHW0411', '+22676000000', 'Ouedraogo'),
  ];

  it('never matches the digits of a phone when the term has letters', () => {
    expect(filter('CHW0410', rows)).toEqual(['CHW0410']);
  });

  it('lists the exact MRN first among partial matches', () => {
    expect(filter('chw041', [...rows, row('CHW041', '', 'Aaa')])[0]).toBe('CHW041');
    expect(filter('CHW0410', [row('CHW04100', '', 'Aaa'), ...rows])).toEqual(['CHW0410', 'CHW04100']);
  });

  it('a phone typed with digits still finds the patient', () => {
    expect(filter('70 00 41 01', rows)).toEqual(['CHW8661']);
  });
});
