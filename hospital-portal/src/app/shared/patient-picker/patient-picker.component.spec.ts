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
      expect(patientSpy.search).toHaveBeenCalledWith({
        name: 'do',
        hospitalId: 'h1',
        size: 8,
        includeInactive: true,
      });
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

  it('looks a bare server-issued MRN up across hospitals (QA D129)', (done) => {
    patientSpy.lookup.and.returnValue(of([mockPatient('p9', 'Dana')]));
    component.ngOnInit();
    component.onQueryChange('CHU0421');
    setTimeout(() => {
      expect(patientSpy.lookup).toHaveBeenCalled();
      expect(component.suggestions().map((p) => p.id)).toContain('p9');
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

  it('says no patient matches when a finished search finds nothing (QA D68)', (done) => {
    const fixture = TestBed.createComponent(PatientPickerComponent);
    const picker = fixture.componentInstance;
    picker.ngOnInit();
    picker.onQueryChange('zz');
    setTimeout(() => {
      fixture.detectChanges();
      expect(picker.noMatch()).toBeTrue();
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.picker-no-match')).not.toBeNull();
      picker.onQueryChange('z');
      fixture.detectChanges();
      expect(picker.noMatch()).toBeFalse();
      expect(el.querySelector('.picker-no-match')).toBeNull();
      done();
    }, 400);
  });

  it('shows no "no match" message while a search has results', (done) => {
    patientSpy.search.and.returnValue(of([mockPatient('p1', 'Anna')]));
    component.ngOnInit();
    component.onQueryChange('an');
    setTimeout(() => {
      expect(component.noMatch()).toBeFalse();
      done();
    }, 400);
  });

  // QA D903: the re-test typed 'CHW5448' on /pharmacy/mtm and the option read
  // 'FZ Fatimata Zongo 70001003' — the phone, not the MRN the server returned.
  function zongo(extra: Partial<PatientResponse> = {}): PatientResponse {
    return {
      id: 'pz',
      firstName: 'Fatimata',
      lastName: 'Zongo',
      phoneNumberPrimary: '70001003',
      email: 'fz@example.test',
      active: true,
      ...extra,
    } as PatientResponse;
  }

  it('shows the hospital MRN, not the phone or email, under each option (QA D903)', (done) => {
    const fixture = TestBed.createComponent(PatientPickerComponent);
    const picker = fixture.componentInstance;
    picker.hospitalId = 'h1';
    patientSpy.search.and.returnValue(of([zongo({ mrn: 'CHW5448' })]));
    patientSpy.lookup.and.returnValue(of([zongo({ mrn: 'CHW5448' })]));
    picker.ngOnInit();
    picker.onQueryChange('CHW5448');
    setTimeout(() => {
      fixture.detectChanges();
      const el: HTMLElement = fixture.nativeElement;
      const option = el.querySelector('.picker-option') as HTMLElement;
      expect(option.querySelector('.opt-name')?.textContent?.trim()).toBe('Fatimata Zongo');
      expect(option.querySelector('.opt-meta')?.textContent?.trim()).toBe('CHW5448');
      expect(option.textContent).not.toContain('70001003');
      expect(option.textContent).not.toContain('fz@example.test');
      done();
    }, 400);
  });

  it('shows the MRN on the chip once a patient is picked (QA D903)', () => {
    const fixture = TestBed.createComponent(PatientPickerComponent);
    fixture.componentInstance.select(zongo({ mrn: 'CHW5448' }));
    fixture.detectChanges();
    const chip = (fixture.nativeElement as HTMLElement).querySelector(
      '.picker-chip',
    ) as HTMLElement;
    expect(chip.querySelector('.chip-sub')?.textContent?.trim()).toBe('CHW5448');
    expect(chip.textContent).not.toContain('70001003');
  });

  it('takes the scoped search MRN when the exact hit came back without one (QA D903)', (done) => {
    component.hospitalId = 'h1';
    patientSpy.lookup.and.returnValue(of([zongo()]));
    patientSpy.search.and.returnValue(of([zongo({ mrn: 'CHW5448' })]));
    component.ngOnInit();
    component.onQueryChange('CHW5448');
    setTimeout(() => {
      expect(component.suggestions().length).toBe(1);
      expect(component.subline(component.suggestions()[0])).toBe('CHW5448');
      done();
    }, 400);
  });

  it('falls back to the email, then the phone, only when no MRN is known (QA D903)', () => {
    expect(component.subline(zongo({ mrn: '  ' }))).toBe('fz@example.test');
    expect(component.subline(zongo({ email: '' }))).toBe('70001003');
    expect(component.subline(zongo({ email: undefined, phoneNumberPrimary: undefined }))).toBe('');
  });

  it('renders no empty second line for a patient with nothing to show (QA D903)', () => {
    const fixture = TestBed.createComponent(PatientPickerComponent);
    fixture.componentInstance.select({
      id: 'p0',
      firstName: 'Awa',
      lastName: '',
      active: true,
    } as PatientResponse);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.chip-sub')).toBeNull();
  });

  it('does not search for queries shorter than two characters', () => {
    component.ngOnInit();
    component.onQueryChange('a');
    expect(component.suggestions()).toEqual([]);
    expect(component.dropdownOpen()).toBeFalse();
  });
});
