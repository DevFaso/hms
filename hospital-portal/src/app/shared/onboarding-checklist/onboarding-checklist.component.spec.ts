import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Route } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import fr from '../../../assets/i18n/fr.json';
import { Subject, of, throwError } from 'rxjs';

import {
  ONBOARDING_STEP_ROUTES,
  OnboardingChecklistComponent,
} from './onboarding-checklist.component';
import { routes as appRoutes } from '../../app.routes';
import { roleSatisfies } from '../../core/role-equivalence';
import {
  HospitalOnboardingReadiness,
  HospitalService,
  OnboardingStep,
} from '../../services/hospital.service';

/** QA r65 D2731: the onboarding checklist of a hospital. */
describe('OnboardingChecklistComponent (QA r65 D2731)', () => {
  let hospitals: jasmine.SpyObj<HospitalService>;

  const STEPS: OnboardingStep[] = [
    'DEPARTMENTS',
    'STAFF',
    'PROVIDERS_IN_DEPARTMENT',
    'SESSION_TEMPLATES',
    'OPEN_SLOTS',
    'BEDS',
    'PRICE_LIST',
    'PAYERS',
  ];
  const BOOKING: OnboardingStep[] = STEPS.slice(0, 5);

  /** The r65 set-up: departments and staff exist, nobody is attached, no rota. */
  const fresh = (): HospitalOnboardingReadiness => ({
    hospitalId: 'h-1',
    patientBookable: false,
    items: STEPS.map((step) => ({
      step,
      done: step === 'DEPARTMENTS' || step === 'STAFF',
      count: step === 'DEPARTMENTS' ? 9 : step === 'STAFF' ? 46 : 0,
      requiredForBooking: BOOKING.includes(step),
    })),
  });
  const complete = (): HospitalOnboardingReadiness => ({
    hospitalId: 'h-1',
    patientBookable: true,
    items: STEPS.map((step) => ({
      step,
      done: true,
      count: 3,
      requiredForBooking: BOOKING.includes(step),
    })),
  });

  function render(inputs: {
    hospitalId: string | null;
    scopedLinks?: boolean;
    hideWhenComplete?: boolean;
  }): ComponentFixture<OnboardingChecklistComponent> {
    TestBed.configureTestingModule({
      imports: [OnboardingChecklistComponent, TranslateModule.forRoot()],
      providers: [provideRouter(appRoutes), { provide: HospitalService, useValue: hospitals }],
    });
    const fixture = TestBed.createComponent(OnboardingChecklistComponent);
    fixture.componentRef.setInput('hospitalId', inputs.hospitalId);
    fixture.componentRef.setInput('scopedLinks', inputs.scopedLinks ?? false);
    fixture.componentRef.setInput('hideWhenComplete', inputs.hideWhenComplete ?? false);
    fixture.detectChanges();
    return fixture;
  }

  const el = (fixture: ComponentFixture<unknown>, id: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const row = (fixture: ComponentFixture<unknown>, step: OnboardingStep) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(`[data-step="${step}"]`)!;

  beforeEach(() => {
    hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', ['onboardingReadiness']);
    hospitals.onboardingReadiness.and.returnValue(of(fresh()));
  });

  afterEach(() => TestBed.resetTestingModule());

  it('lists every step, marks what is done, and says patients cannot book yet', () => {
    const fixture = render({ hospitalId: 'h-1' });

    expect(hospitals.onboardingReadiness).toHaveBeenCalledOnceWith('h-1');
    for (const step of STEPS) expect(row(fixture, step)).withContext(step).not.toBeNull();
    expect(row(fixture, 'DEPARTMENTS').classList).toContain('oc-row-done');
    expect(row(fixture, 'PROVIDERS_IN_DEPARTMENT').classList).not.toContain('oc-row-done');
    expect(row(fixture, 'PROVIDERS_IN_DEPARTMENT').textContent).toContain(
      'PLATFORM_SETUP.CHECKLIST.STEP.PROVIDERS_IN_DEPARTMENT.TODO',
    );
    expect(row(fixture, 'OPEN_SLOTS').textContent).toContain(
      'PLATFORM_SETUP.CHECKLIST.NEEDED_FOR_BOOKING',
    );
    expect(row(fixture, 'PRICE_LIST').textContent).not.toContain(
      'PLATFORM_SETUP.CHECKLIST.NEEDED_FOR_BOOKING',
    );
    expect(el(fixture, 'onboarding-booking-state')!.textContent).toContain(
      'PLATFORM_SETUP.CHECKLIST.NOT_BOOKABLE',
    );
  });

  it('V356: « Règles cliniques validées : n / N », linked to the rules page', () => {
    hospitals.onboardingReadiness.and.returnValue(
      of({
        ...fresh(),
        items: [
          ...fresh().items,
          { step: 'CLINICAL_RULES', done: false, count: 3, requiredForBooking: false, total: 39 },
        ],
      }),
    );
    TestBed.configureTestingModule({
      imports: [OnboardingChecklistComponent, TranslateModule.forRoot()],
      providers: [provideRouter(appRoutes), { provide: HospitalService, useValue: hospitals }],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    const fixture = TestBed.createComponent(OnboardingChecklistComponent);
    fixture.componentRef.setInput('hospitalId', 'h-1');
    fixture.detectChanges();

    const rules = row(fixture, 'CLINICAL_RULES');
    expect(rules.textContent).toContain('Règles cliniques validées : 3 / 39');
    expect(rules.textContent).not.toContain('nécessaire aux rendez-vous');
    expect(rules.querySelector('a')!.getAttribute('href')).toBe('/clinical-rules');
  });

  it('says patients can book once the server says so', () => {
    hospitals.onboardingReadiness.and.returnValue(of(complete()));
    const fixture = render({ hospitalId: 'h-1' });
    expect(el(fixture, 'onboarding-booking-state')!.textContent).toContain(
      'PLATFORM_SETUP.CHECKLIST.BOOKABLE',
    );
  });

  it('on the dashboard, a hospital with every step done shows no checklist; an incomplete one does', () => {
    hospitals.onboardingReadiness.and.returnValue(of(complete()));
    expect(
      el(render({ hospitalId: 'h-1', hideWhenComplete: true }), 'onboarding-checklist'),
    ).toBeNull();
    TestBed.resetTestingModule();

    hospitals.onboardingReadiness.and.returnValue(of(fresh()));
    expect(
      el(render({ hospitalId: 'h-1', hideWhenComplete: true }), 'onboarding-checklist'),
    ).not.toBeNull();
  });

  it('asks nothing and shows nothing without a hospital (a role that has no checklist)', () => {
    const fixture = render({ hospitalId: null });
    expect(hospitals.onboardingReadiness).not.toHaveBeenCalled();
    expect(el(fixture, 'onboarding-checklist')).toBeNull();
    expect(el(fixture, 'onboarding-checklist-failed')).toBeNull();
  });

  it('a super-admin’s links carry the hospital, so each step opens on THIS hospital', () => {
    const fixture = render({ hospitalId: 'h-1', scopedLinks: true });
    const link = row(fixture, 'DEPARTMENTS').querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/departments?hospitalId=h-1');
  });

  it('a hospital admin’s links carry no hospital (theirs is the only one)', () => {
    const fixture = render({ hospitalId: 'h-1' });
    expect(row(fixture, 'DEPARTMENTS').querySelector('a')!.getAttribute('href')).toBe(
      '/departments',
    );
  });

  it('a failure is said on the hospital page and stays silent on the dashboard; a 404 is never a failure', () => {
    hospitals.onboardingReadiness.and.returnValue(throwError(() => ({ status: 500 })));
    expect(el(render({ hospitalId: 'h-1' }), 'onboarding-checklist-failed')).not.toBeNull();
    TestBed.resetTestingModule();

    expect(
      el(render({ hospitalId: 'h-1', hideWhenComplete: true }), 'onboarding-checklist-failed'),
    ).toBeNull();
    TestBed.resetTestingModule();

    // A pharmacy or a laboratory has no checklist.
    hospitals.onboardingReadiness.and.returnValue(throwError(() => ({ status: 404 })));
    expect(el(render({ hospitalId: 'h-1' }), 'onboarding-checklist-failed')).toBeNull();
  });

  it('an answer for a hospital the page has since left is dropped', () => {
    const first = new Subject<HospitalOnboardingReadiness>();
    hospitals.onboardingReadiness.and.returnValues(first, of({ ...complete(), hospitalId: 'h-2' }));
    const fixture = render({ hospitalId: 'h-1' });

    fixture.componentRef.setInput('hospitalId', 'h-2');
    fixture.detectChanges();
    first.next(fresh());
    fixture.detectChanges();

    expect(el(fixture, 'onboarding-booking-state')!.textContent).toContain(
      'PLATFORM_SETUP.CHECKLIST.BOOKABLE',
    );
  });

  // ── class guard: no step links to a page its readers cannot open ────

  /** The `data.roles` of the route a path opens (the shell's children). */
  function rolesOf(path: string, table: Route[] = appRoutes): string[] | undefined {
    const wanted = path.split('/').filter(Boolean).join('/');
    for (const route of table) {
      if (route.path === wanted) return (route.data?.['roles'] as string[] | undefined) ?? [];
      if (route.children) {
        const found = rolesOf(path, route.children);
        if (found) return found;
      }
    }
    return undefined;
  }

  it('every step has a route, and the route admits both roles that read the checklist', () => {
    for (const step of STEPS) {
      const route = ONBOARDING_STEP_ROUTES[step];
      const roles = rolesOf(route);
      expect(roles).withContext(`${step} → ${route}: no such route`).toBeDefined();
      for (const reader of ['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN']) {
        expect(roles!.length === 0 || roleSatisfies(roles!, reader))
          .withContext(`${step} → ${route} refuses ${reader}`)
          .toBeTrue();
      }
    }
  });

  it('fits its box at 1366, 1024, 768 and 390 px wide', () => {
    const fixture = render({ hospitalId: 'h-1' });
    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    for (const width of [1366, 1024, 768, 390]) {
      host.style.width = `${width - 32}px`;
      fixture.detectChanges();
      const card = el(fixture, 'onboarding-checklist')!;
      expect(card.scrollWidth)
        .withContext(`checklist at ${width}px`)
        .toBeLessThanOrEqual(card.clientWidth + 1);
    }
  });
});
