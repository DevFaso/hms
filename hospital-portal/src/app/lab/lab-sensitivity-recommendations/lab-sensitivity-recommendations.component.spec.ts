import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import { LabSensitivityRecommendation } from '../../services/sensitive-policy.service';
import { LabSensitivityRecommendationsComponent } from './lab-sensitivity-recommendations.component';
import fr from '../../../assets/i18n/fr.json';

@Component({
  standalone: true,
  imports: [LabSensitivityRecommendationsComponent],
  template: `
    <div class="host" [style.width.px]="width()">
      <app-lab-sensitivity-recommendations (applied)="applied = applied + 1" />
    </div>
  `,
})
class HostComponent {
  width = signal(1366);
  applied = 0;
}

const LIST = '/lab-test-definitions/sensitivity-recommendations';

function row(overrides: Partial<LabSensitivityRecommendation>): LabSensitivityRecommendation {
  return {
    definitionId: 'def-hiv',
    testCode: 'SRV',
    name: 'Sérologie VIH 1 et 2 (test rapide Determine)',
    panel: false,
    currentCategory: null,
    recommendedCategory: 'HIV',
    matchedOn: 'NAME',
    matchedText: 'Sérologie VIH 1 et 2 (test rapide Determine)',
    applied: false,
    provisional: true,
    ...overrides,
  };
}

const ROWS: LabSensitivityRecommendation[] = [
  row({}),
  row({
    definitionId: 'def-tpha',
    testCode: 'TPHA',
    name: 'Sérologie syphilis',
    recommendedCategory: 'STI',
    matchedOn: 'CODE',
    matchedText: 'TPHA',
  }),
  row({
    definitionId: 'def-bpn',
    testCode: 'BPN',
    name: 'Bilan prénatal (VIH, syphilis, AgHBs, groupe sanguin)',
    panel: true,
    matchedOn: 'COMPONENT',
    matchedText: 'Sérologie VIH',
    currentCategory: 'REPRODUCTIVE_HEALTH',
  }),
  row({
    definitionId: 'def-hbs',
    testCode: 'AGHBS',
    name: 'Antigène HBs',
    recommendedCategory: 'VIRAL_HEPATITIS',
    currentCategory: 'VIRAL_HEPATITIS',
    applied: true,
  }),
];

/**
 * Policy 2026-11-20 (provisional): the recommended sensitive categories are a
 * suggestion the lab director confirms, match by match; nothing is applied
 * silently, and the list says it is provisional.
 */
describe('LabSensitivityRecommendationsComponent (policy 2026-11-20)', () => {
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

  function open(rows: LabSensitivityRecommendation[] = ROWS): void {
    q<HTMLButtonElement>('reco-open')!.click();
    fixture.detectChanges();
    httpMock.expectOne(LIST).flush({ success: true, data: rows });
    fixture.detectChanges();
  }

  it('asks nothing of the server until the director opens the dialog', () => {
    expect(q('reco-open')!.textContent).toContain('Appliquer les catégories recommandées');
    expect(q('reco-dialog')).toBeNull();
    httpMock.expectNone(LIST);
  });

  it('shows each match with what matched and the recommended category, and says the list is provisional', () => {
    open();

    expect(q('reco-provisional')!.textContent).toContain('provisoire');
    const pending = q('reco-pending')!.textContent ?? '';
    expect(pending).toContain('Sérologie VIH 1 et 2');
    expect(pending).toContain('Catégorie recommandée : VIH');
    expect(pending).toContain('Reconnu par son code : TPHA');
    expect(pending).toContain('Infections sexuellement transmissibles');
    // A combined panel says it is hidden whole, and that a category would be replaced.
    expect(pending).toContain('Un bilan est masqué en entier');
    expect(q('reco-replaces')!.textContent).toContain('Santé reproductive');
    // What the catalogue already carries is listed apart, with no box to tick.
    expect(q('reco-done')!.textContent).toContain('Antigène HBs');
    expect(q('reco-check-AGHBS')).toBeNull();
  });

  it('ticks the tests that have no category yet, and leaves one the director classified otherwise unticked', () => {
    open();

    expect(q<HTMLInputElement>('reco-check-SRV')!.checked).toBeTrue();
    expect(q<HTMLInputElement>('reco-check-TPHA')!.checked).toBeTrue();
    expect(q<HTMLInputElement>('reco-check-BPN')!.checked).toBeFalse();
    expect(q('reco-apply')!.textContent).toContain('(2)');
  });

  it('applies only what is ticked, each with its recommended category, then tells the host', () => {
    open();
    const tpha = q<HTMLInputElement>('reco-check-TPHA')!;
    tpha.checked = false;
    tpha.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    q<HTMLButtonElement>('reco-apply')!.click();
    const request = httpMock.expectOne(`${LIST}/apply`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ items: [{ definitionId: 'def-hiv', category: 'HIV' }] });
    request.flush({ success: true, data: [] });
    fixture.detectChanges();

    expect(fixture.componentInstance.applied).toBe(1);
    expect(toast.success).toHaveBeenCalled();
    expect(q('reco-dialog')).toBeNull();
  });

  it('applies nothing when nothing is ticked, and nothing when the dialog is closed', () => {
    open([row({ currentCategory: 'STI' })]);
    expect(q<HTMLButtonElement>('reco-apply')!.disabled).toBeTrue();

    q<HTMLButtonElement>('reco-apply')!.click();
    host.querySelector<HTMLButtonElement>('.reco-close')!.click();
    fixture.detectChanges();

    expect(q('reco-dialog')).toBeNull();
    httpMock.expectNone(`${LIST}/apply`);
    expect(fixture.componentInstance.applied).toBe(0);
  });

  it('keeps the dialog open and shows the server refusal when the apply is refused', () => {
    open();
    q<HTMLButtonElement>('reco-apply')!.click();
    httpMock
      .expectOne(`${LIST}/apply`)
      .flush({ message: 'Liste modifiée' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalled();
    expect(q('reco-dialog')).not.toBeNull();
    expect(fixture.componentInstance.applied).toBe(0);
  });

  it('says so when no test of the catalogue is on the list, and when the list cannot be read', () => {
    open([]);
    expect(q('reco-empty')).not.toBeNull();
    host.querySelector<HTMLButtonElement>('.reco-close')!.click();
    fixture.detectChanges();

    q<HTMLButtonElement>('reco-open')!.click();
    fixture.detectChanges();
    httpMock.expectOne(LIST).flush('down', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(q('reco-error')).not.toBeNull();
  });

  // The dialog at a desktop, a small laptop, a tablet and a phone.
  for (const width of [1366, 1024, 768, 390]) {
    it(`never spills past a ${width}px screen`, () => {
      fixture.componentInstance.width.set(width);
      open();
      const dialog = q<HTMLElement>('reco-dialog')!;
      dialog.style.maxWidth = `${Math.min(720, width - 32)}px`;
      fixture.detectChanges();

      expect(dialog.scrollWidth).toBeLessThanOrEqual(dialog.clientWidth + 1);
      const right = dialog.getBoundingClientRect().right;
      for (const part of Array.from(
        dialog.querySelectorAll<HTMLElement>('li, p, label, button, h2'),
      )) {
        expect(part.getBoundingClientRect().right).toBeLessThanOrEqual(right + 1);
      }
      const trigger = q<HTMLElement>('reco-open')!;
      const box = host.querySelector<HTMLElement>('.host')!;
      expect(trigger.getBoundingClientRect().right).toBeLessThanOrEqual(
        box.getBoundingClientRect().right + 1,
      );
    });
  }
});
