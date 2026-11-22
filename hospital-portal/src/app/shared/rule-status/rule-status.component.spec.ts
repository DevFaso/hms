import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { RoleContextService } from '../../core/role-context.service';
import { ClinicalRuleStatus, ClinicalRulesOverview } from '../../services/clinical-rules.service';
import { RuleStatusComponent } from './rule-status.component';
import fr from '../../../assets/i18n/fr.json';

@Component({
  standalone: true,
  imports: [RuleStatusComponent],
  template: `
    <p data-testid="full">
      <app-rule-status ruleKey="PRESCRIPTION_RENEWAL" [detail]="detail()"
        >Règle provisoire, en attente d’approbation.</app-rule-status
      >
    </p>
    <p data-testid="chip">
      <app-rule-status ruleKey="PRESCRIPTION_RENEWAL" [compact]="true">provisoire</app-rule-status>
    </p>
  `,
})
class HostComponent {
  detail = signal<string | null>(null);
}

const URL = '/hospitals/h-1/clinical-rules';

function overview(signed: boolean): ClinicalRulesOverview {
  const rule: ClinicalRuleStatus = {
    key: 'PRESCRIPTION_RENEWAL',
    version: '2026-11-20.1',
    title: { en: 'Prescription renewal', fr: 'Renouvellement d’ordonnance', es: 'Renovación' },
    description: { en: 'd', fr: 'd', es: 'd' },
    source: { en: 'CSP art. 276', fr: 'CSP art. 276', es: 'CSP art. 276' },
    approverRole: { en: 'Pharmacists', fr: 'Ordre des pharmaciens', es: 'Farmacéuticos' },
    enforcedIn: ['RefillApprovalServiceImpl'],
    status: signed ? 'SIGNED' : 'PENDING',
    signoff: signed
      ? {
          id: 's-1',
          ruleVersion: '2026-11-20.1',
          status: 'SIGNED',
          signerName: 'Dr Awa Sawadogo',
          signerTitle: 'Médecin-chef',
          signerUserId: null,
          signedOn: '2026-11-20',
          note: null,
          recordedByName: null,
          recordedAt: '2026-11-21T09:00:00',
          revokedAt: null,
          revokedByName: null,
          revokeReason: null,
        }
      : null,
    lastSignedVersion: null,
    area: 'PRESCRIBING',
    sourceUrl: null,
  };
  return {
    hospitalId: 'h-1',
    canManage: false,
    signedCount: signed ? 1 : 0,
    total: 1,
    rules: [rule],
  };
}

/**
 * V356: where a rule says « provisoire », it says so for THIS hospital —
 * pending with its source, or signed with the date and the signer's title.
 */
describe('RuleStatusComponent (V356)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HTMLElement;
  let httpMock: HttpTestingController;

  async function setUp(hospitalId: string | null): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [HostComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    }).compileComponents();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    httpMock = TestBed.inject(HttpTestingController);
    TestBed.inject(RoleContextService).activeHospitalId = hospitalId;
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
  }

  afterEach(() => httpMock.verify());

  const text = (testId: string) =>
    (host.querySelector(`[data-testid="${testId}"]`)?.textContent ?? '')
      .replace(/\s+/g, ' ')
      .trim();

  it('keeps the screen’s own wording while the hospital is unknown, and asks nothing', async () => {
    await setUp(null);
    httpMock.expectNone(() => true);
    expect(text('full')).toBe('Règle provisoire, en attente d’approbation.');
  });

  it('reads the hospital’s list once for every label on the page', async () => {
    await setUp('h-1');
    httpMock.expectOne(URL).flush(overview(false));
    fixture.detectChanges();
    httpMock.expectNone(URL);
  });

  it('pending: « Provisoire — source : … » with the approver', async () => {
    await setUp('h-1');
    httpMock.expectOne(URL).flush(overview(false));
    fixture.detectChanges();

    expect(text('full')).toContain('Provisoire — source : CSP art. 276');
    expect(text('full')).toContain('Ordre des pharmaciens');
    expect(text('chip')).toBe('Provisoire');
  });

  it('signed: « Validée le <date> par <titre> », with what the rule does kept before it', async () => {
    await setUp('h-1');
    fixture.componentInstance.detail.set('Seul un prescripteur approuve un renouvellement.');
    httpMock.expectOne(URL).flush(overview(true));
    fixture.detectChanges();

    expect(text('full')).toContain('Seul un prescripteur approuve un renouvellement.');
    expect(text('full')).toContain('Validée le 20 nov. 2026 par Médecin-chef');
    expect(text('full')).not.toContain('provisoire');
    expect(text('chip')).toBe('Validée');
  });

  it('a role that may not read the list keeps the screen’s own wording, and is not asked again', async () => {
    await setUp('h-1');
    httpMock.expectOne(URL).flush('no', { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(text('full')).toBe('Règle provisoire, en attente d’approbation.');
  });
});
