import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
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
    // QA D3901: a group only typed on the chart is shown as declared, never as the laboratory's.
    expect(text(el, 'blood-group-declared')).toBe('O+');
    expect(text(el, 'patient-safety-blood-group')).toContain('BLOOD_GROUP.DECLARED_UNVERIFIED');
    expect(el.querySelector('[data-testid="blood-group-confirmed"]')).toBeNull();
    expect(el.textContent).toContain('PATIENT_SAFETY.BLOOD_GROUP');
    expect(text(el, 'patient-safety-weight')).toContain('PATIENT_SAFETY.WEIGHT_KG');
    expect(text(el, 'patient-safety-allergies')).toContain('Pénicilline');
  });

  it('QA D3901: shows the laboratory group, the typed one that differs, and the antibody alert', () => {
    storyboard.getStoryboard.and.returnValue(
      of(
        summary({
          bloodType: 'A+',
          bloodGroup: {
            declared: 'A+',
            confirmed: 'B+',
            confirmedAt: '2026-11-25T09:30:00',
            confirmedHospitalName: 'Centre Médical Test',
            discrepancy: true,
            alloImmunised: true,
            antibodySpecificities: 'anti-E',
          },
        }),
      ),
    );
    const el = show('p-1');
    expect(text(el, 'blood-group-confirmed')).toBe('B+');
    expect(el.querySelector('[data-testid="blood-group-declared"]')).toBeNull();
    expect(text(el, 'blood-group-discrepancy')).toContain('BLOOD_GROUP.DIFFERS_FROM_DECLARED');
    expect(text(el, 'blood-group-antibody')).toContain('BLOOD_GROUP.ALLO_IMMUNISED');
  });

  it('says when no blood group is recorded, never leaves the slot empty', () => {
    storyboard.getStoryboard.and.returnValue(of(summary({ bloodType: null })));
    const el = show('p-1');
    expect(text(el, 'patient-safety-blood-group')).toBe('PATIENT_SAFETY.NOT_DOCUMENTED');
  });

  it('keeps the blood group readable when the request action shares a narrow header', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation(
      'fr',
      {
        PATIENT_SAFETY: { BLOOD_GROUP: 'Groupe sanguin', NOT_DOCUMENTED: 'Non renseigné' },
      },
      true,
    );
    translate.use('fr');
    storyboard.getStoryboard.and.returnValue(
      of(summary({ bloodType: null, fullName: 'Pengdwendé Yaméogo' })),
    );
    const el = show('p-1');
    el.style.width = '260px';
    const fact = el.querySelector('.psh-fact-blood') as HTMLElement;
    const action = document.createElement('span');
    action.textContent = 'Demander du sang';
    action.style.cssText = 'display:inline-block;flex:none;width:130px;white-space:nowrap';
    fact.appendChild(action);
    fixture.detectChanges();

    const value = el.querySelector('[data-testid="patient-safety-blood-group"]') as HTMLElement;
    expect(value.getBoundingClientRect().width).toBeGreaterThan(60);
    expect(action.getBoundingClientRect().right).toBeLessThanOrEqual(
      fact.getBoundingClientRect().right + 1,
    );
    expect(el.querySelector('[data-testid="patient-safety-name"]')?.textContent).toContain(
      'Pengdwendé Yaméogo',
    );
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
