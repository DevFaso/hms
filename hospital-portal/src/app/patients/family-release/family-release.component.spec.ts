import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import { FamilyReleaseOverview } from '../../services/sensitive-policy.service';
import { expectEmptySubmitRefusal, expectFieldsMarked } from '../../testing/form-refusal-behaviour';
import { FamilyReleaseComponent } from './family-release.component';
import fr from '../../../assets/i18n/fr.json';

@Component({
  standalone: true,
  imports: [FamilyReleaseComponent],
  template: `
    <div class="host" [style.width.px]="width()">
      <app-family-release [patientId]="patientId()" />
    </div>
  `,
})
class HostComponent {
  width = signal(1366);
  patientId = signal('p-1');
}

const URL = '/patients/p-1/family-releases';
const REASON = 'Annonce faite aux parents après conseil, à leur demande.';

function overview(overrides: Partial<FamilyReleaseOverview> = {}): FamilyReleaseOverview {
  return {
    patientId: 'p-1',
    minor: true,
    adolescentAge: 12,
    adolescentAgeIsDefault: true,
    guardians: [
      {
        grantId: 'g-mother',
        holderName: 'Awa Sawadogo',
        relationship: 'PARENT',
        verified: true,
        verifiedAt: '2026-11-18T09:00:00',
      },
      {
        grantId: 'g-father',
        holderName: 'Issouf Sawadogo née Ouédraogo-Compaoré de Ouagadougou',
        relationship: 'LEGAL_GUARDIAN',
        verified: false,
        verifiedAt: null,
      },
    ],
    results: [
      {
        labOrderId: 'o-hiv',
        testName: 'Sérologie VIH 1 et 2 (test rapide Determine, confirmation)',
        category: 'HIV',
        resultedAt: '2026-11-19T10:30:00',
        releases: [],
      },
    ],
    ...overrides,
  };
}

/**
 * Policy 2026-11-20 (provisional): a minor's HIV result stays hidden from the
 * family; a clinician releases it to one verified parent, with a reason.
 */
describe('FamilyReleaseComponent (policy 2026-11-20)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HTMLElement;
  let httpMock: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    await TestBed.configureTestingModule({
      imports: [HostComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  const q = <T extends HTMLElement>(testId: string) =>
    host.querySelector<T>(`[data-testid="${testId}"]`);

  function load(view: FamilyReleaseOverview = overview()): void {
    httpMock.expectOne(URL).flush(view);
    fixture.detectChanges();
  }

  function type(testId: string, value: string): void {
    const field = q<HTMLTextAreaElement>(testId)!;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function pick(grantId: string): void {
    const select = q<HTMLSelectElement>('release-guardian')!;
    select.value = grantId;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('shows nothing for an adult, and nothing when the server refuses the reader', () => {
    load(overview({ minor: false, guardians: [], results: [] }));
    expect(q('family-release')).toBeNull();

    fixture.componentInstance.patientId.set('p-2');
    fixture.detectChanges();
    httpMock
      .expectOne('/patients/p-2/family-releases')
      .flush({ message: 'refusé' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(q('family-release')).toBeNull();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('lists the parents, the hidden HIV result, and says the rule and the age of 12 are provisional', () => {
    load();

    expect(q('family-release-provisional')!.textContent).toContain('provisoire');
    expect(q('family-release-provisional')!.textContent).toContain('12 ans');
    const guardians = q('family-release-guardians')!.textContent ?? '';
    expect(guardians).toContain('Awa Sawadogo');
    expect(guardians).toContain('Tuteur légal');
    expect(host.querySelectorAll('[data-testid="guardian-verified"]').length).toBe(1);
    expect(host.querySelectorAll('[data-testid="guardian-verify"]').length).toBe(1);
    const results = q('family-release-results')!.textContent ?? '';
    expect(results).toContain('Sérologie VIH');
    expect(results).toContain('VIH');
    expect(q('result-released')).toBeNull();
  });

  it('releases the result to the verified mother only once a reason of 10 characters is given', () => {
    load();
    q<HTMLButtonElement>('result-release-o-hiv')!.click();
    fixture.detectChanges();
    pick('g-mother');

    // Never silently disabled. No reason: the click is stopped and the field is marked.
    const submit = q<HTMLButtonElement>('release-submit')!;
    expect(submit.disabled).toBeFalse();
    submit.click();
    fixture.detectChanges();
    expect(q('release-reason')!.getAttribute('aria-invalid')).toBe('true');
    // A reason under ten characters: stopped, and the button says why.
    type('release-reason', 'court');
    expect(submit.getAttribute('data-submit-blocked')).toBe('true');
    submit.click();
    fixture.detectChanges();
    expect(q('family-release-form')!.textContent).toContain('au moins 10 caractères');
    httpMock.expectNone(`${URL}/lab-orders/o-hiv`);
    // A verified parent needs no attestation.
    expect(q('release-attest')).toBeNull();

    type('release-reason', `  ${REASON}  `);
    q<HTMLButtonElement>('release-submit')!.click();
    const request = httpMock.expectOne(`${URL}/lab-orders/o-hiv`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      grantId: 'g-mother',
      reason: REASON,
      relationshipVerified: false,
    });
    request.flush(
      overview({
        results: [
          {
            ...overview().results[0],
            releases: [
              {
                grantId: 'g-mother',
                holderName: 'Awa Sawadogo',
                releasedAt: '2026-11-20T08:00:00',
                reason: REASON,
              },
            ],
          },
        ],
      }),
    );
    fixture.detectChanges();

    expect(toast.success).toHaveBeenCalled();
    expect(q('family-release-form')).toBeNull();
    expect(q('result-released')!.textContent).toContain('Awa Sawadogo');
    expect(q('result-release-reason')!.textContent).toContain(REASON);
    // Still releasable to the other guardian.
    expect(q('result-release-o-hiv')).not.toBeNull();
  });

  it('asks the clinician to attest the relationship of a parent nobody verified', () => {
    load();
    q<HTMLButtonElement>('result-release-o-hiv')!.click();
    fixture.detectChanges();
    pick('g-father');
    type('release-reason', REASON);

    expect(q('release-attest')).not.toBeNull();
    const submit = q<HTMLButtonElement>('release-submit')!;
    expect(submit.getAttribute('data-submit-blocked')).toBe('true');
    submit.click();
    fixture.detectChanges();
    expect(q('family-release-form')!.textContent).toContain('vérifié le lien de parenté');
    httpMock.expectNone(`${URL}/lab-orders/o-hiv`);

    const attest = q<HTMLInputElement>('release-attest')!;
    attest.checked = true;
    attest.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    q<HTMLButtonElement>('release-submit')!.click();

    const request = httpMock.expectOne(`${URL}/lab-orders/o-hiv`);
    expect(request.request.body.relationshipVerified).toBeTrue();
    request.flush(overview());
  });

  it('keeps the form and shows the server refusal when the release is refused', () => {
    load();
    q<HTMLButtonElement>('result-release-o-hiv')!.click();
    fixture.detectChanges();
    pick('g-mother');
    type('release-reason', REASON);
    q<HTMLButtonElement>('release-submit')!.click();
    httpMock
      .expectOne(`${URL}/lab-orders/o-hiv`)
      .flush(
        { message: 'Le laboratoire n’a pas encore validé ce résultat.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledWith('Le laboratoire n’a pas encore validé ce résultat.');
    expect(q('family-release-form')).not.toBeNull();
    expect(q<HTMLTextAreaElement>('release-reason')!.value).toBe(REASON);
  });

  it('an empty release is refused at its fields, starred and named, and nothing is sent', () => {
    load();
    q<HTMLButtonElement>('result-release-o-hiv')!.click();
    fixture.detectChanges();

    const marked = expectEmptySubmitRefusal(fixture, { button: '[data-testid="release-submit"]' });

    expect(marked).toContain('family-release-guardian');
    expect(marked).toContain('family-release-reason');
    httpMock.expectNone(`${URL}/lab-orders/o-hiv`);
  });

  it('a reason the server refuses is marked on the reason field, in the server’s words', () => {
    load();
    q<HTMLButtonElement>('result-release-o-hiv')!.click();
    fixture.detectChanges();
    pick('g-mother');
    type('release-reason', REASON);
    q<HTMLButtonElement>('release-submit')!.click();
    httpMock.expectOne(`${URL}/lab-orders/o-hiv`).flush(
      {
        error: 'Validation Failed',
        message: 'Indiquez le motif.',
        fieldErrors: { reason: 'Indiquez le motif de la communication de ce résultat.' },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    expectFieldsMarked(fixture, {
      'family-release-reason': 'Indiquez le motif de la communication de ce résultat.',
    });
  });

  it('records that a parent’s relationship was verified', () => {
    load();
    q<HTMLButtonElement>('guardian-verify')!.click();
    const request = httpMock.expectOne(`${URL}/guardians/g-father/verified`);
    expect(request.request.method).toBe('POST');
    request.flush(
      overview({
        guardians: overview().guardians.map((g) => ({ ...g, verified: true })),
      }),
    );
    fixture.detectChanges();

    expect(host.querySelectorAll('[data-testid="guardian-verified"]').length).toBe(2);
    expect(q('guardian-verify')).toBeNull();
  });

  it('says so when no parent holds a family access, and when no sensitive result is hidden', () => {
    load(overview({ guardians: [], results: [] }));
    expect(q('family-release-no-guardian')).not.toBeNull();
    expect(q('family-release-no-result')).not.toBeNull();
  });

  // The chart column at a desktop, a small laptop, a tablet and a phone.
  for (const width of [1366, 1024, 768, 390]) {
    it(`never spills past a ${width}px container, form open`, () => {
      fixture.componentInstance.width.set(width);
      fixture.detectChanges();
      load();
      q<HTMLButtonElement>('result-release-o-hiv')!.click();
      fixture.detectChanges();
      pick('g-father');

      const box = host.querySelector<HTMLElement>('.host')!;
      expect(box.scrollWidth).toBeLessThanOrEqual(box.clientWidth + 1);
      const right = box.getBoundingClientRect().right;
      for (const part of Array.from(
        box.querySelectorAll<HTMLElement>('li, p, label, button, select, textarea, h3, h4'),
      )) {
        expect(part.getBoundingClientRect().right).toBeLessThanOrEqual(right + 1);
      }
    });
  }
});
