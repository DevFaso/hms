import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { PatientSafetyHeaderComponent } from './patient-safety-header.component';
import { PatientStoryboard, StoryboardService } from '../../services/storyboard.service';

/** QA r70 D3220: the patient-safety header of every clinical screen. */
describe('PatientSafetyHeaderComponent', () => {
  let fixture: ComponentFixture<PatientSafetyHeaderComponent>;
  let storyboard: jasmine.SpyObj<StoryboardService>;

  function summary(overrides: Partial<PatientStoryboard['patient']> = {}): PatientStoryboard {
    return {
      patient: {
        id: 'p-1',
        fullName: 'Awa Test',
        ageYears: 2,
        gender: 'FEMALE',
        bloodType: 'O+',
        latestWeightKg: 12,
        latestWeightRecordedAt: '2026-11-20T08:00:00',
        ...overrides,
      },
      allergies: [{ id: 'a-1', allergenDisplay: 'Pénicilline', severity: 'SEVERE' }],
      problems: [],
      hasHighSeverityAllergy: true,
      hasChronicProblem: false,
    };
  }

  beforeEach(async () => {
    storyboard = jasmine.createSpyObj<StoryboardService>('StoryboardService', ['getStoryboard']);
    await TestBed.configureTestingModule({
      imports: [PatientSafetyHeaderComponent, TranslateModule.forRoot()],
      providers: [{ provide: StoryboardService, useValue: storyboard }],
    }).compileComponents();
    fixture = TestBed.createComponent(PatientSafetyHeaderComponent);
  });

  function show(patientId: string | null): HTMLElement {
    fixture.componentRef.setInput('patientId', patientId);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (el: HTMLElement, id: string) =>
    el.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? null;

  it('shows the blood group, labelled, with the weight and the allergies', () => {
    storyboard.getStoryboard.and.returnValue(of(summary()));
    const el = show('p-1');
    expect(text(el, 'patient-safety-blood-group')).toBe('O+');
    expect(el.textContent).toContain('PATIENT_SAFETY.BLOOD_GROUP');
    expect(text(el, 'patient-safety-weight')).toContain('PATIENT_SAFETY.WEIGHT_KG');
    expect(text(el, 'patient-safety-allergies')).toContain('Pénicilline');
  });

  it('says when no blood group is recorded, never leaves the slot empty', () => {
    storyboard.getStoryboard.and.returnValue(of(summary({ bloodType: null })));
    const el = show('p-1');
    expect(text(el, 'patient-safety-blood-group')).toBe('PATIENT_SAFETY.NOT_DOCUMENTED');
  });

  it('renders nothing for a role the server refuses (the front desk)', () => {
    storyboard.getStoryboard.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 403 })),
    );
    const el = show('p-1');
    expect(el.querySelector('[data-testid="patient-safety-header"]')).toBeNull();
  });

  it('says the header is unavailable on any other failure', () => {
    storyboard.getStoryboard.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );
    const el = show('p-1');
    expect(el.querySelector('[data-testid="patient-safety-error"]')).not.toBeNull();
  });

  it('asks nothing without a patient', () => {
    const el = show(null);
    expect(storyboard.getStoryboard).not.toHaveBeenCalled();
    expect(el.querySelector('[data-testid="patient-safety-header"]')).toBeNull();
  });
});
