import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import {
  PatientChartChanges,
  patientChartChangeInterceptor,
} from './patient-chart-change.interceptor';

/** QA D957: a successful write under /patients/{id}/… says that patient's chart changed. */
describe('patientChartChangeInterceptor (QA D957)', () => {
  const id = '3f1c2a9e-1d2b-4c5d-8e9f-0a1b2c3d4e5f';
  let http: HttpClient;
  let mock: HttpTestingController;
  let seen: string[];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([patientChartChangeInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    mock = TestBed.inject(HttpTestingController);
    seen = [];
    TestBed.inject(PatientChartChanges)
      .of(id)
      .subscribe((p) => seen.push(p));
  });

  afterEach(() => mock.verify());

  it('notifies after a saved allergy and a saved problem', () => {
    http.post(`/patients/${id}/allergies`, {}).subscribe();
    mock.expectOne(`/patients/${id}/allergies`).flush({});
    http.put(`/api/patients/${id}/diagnoses/x`, {}).subscribe();
    mock.expectOne(`/api/patients/${id}/diagnoses/x`).flush({});
    expect(seen).toEqual([id, id]);
  });

  it('stays quiet on a read, a refused write, and a write elsewhere', () => {
    http.get(`/patients/${id}/allergies`).subscribe();
    mock.expectOne(`/patients/${id}/allergies`).flush([]);
    http.post(`/patients/${id}/allergies`, {}).subscribe({ error: () => undefined });
    mock.expectOne(`/patients/${id}/allergies`).flush({}, { status: 400, statusText: 'Bad' });
    http.post('/encounters/e1/notes', {}).subscribe();
    mock.expectOne('/encounters/e1/notes').flush({});
    expect(seen).toEqual([]);
  });
});
