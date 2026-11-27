import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MySharingComponent } from './my-sharing.component';
import {
  AccessLogEntry,
  DisclosureAccounting,
  PatientPortalService,
  PatientProfileDTO,
  RecordSharingOptOut,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';

/**
 * "Who accessed my record" (E9 #66). The access-log half of these specs
 * exists because an earlier suite stubbed the log as empty and never
 * rendered a row, so bindings that matched nothing the backend sent went
 * unnoticed (Tier 2 item 39). The opt-out half pins the one control the
 * page still offers.
 */
describe('MySharingComponent', () => {
  let component: MySharingComponent;
  let fixture: ComponentFixture<MySharingComponent>;

  const entry = (over: Partial<AccessLogEntry> = {}): AccessLogEntry => ({
    id: 'a1',
    actor: 'Dr Alice Traore',
    actorRole: 'DOCTOR',
    hospitalName: 'City Clinic',
    eventType: 'PATIENT_ACCESS',
    entityType: 'PATIENT',
    resourceId: 'p1',
    description: 'Doctor record view',
    status: 'SUCCESS',
    timestamp: '2026-08-20T10:30:00',
    category: 'TREATMENT_ACCESS',
    externalDisclosure: false,
    ...over,
  });

  const accounting = (
    entries: AccessLogEntry[],
    over: Partial<DisclosureAccounting> = {},
  ): DisclosureAccounting => ({
    from: null,
    to: null,
    countsByCategory: {},
    totalEvents: entries.length,
    externalDisclosures: 0,
    entries,
    totalPages: 1,
    page: 0,
    ...over,
  });

  const optOutState = (inForce: boolean): RecordSharingOptOut => ({
    patientId: 'p-1',
    inForce,
    optedOutAt: inForce ? '2026-09-12T08:00:00' : null,
    reason: inForce ? 'Je préfère' : null,
    revokedAt: null,
  });

  let disclosureResponse: () => ReturnType<PatientPortalService['getMyDisclosures']>;
  let optOutResponse: () => ReturnType<PatientPortalService['getMyOptOut']>;
  let portal: jasmine.SpyObj<PatientPortalService>;

  const mockToast = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    disclosureResponse = () => of(accounting([]));
    optOutResponse = () => of(optOutState(false));
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getMyDisclosures',
      'getMyProfile',
      'getMyOptOut',
      'optOutOfSharing',
      'revokeOptOut',
    ]);
    portal.getMyDisclosures.and.callFake(() => disclosureResponse());
    portal.getMyProfile.and.returnValue(of({ id: 'p-1' } as PatientProfileDTO));
    portal.getMyOptOut.and.callFake(() => optOutResponse());
    portal.optOutOfSharing.and.returnValue(of(optOutState(true)));
    portal.revokeOptOut.and.returnValue(of(optOutState(false)));
    mockToast.success.calls.reset();
    mockToast.error.calls.reset();

    await TestBed.configureTestingModule({
      imports: [MySharingComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: portal },
        { provide: ToastService, useValue: mockToast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MySharingComponent);
    component = fixture.componentInstance;
  });

  function el(testId: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`);
  }

  // ── Opt-out ──

  it('loads the opt-out for the signed-in patient and offers to close the door', () => {
    fixture.detectChanges();

    expect(portal.getMyOptOut).toHaveBeenCalledOnceWith('p-1');
    expect(component.optedOut()).toBeFalse();
    expect(el('opt-out-enable')).not.toBeNull();
    expect(el('opt-out-revoke')).toBeNull();
  });

  it('asks for a reason, then opts out with the stated reason and shows the new state', () => {
    fixture.detectChanges();

    el('opt-out-enable')!.click();
    fixture.detectChanges();
    expect(el('opt-out-form')).not.toBeNull();

    component.optOutReason = '  Je préfère  ';
    el('opt-out-confirm')!.click();
    fixture.detectChanges();

    expect(portal.optOutOfSharing).toHaveBeenCalledOnceWith('p-1', 'Je préfère');
    expect(component.optedOut()).toBeTrue();
    expect(el('opt-out-form')).toBeNull();
    expect(el('opt-out-revoke')).not.toBeNull();
    expect(mockToast.success).toHaveBeenCalledWith('PORTAL.SHARING.OPT_OUT.SAVED_ON');
  });

  it('sends no reason when the patient leaves it blank', () => {
    fixture.detectChanges();
    component.openOptOutForm();
    component.confirmOptOut();

    expect(portal.optOutOfSharing).toHaveBeenCalledOnceWith('p-1', null);
  });

  it('reopens sharing from an opted-out state', async () => {
    optOutResponse = () => of(optOutState(true));
    fixture.detectChanges();
    expect(component.optedOut()).toBeTrue();

    // QA CX-010: asks first, the record opens to other hospitals again.
    const ask = spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    el('opt-out-revoke')!.click();
    await new Promise((resolve) => setTimeout(resolve));
    expect(ask).toHaveBeenCalledTimes(1);
    fixture.detectChanges();

    expect(portal.revokeOptOut).toHaveBeenCalledOnceWith('p-1');
    expect(component.optedOut()).toBeFalse();
    expect(mockToast.success).toHaveBeenCalledWith('PORTAL.SHARING.OPT_OUT.SAVED_OFF');
  });

  it('shows a failure instead of "sharing is on" when the opt-out cannot be loaded', () => {
    optOutResponse = () => throwError(() => new Error('500'));
    fixture.detectChanges();

    expect(component.optOutFailed()).toBeTrue();
    expect(el('opt-out-failed')).not.toBeNull();
    expect(el('opt-out-enable')).toBeNull();
    expect(el('opt-out-status')).toBeNull();
  });

  it('keeps the current state and says so when saving fails', () => {
    portal.optOutOfSharing.and.returnValue(throwError(() => new Error('409')));
    fixture.detectChanges();
    component.openOptOutForm();
    component.confirmOptOut();

    expect(component.optedOut()).toBeFalse();
    expect(component.optOutSaving()).toBeFalse();
    expect(mockToast.error).toHaveBeenCalledWith('PORTAL.SHARING.OPT_OUT.FAILED');
  });

  // ── Access log ──

  it('loads the access log on arrival, without a tab to find first', () => {
    disclosureResponse = () => of(accounting([entry()]));
    fixture.detectChanges();

    expect(portal.getMyDisclosures).toHaveBeenCalledTimes(1);
    expect(component.accessLog().length).toBe(1);
  });

  it('renders the name of whoever accessed the record', () => {
    // THE REGRESSION. With the old bindings this row rendered as an empty
    // title and a bare " · " separator, and the only way to see it was to
    // put a row on the page — which no test did.
    disclosureResponse = () => of(accounting([entry()]));
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Dr Alice Traore');
    expect(text).toContain('City Clinic');
  });

  it('marks an emergency access so it is not lost among routine views', () => {
    disclosureResponse = () =>
      of(
        accounting(
          [
            entry({ id: 'e1', category: 'EMERGENCY_ACCESS', eventType: 'BREAK_GLASS_ACCESS' }),
            entry({ id: 'e2' }),
          ],
          { countsByCategory: { EMERGENCY_ACCESS: 1, TREATMENT_ACCESS: 1 } },
        ),
      );
    fixture.detectChanges();

    const emergency = (fixture.nativeElement as HTMLElement).querySelectorAll('.pli-emergency');
    expect(emergency.length).toBe(1);
  });

  it('marks a release to another hospital as an external disclosure', () => {
    disclosureResponse = () =>
      of(
        accounting([entry({ category: 'SHARED_WITH_PROVIDER', externalDisclosure: true })], {
          externalDisclosures: 1,
        }),
      );
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.pli-external').length).toBe(1);
  });

  it('QA D3451: a result released to a parent says who released it, to whom and which category, never the value', () => {
    disclosureResponse = () =>
      of(
        accounting(
          [
            entry({
              id: 'rel-1',
              actor: 'Test Médecin T1',
              eventType: 'SENSITIVE_RESULT_RELEASED_TO_PROXY',
              entityType: 'PROXY_SENSITIVE_RELEASE',
              description: 'Sensitive result (HIV) released to a verified parent or guardian',
              category: 'RELEASED_TO_FAMILY',
              externalDisclosure: true,
              recipientName: 'Patient Test 11',
              recipientRelationship: 'PARENT',
              resultCategory: 'HIV',
            }),
          ],
          { externalDisclosures: 1, countsByCategory: { RELEASED_TO_FAMILY: 1 } },
        ),
      );
    fixture.detectChanges();

    const row = (fixture.nativeElement as HTMLElement).querySelector('.portal-list-item')!;
    expect(row.classList).toContain('pli-external');
    expect(row.querySelector('.pli-title')?.textContent).toContain('Test Médecin T1');
    expect(row.querySelector('.pli-category')?.textContent).toContain(
      'PORTAL.SHARING.CATEGORY.RELEASED_TO_FAMILY',
    );
    const release = el('released-to-family')!;
    expect(release.textContent).toContain('R70_PRIVACY.RELEASED_TO');
    expect(release.textContent).toContain('R70_PRIVACY.RESULT_CATEGORY');
    // The patient's page does not print the audit description (staff wording), nor any value.
    expect(row.textContent).not.toContain('Sensitive result');
    expect(row.textContent).not.toContain('Positif');
    expect(component.resultCategoryLabelKey(component.accessLog()[0])).toBe(
      'SENSITIVITY_TAG.CATEGORY_HIV',
    );
  });

  it('QA D3451: a release whose recipient cannot be read is still listed, without a name', () => {
    disclosureResponse = () =>
      of(accounting([entry({ category: 'RELEASED_TO_FAMILY', externalDisclosure: true })]));
    fixture.detectChanges();

    const release = el('released-to-family')!;
    expect(release.textContent).toContain('R70_PRIVACY.RELEASED_TO_UNNAMED');
    expect(release.textContent).not.toContain('R70_PRIVACY.RESULT_CATEGORY');
  });

  it('an ordinary chart read carries no release line', () => {
    disclosureResponse = () => of(accounting([entry()]));
    fixture.detectChanges();

    expect(el('released-to-family')).toBeNull();
    expect(el('family-access-read')).toBeNull();
  });

  it('QA D3451 (r77): a relative’s read through family access says who, how related and which part', () => {
    disclosureResponse = () =>
      of(
        accounting(
          [
            entry({
              id: 'fam-1',
              actor: 'Mariam Ouédraogo',
              actorRole: 'PATIENT',
              eventType: 'PATIENT_ACCESS',
              entityType: 'FamilyAccess',
              description: 'Record read through family access (MEDICATIONS)',
              category: 'FAMILY_ACCESS',
              externalDisclosure: true,
              recipientRelationship: 'PARENT',
              accessedSection: 'MEDICATIONS',
            }),
          ],
          { externalDisclosures: 1, countsByCategory: { FAMILY_ACCESS: 1 } },
        ),
      );
    fixture.detectChanges();

    const row = (fixture.nativeElement as HTMLElement).querySelector('.portal-list-item')!;
    expect(row.classList).toContain('pli-external');
    expect(row.querySelector('.pli-title')?.textContent).toContain('Mariam Ouédraogo');
    expect(row.querySelector('.pli-category')?.textContent).toContain(
      'PORTAL.SHARING.CATEGORY.FAMILY_ACCESS',
    );
    const read = el('family-access-read')!;
    expect(read.textContent).toContain('R70_PRIVACY.FAMILY_SECTION_READ');
    expect(component.familySectionLabelKey(component.accessLog()[0])).toBe(
      'R70_PRIVACY.FAMILY_SECTION_MEDICATIONS',
    );
    // A relative is not shown under an account role, nor with the audit screen's wording.
    expect(row.querySelector('.pli-sub')?.textContent).not.toContain('PATIENT');
    expect(row.textContent).not.toContain('Record read through');
    expect(el('released-to-family')).toBeNull();
  });

  it('QA D3451 (r77): an unknown part of the record is not printed as a code', () => {
    disclosureResponse = () =>
      of(
        accounting([
          entry({ category: 'FAMILY_ACCESS', externalDisclosure: true, accessedSection: 'X_NEW' }),
        ]),
      );
    fixture.detectChanges();

    expect(component.familySectionLabelKey(component.accessLog()[0])).toBeNull();
    expect(el('family-access-read')!.textContent).not.toContain('X_NEW');
  });

  it('shows a failure state instead of telling the patient nobody looked', () => {
    // The service used to catchError into an empty array, so an outage
    // rendered as "Nobody has accessed your records yet" — a false
    // statement about a privacy-critical fact, not a blank screen.
    disclosureResponse = () => throwError(() => new Error('500'));
    fixture.detectChanges();

    expect(component.logFailed()).toBe(true);
    expect(component.accessLog().length).toBe(0);
  });

  it('retries after a failure rather than caching the failure as an answer', () => {
    disclosureResponse = () => throwError(() => new Error('500'));
    fixture.detectChanges();
    expect(component.logFailed()).toBe(true);

    disclosureResponse = () => of(accounting([entry()]));
    component.retryAccessLog();
    fixture.detectChanges();

    expect(component.logFailed()).toBe(false);
    expect(component.accessLog().length).toBe(1);
  });

  it('leads with the counts that matter across the whole history', () => {
    // The counts come from a grouped query over the entire window, not from
    // the loaded page — a patient with one emergency access six months and
    // 400 chart-opens ago must see the 1 without paging to it.
    disclosureResponse = () =>
      of(
        accounting([entry()], {
          countsByCategory: { EMERGENCY_ACCESS: 2, TREATMENT_ACCESS: 400 },
          externalDisclosures: 3,
          totalEvents: 405,
        }),
      );
    fixture.detectChanges();

    expect(component.emergencyCount()).toBe(2);
    expect(component.externalCount()).toBe(3);
    const band = (fixture.nativeElement as HTMLElement).querySelector('.access-summary');
    expect(band).toBeTruthy();
    expect(band?.textContent).toContain('2');
    expect(band?.textContent).toContain('3');
  });

  it('hides the summary band when there is nothing in it', () => {
    // A row of zeroes on every ordinary account trains people to skip past
    // the band on the one visit it matters.
    disclosureResponse = () => of(accounting([entry()]));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.access-summary')).toBeNull();
  });

  it('states what the log covers in every state, including when it is empty', () => {
    // Routine chart reads emit no audit event, so this list contains only
    // disclosures. Without the note, an empty page reads as "nobody looked"
    // and a populated one reads as "this is everyone who looked" — both
    // false, and about a privacy fact the patient cannot verify elsewhere.
    const note = () => (fixture.nativeElement as HTMLElement).querySelector('.access-scope-note');

    disclosureResponse = () => of(accounting([]));
    fixture.detectChanges();
    expect(note()).withContext('empty state').toBeTruthy();

    disclosureResponse = () => of(accounting([entry()]));
    component.retryAccessLog();
    fixture.detectChanges();
    expect(note()).withContext('populated state').toBeTruthy();

    disclosureResponse = () => throwError(() => new Error('500'));
    component.retryAccessLog();
    fixture.detectChanges();
    expect(note()).withContext('failure state').toBeTruthy();
  });

  it('falls back to a neutral label when the backend sends no category', () => {
    expect(component.categoryLabelKey(entry({ category: null }))).toBe(
      'PORTAL.SHARING.CATEGORY.UNKNOWN',
    );
    expect(component.categoryLabelKey(entry({ category: 'INSURANCE' }))).toBe(
      'PORTAL.SHARING.CATEGORY.INSURANCE',
    );
  });

  it('translates the actor role on an access-log row', () => {
    // `entry.actorRole` rendered raw, so a patient in Ouagadougou read
    // DOCTOR beside the name of whoever opened their chart. The server
    // sends one bare token (DisclosureAccountingServiceImpl.toEntry); the
    // row translates it.
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', { PORTAL: { ENUM: { ROLE: { DOCTOR: 'Médecin' } } } }, true);
    disclosureResponse = () => of(accounting([entry()]));

    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Médecin');
    expect(text).not.toContain('DOCTOR');
  });
});
