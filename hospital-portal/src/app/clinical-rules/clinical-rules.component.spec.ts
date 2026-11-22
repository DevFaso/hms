import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import {
  ClinicalRuleStatus,
  ClinicalRulesOverview,
  RuleSignoffStatus,
} from '../services/clinical-rules.service';
import { answerReasonDialog } from '../shared/reason-dialog/reason-dialog.testing';
import { expectEmptySubmitRefusal, expectFieldsMarked } from '../testing/form-refusal-behaviour';
import { ClinicalRulesComponent } from './clinical-rules.component';
import fr from '../../assets/i18n/fr.json';

@Component({
  standalone: true,
  imports: [ClinicalRulesComponent],
  template: `<div class="host" [style.width.px]="width()"><app-clinical-rules /></div>`,
})
class HostComponent {
  width = signal(1366);
}

const URL = '/hospitals/h-1/clinical-rules';

function rule(key: string, status: RuleSignoffStatus = 'PENDING'): ClinicalRuleStatus {
  return {
    key,
    version: '2026-11-20.1',
    title: { en: `Rule ${key}`, fr: `Règle ${key}`, es: `Regla ${key}` },
    description: { en: 'What it does', fr: 'Ce qu’elle fait', es: 'Lo que hace' },
    source: {
      en: 'Loi 030-2008 art. 17-18; Code pénal art. 525-2',
      fr: 'Loi 030-2008, art. 17 et 18 ; Code pénal, art. 525-2',
      es: 'Loi 030-2008 art. 17-18; Code pénal art. 525-2',
    },
    approverRole: { en: 'Jurist', fr: 'Juriste burkinabè', es: 'Jurista' },
    enforcedIn: ['ProxySensitivityPolicy', 'MinorResultReleaseServiceImpl'],
    status,
    signoff:
      status === 'PENDING'
        ? null
        : {
            id: 's-1',
            ruleVersion: '2026-11-20.1',
            status,
            signerName: 'Dr Awa Sawadogo',
            signerTitle: 'Médecin-chef',
            signerUserId: null,
            signedOn: '2026-11-20',
            note: null,
            recordedByName: 'Admin Clinique',
            recordedAt: '2026-11-21T09:00:00',
            revokedAt: status === 'REVOKED' ? '2026-11-21T10:00:00' : null,
            revokedByName: status === 'REVOKED' ? 'Admin Clinique' : null,
            revokeReason: status === 'REVOKED' ? 'Erreur de saisie' : null,
          },
    lastSignedVersion: null,
    area: 'RECORDS',
    sourceUrl: key === 'MINOR_HIV_RESULT_RELEASE' ? 'https://example.org/source' : null,
  };
}

function overview(canManage: boolean, rules: ClinicalRuleStatus[]): ClinicalRulesOverview {
  return {
    hospitalId: 'h-1',
    canManage,
    signedCount: rules.filter((r) => r.status === 'SIGNED').length,
    total: rules.length,
    rules,
  };
}

/**
 * V356: « Règles cliniques et validation » — every provisional rule set and
 * this hospital's sign-off; the admin records or revokes, staff read.
 */
describe('ClinicalRulesComponent (V356)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HTMLElement;
  let httpMock: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;
  let roleContext: RoleContextService;

  beforeEach(async () => {
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    await TestBed.configureTestingModule({
      imports: [HostComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ToastService, useValue: toast },
        {
          provide: AuthService,
          useValue: {
            getUserProfile: () => ({ firstName: 'Awa', lastName: 'Sawadogo' }),
            getUserId: () => 'u-1',
          },
        },
      ],
    }).compileComponents();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    httpMock = TestBed.inject(HttpTestingController);
    roleContext = TestBed.inject(RoleContextService);
    roleContext.activeHospitalId = 'h-1';
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  const q = <T extends HTMLElement>(testId: string) =>
    host.querySelector<T>(`[data-testid="${testId}"]`);

  function load(current: ClinicalRulesOverview): void {
    httpMock.expectOne(URL).flush(current);
    fixture.detectChanges();
  }

  async function type(testId: string, value: string): Promise<void> {
    await fixture.whenStable();
    const input = q<HTMLInputElement>(testId)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('lists each rule set with its source, approver and status here, and the progress', () => {
    load(
      overview(true, [rule('MINOR_HIV_RESULT_RELEASE'), rule('PRESCRIPTION_RENEWAL', 'SIGNED')]),
    );

    expect(q('clinical-rules-progress')!.textContent).toContain('1 / 2');
    const cards = host.querySelectorAll('.rule-card');
    expect(cards.length).toBe(2);
    expect(cards[0].textContent).toContain('Règle MINOR_HIV_RESULT_RELEASE');
    expect(cards[0].textContent).toContain('Loi 030-2008');
    expect(cards[0].textContent).toContain('Juriste burkinabè');
    expect(cards[0].textContent).toContain('Provisoire');
    expect(cards[1].textContent).toContain('Validée le');
    expect(cards[1].textContent).toContain('Médecin-chef (Dr Awa Sawadogo)');
    expect(cards[0].querySelector('[data-testid="rule-sign"]')).not.toBeNull();
    expect(cards[1].querySelector('[data-testid="rule-revoke"]')).not.toBeNull();
    // The page says a sign-off changes no rule's behaviour.
    expect(host.textContent).toContain('ne change que la mention « provisoire »');
  });

  it('shows a translated area, never a class name, and links a source that has a public page', () => {
    load(overview(true, [rule('MINOR_HIV_RESULT_RELEASE'), rule('PRESCRIPTION_RENEWAL')]));

    const cards = host.querySelectorAll('.rule-card');
    expect(cards[0].querySelector('[data-testid="rule-area"]')!.textContent).toContain(
      'Dossier patient et confidentialité',
    );
    expect(host.textContent).not.toContain('ProxySensitivityPolicy');
    expect(host.querySelector('.rule-code')).toBeNull();
    const link = cards[0].querySelector<HTMLAnchorElement>('[data-testid="rule-source-link"]')!;
    expect(link.getAttribute('href')).toBe('https://example.org/source');
    expect(link.getAttribute('rel')).toContain('noopener');
    expect(cards[1].querySelector('[data-testid="rule-source-link"]')).toBeNull();
  });

  it('is read-only for staff who do not manage it: no action, and it says who records', () => {
    load(overview(false, [rule('MINOR_HIV_RESULT_RELEASE')]));

    expect(q('clinical-rules-read-only')).not.toBeNull();
    expect(q('rule-sign')).toBeNull();
    expect(q('rule-revoke')).toBeNull();
  });

  it('records a sign-off for the version shown, and the card turns « Validée »', async () => {
    load(overview(true, [rule('MINOR_HIV_RESULT_RELEASE')]));
    q<HTMLButtonElement>('rule-sign')!.click();
    fixture.detectChanges();
    await type('rule-signer-name', 'Dr Awa Sawadogo');

    q<HTMLButtonElement>('rule-sign-confirm')!.click();
    const req = httpMock.expectOne(`${URL}/MINOR_HIV_RESULT_RELEASE/signoffs`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(
      jasmine.objectContaining({
        ruleVersion: '2026-11-20.1',
        signerName: 'Dr Awa Sawadogo',
        signerTitle: 'Médecin-chef',
        signerUserId: null,
      }),
    );
    req.flush(rule('MINOR_HIV_RESULT_RELEASE', 'SIGNED'));
    fixture.detectChanges();

    expect(toast.success).toHaveBeenCalled();
    expect(q('rule-sign-dialog')).toBeNull();
    expect(q('clinical-rules-progress')!.textContent).toContain('1 / 1');
    expect(q('rule-signoff')!.textContent).toContain('Médecin-chef');
  });

  it('a blank signer is refused at its field, starred and named, and nothing is sent', async () => {
    load(overview(true, [rule('MINOR_HIV_RESULT_RELEASE')]));
    q<HTMLButtonElement>('rule-sign')!.click();
    fixture.detectChanges();
    await fixture.whenStable();

    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="rule-sign-confirm"]',
    });

    expect(marked).toContain('rule-signer-name');
    httpMock.expectNone(`${URL}/MINOR_HIV_RESULT_RELEASE/signoffs`);
  });

  it('a field the server refuses is marked in the server’s words', async () => {
    load(overview(true, [rule('MINOR_HIV_RESULT_RELEASE')]));
    q<HTMLButtonElement>('rule-sign')!.click();
    fixture.detectChanges();
    await type('rule-signer-name', 'Dr A');
    q<HTMLButtonElement>('rule-sign-confirm')!.click();
    httpMock.expectOne(`${URL}/MINOR_HIV_RESULT_RELEASE/signoffs`).flush(
      {
        error: 'Validation Failed',
        message: 'doit être une date passée ou présente',
        fieldErrors: { signedOn: 'doit être une date passée ou présente' },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    expectFieldsMarked(fixture, { 'rule-signed-on': 'doit être une date passée ou présente' });
  });

  it('« Je suis le signataire » records the admin’s own account and name', async () => {
    load(overview(true, [rule('MINOR_HIV_RESULT_RELEASE')]));
    q<HTMLButtonElement>('rule-sign')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const self = q<HTMLInputElement>('rule-signer-self')!;
    self.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    q<HTMLButtonElement>('rule-sign-confirm')!.click();
    const req = httpMock.expectOne(`${URL}/MINOR_HIV_RESULT_RELEASE/signoffs`);
    expect(req.request.body.signerUserId).toBe('u-1');
    expect(req.request.body.signerName).toBe('Awa Sawadogo');
    req.flush(rule('MINOR_HIV_RESULT_RELEASE', 'SIGNED'));
  });

  it('revokes a sign-off with a reason; the card says it was revoked', () => {
    load(overview(true, [rule('PRESCRIPTION_RENEWAL', 'SIGNED')]));
    q<HTMLButtonElement>('rule-revoke')!.click();
    answerReasonDialog(fixture, 'Erreur de saisie');

    const req = httpMock.expectOne(`${URL}/PRESCRIPTION_RENEWAL/revocation`);
    expect(req.request.body).toEqual({ reason: 'Erreur de saisie' });
    req.flush(rule('PRESCRIPTION_RENEWAL', 'REVOKED'));
    fixture.detectChanges();

    expect(q('rule-signoff')!.textContent).toContain('révoquée');
    expect(q('rule-signoff')!.textContent).toContain('Erreur de saisie');
    expect(q('rule-sign')).not.toBeNull();
  });

  it('a super-admin with no hospital selected is asked to choose one, and nothing is read', () => {
    httpMock.expectOne(URL).flush(overview(true, []));
    roleContext.activeHospitalId = null;
    fixture.detectChanges();

    expect(q('clinical-rules-no-hospital')).not.toBeNull();
    httpMock.expectNone(() => true);
  });

  for (const width of [1366, 1024, 768, 390]) {
    it(`never spills past a ${width}px container`, () => {
      fixture.componentInstance.width.set(width);
      fixture.detectChanges();
      load(
        overview(true, [
          rule('MINOR_HIV_RESULT_RELEASE'),
          rule('PRESCRIPTION_RENEWAL', 'SIGNED'),
          rule('DEFERRED_BILLING', 'REVOKED'),
        ]),
      );

      const box = host.querySelector<HTMLElement>('.host')!;
      expect(box.scrollWidth).toBeLessThanOrEqual(box.clientWidth + 1);
      const right = box.getBoundingClientRect().right;
      for (const part of Array.from(
        box.querySelectorAll<HTMLElement>('p, dd, dt, h2, button, .rule-chip'),
      )) {
        expect(part.getBoundingClientRect().right).toBeLessThanOrEqual(right + 1);
      }
    });
  }
});
