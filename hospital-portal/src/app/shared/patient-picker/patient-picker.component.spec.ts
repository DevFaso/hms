import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { PatientPickerComponent } from './patient-picker.component';
import { PatientService, PatientResponse } from '../../services/patient.service';

function mockPatient(id: string, firstName: string): PatientResponse {
  return { id, firstName, lastName: 'Doe', active: true } as PatientResponse;
}

describe('PatientPickerComponent', () => {
  let component: PatientPickerComponent;
  let patientSpy: jasmine.SpyObj<PatientService>;

  beforeEach(async () => {
    patientSpy = jasmine.createSpyObj('PatientService', ['search', 'lookup']);
    patientSpy.search.and.returnValue(of([]));
    patientSpy.lookup.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [PatientPickerComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientService, useValue: patientSpy }],
    }).compileComponents();

    component = TestBed.createComponent(PatientPickerComponent).componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('uses only the hospital-scoped search for free-text name queries', (done) => {
    component.hospitalId = 'h1';
    patientSpy.search.and.returnValue(of([mockPatient('p1', 'Anna'), mockPatient('p2', 'Ben')]));
    component.ngOnInit();
    component.onQueryChange('do');
    setTimeout(() => {
      expect(patientSpy.search).toHaveBeenCalledWith({ name: 'do', hospitalId: 'h1', size: 8 });
      expect(patientSpy.lookup).not.toHaveBeenCalled();
      expect(component.suggestions().map((p) => p.id)).toEqual(['p1', 'p2']);
      expect(component.dropdownOpen()).toBeTrue();
      done();
    }, 400);
  });

  it('adds the exact cross-hospital lookup for identifier queries, exact hits first', (done) => {
    component.hospitalId = 'h1';
    patientSpy.search.and.returnValue(of([mockPatient('p1', 'Anna'), mockPatient('p2', 'Ben')]));
    patientSpy.lookup.and.returnValue(of([mockPatient('p2', 'Ben'), mockPatient('p3', 'Cara')]));
    component.ngOnInit();
    component.onQueryChange('jane@example.com');
    setTimeout(() => {
      expect(patientSpy.lookup).toHaveBeenCalledWith({
        identifier: 'jane@example.com',
        hospitalId: 'h1',
      });
      expect(component.suggestions().map((p) => p.id)).toEqual(['p2', 'p3', 'p1']);
      done();
    }, 400);
  });

  it('treats phone-like input as an identifier', (done) => {
    patientSpy.lookup.and.returnValue(of([mockPatient('p3', 'Cara')]));
    component.ngOnInit();
    component.onQueryChange('+22670707070');
    setTimeout(() => {
      expect(patientSpy.lookup).toHaveBeenCalled();
      expect(component.suggestions().map((p) => p.id)).toEqual(['p3']);
      done();
    }, 400);
  });

  it('keeps the stream alive when one source errors', (done) => {
    patientSpy.search.and.returnValue(throwError(() => new Error('boom')));
    patientSpy.lookup.and.returnValue(of([mockPatient('p3', 'Cara')]));
    component.ngOnInit();
    component.onQueryChange('mrn-X4K9Q2A');
    setTimeout(() => {
      expect(component.suggestions().map((p) => p.id)).toEqual(['p3']);
      done();
    }, 400);
  });

  it('does not search for queries shorter than two characters', () => {
    component.ngOnInit();
    component.onQueryChange('a');
    expect(component.suggestions()).toEqual([]);
    expect(component.dropdownOpen()).toBeFalse();
  });
});
