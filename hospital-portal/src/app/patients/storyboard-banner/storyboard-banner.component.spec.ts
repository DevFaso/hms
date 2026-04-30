import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, Subject, of, throwError } from 'rxjs';

import { StoryboardBannerComponent } from './storyboard-banner.component';
import { PatientStoryboard, StoryboardService } from '../../services/storyboard.service';

describe('StoryboardBannerComponent', () => {
  let fixture: ComponentFixture<StoryboardBannerComponent>;
  let storyboardSpy: jasmine.SpyObj<StoryboardService>;

  beforeEach(async () => {
    storyboardSpy = jasmine.createSpyObj<StoryboardService>('StoryboardService', ['getStoryboard']);

    await TestBed.configureTestingModule({
      imports: [StoryboardBannerComponent, TranslateModule.forRoot()],
      providers: [{ provide: StoryboardService, useValue: storyboardSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(StoryboardBannerComponent);
  });

  function setPatient(id: string | null | undefined, hospitalId?: string | null): void {
    fixture.componentRef.setInput('patientId', id);
    if (hospitalId !== undefined) {
      fixture.componentRef.setInput('hospitalId', hospitalId);
    }
    fixture.detectChanges();
  }

  function bannerEl(): HTMLElement | null {
    return fixture.nativeElement.querySelector('[data-testid="storyboard-banner"]');
  }

  it('hides the banner entirely when patientId is missing', () => {
    setPatient(null);
    expect(bannerEl()).toBeNull();
    expect(storyboardSpy.getStoryboard).not.toHaveBeenCalled();
  });

  it('renders the four sections when the service returns data', () => {
    storyboardSpy.getStoryboard.and.returnValue(of(populatedSummary()));

    setPatient('p-123');

    expect(storyboardSpy.getStoryboard).toHaveBeenCalledOnceWith('p-123', undefined);
    expect(bannerEl()?.dataset['state']).toBe('ready');
    expect(
      fixture.nativeElement.querySelector('[data-testid="storyboard-allergies"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="storyboard-problems"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="storyboard-encounter"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="storyboard-code-status"]'),
    ).not.toBeNull();
  });

  it('forwards the hospital scope when supplied', () => {
    storyboardSpy.getStoryboard.and.returnValue(of(emptySummary()));
    setPatient('p-1', 'h-99');
    expect(storyboardSpy.getStoryboard).toHaveBeenCalledOnceWith('p-1', 'h-99');
  });

  it('shows the empty state when no clinical data and no code status are present', () => {
    storyboardSpy.getStoryboard.and.returnValue(of(emptySummary()));
    setPatient('p-empty');
    expect(bannerEl()?.dataset['state']).toBe('empty');
    expect(fixture.nativeElement.querySelector('[data-testid="storyboard-empty"]')).not.toBeNull();
  });

  it('shows the error state without throwing when the service fails', () => {
    storyboardSpy.getStoryboard.and.returnValue(
      throwError(() => new Error('network')) as Observable<PatientStoryboard>,
    );
    setPatient('p-err');
    expect(bannerEl()?.dataset['state']).toBe('error');
    expect(fixture.nativeElement.querySelector('[data-testid="storyboard-error"]')).not.toBeNull();
  });

  it('cancels the previous request when patientId changes mid-flight (clinical-safety)', () => {
    const firstStream = new Subject<PatientStoryboard>();
    storyboardSpy.getStoryboard.and.returnValue(firstStream.asObservable());
    setPatient('p-slow');
    expect(firstStream.observed).toBeTrue();

    const secondSummary = populatedSummary('p-fast');
    storyboardSpy.getStoryboard.and.returnValue(of(secondSummary));
    setPatient('p-fast');

    firstStream.next(populatedSummary('p-slow'));
    firstStream.complete();
    fixture.detectChanges();

    expect(firstStream.observed).toBeFalse();
    expect(bannerEl()?.dataset['state']).toBe('ready');
    expect(
      fixture.nativeElement
        .querySelector('[data-testid="storyboard-banner"] .storyboard__name')
        ?.textContent?.trim(),
    ).toContain('p-fast');
  });
});

function populatedSummary(name = 'Aïssata Diallo'): PatientStoryboard {
  return {
    patient: {
      id: 'p-1',
      mrn: 'MRN-1001',
      firstName: 'Aïssata',
      lastName: 'Diallo',
      fullName: name,
      ageYears: 28,
      gender: 'F',
      bloodType: 'O+',
      dateOfBirth: '1997-04-30',
    },
    allergies: [
      {
        id: 'a-1',
        allergenDisplay: 'Penicillin',
        severity: 'LIFE_THREATENING',
        reaction: 'anaphylaxis',
      },
    ],
    problems: [
      {
        id: 'pr-1',
        problemDisplay: 'Sickle cell disease',
        chronic: true,
      },
    ],
    activeEncounter: {
      id: 'e-1',
      encounterType: 'INPATIENT',
      status: 'IN_PROGRESS',
      staffFullName: 'Dr Compaoré',
      departmentName: 'Internal Medicine',
    },
    codeStatus: {
      status: 'FULL_CODE',
      directives: [],
    },
    hasHighSeverityAllergy: true,
    hasChronicProblem: true,
  };
}

function emptySummary(): PatientStoryboard {
  return {
    patient: {
      id: 'p-blank',
      firstName: 'Empty',
      lastName: 'Patient',
      fullName: 'Empty Patient',
    },
    allergies: [],
    problems: [],
    activeEncounter: null,
    codeStatus: null,
    hasHighSeverityAllergy: false,
    hasChronicProblem: false,
  };
}
