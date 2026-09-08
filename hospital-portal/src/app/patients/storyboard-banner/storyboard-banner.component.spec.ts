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

  describe('collapse when pinned', () => {
    // The banner is sticky by design (persistent patient identity), but the
    // full card block is ~230px tall — pinned at that height it takes a
    // quarter of the chart and everything below reads as sliding under a
    // bridge. condensed() is what the sentinel's IntersectionObserver drives;
    // these tests drive it directly, because jsdom has no IntersectionObserver
    // and the component deliberately degrades to "never condenses" there.
    const condensedFlags = (): HTMLElement | null =>
      fixture.nativeElement.querySelector('[data-testid="storyboard-condensed-flags"]');

    const sections = (): HTMLElement | null =>
      fixture.nativeElement.querySelector('.storyboard__sections');

    function setCondensed(value: boolean): void {
      (
        fixture.componentInstance as unknown as { condensed: { set(v: boolean): void } }
      ).condensed.set(value);
      fixture.detectChanges();
    }

    beforeEach(() => {
      storyboardSpy.getStoryboard.and.returnValue(of(populatedSummary()));
      setPatient('p-1');
    });

    it('shows the full card block and no condensed strip while unpinned', () => {
      expect(sections()?.hidden).toBeFalse();
      expect(condensedFlags()).toBeNull();
      expect(bannerEl()?.classList).not.toContain('storyboard--condensed');
    });

    it('hides the cards and marks the banner condensed once pinned', () => {
      setCondensed(true);

      expect(sections()?.hidden).toBeTrue();
      expect(bannerEl()?.classList).toContain('storyboard--condensed');
    });

    it('keeps the allergy flag visible when condensed — the point of the banner', () => {
      setCondensed(true);

      // Losing this on scroll would defeat the reason the banner is sticky at
      // all: a clinician must not scroll back up to find out about an allergy.
      expect(condensedFlags()).not.toBeNull();
      expect(condensedFlags()?.textContent).toContain('1');
    });

    it('keeps the code status visible when condensed', () => {
      setCondensed(true);

      expect(condensedFlags()?.textContent?.toUpperCase()).toContain('FULL');
    });

    it('returns to the full card block when unpinned again', () => {
      setCondensed(true);
      setCondensed(false);

      expect(sections()?.hidden).toBeFalse();
      expect(condensedFlags()).toBeNull();
    });

    it('renders without an IntersectionObserver (jsdom, SSR) instead of throwing', () => {
      // ngAfterViewInit already ran in beforeEach under a jsdom with no
      // IntersectionObserver. Reaching here at all is the assertion.
      expect(bannerEl()).not.toBeNull();
      expect(sections()?.hidden).toBeFalse();
    });
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
