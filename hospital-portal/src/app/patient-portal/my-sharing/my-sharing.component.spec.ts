import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MySharingComponent } from './my-sharing.component';
import {
  AccessLogEntry,
  DisclosureAccounting,
  PatientPortalService,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

/**
 * The access-log half of these specs exists because the previous suite
 * stubbed `getMyAccessLog: () => of([])` and never rendered a row. The
 * component's bindings (`accessedBy`, `accessType`, `accessedAt`) matched
 * nothing the backend sends, so every row in "Who viewed my records" was
 * blank — and a test that only ever exercises the empty state cannot see
 * that. Tier 2 item 39.
 */
describe('MySharingComponent', () => {
  let component: MySharingComponent;
  let fixture: ComponentFixture<MySharingComponent>;

  const entry = (over: Partial<AccessLogEntry> = {}): AccessLogEntry => ({
    id: 'a1',
    actor: 'Dr Alice Traore',
    actorRole: 'Doctor',
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

  let disclosureResponse: () => ReturnType<PatientPortalService['getMyDisclosures']>;

  const mockPortalService = {
    getMyConsents: () => of([]),
    getMyDisclosures: () => disclosureResponse(),
    revokeConsent: () => of({}),
    grantConsent: () => of({}),
    getMyProfile: () => of({ hospitalId: 'test-hospital-id' }),
  };

  const mockToast = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    disclosureResponse = () => of(accounting([]));

    await TestBed.configureTestingModule({
      imports: [MySharingComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: mockPortalService },
        { provide: ToastService, useValue: mockToast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MySharingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should default to consents tab', () => {
    expect(component.activeTab()).toBe('consents');
  });

  it('should switch to access log tab', () => {
    component.switchToAccessLog();
    expect(component.activeTab()).toBe('access-log');
  });

  it('should open and close share form', () => {
    component.openShareForm();
    expect(component.showShareForm()).toBe(true);
    component.cancelShare();
    expect(component.showShareForm()).toBe(false);
  });

  it('renders the name of whoever accessed the record', () => {
    // THE REGRESSION. With the old bindings this row rendered as an empty
    // title and a bare " · " separator, and the only way to see it was to
    // put a row on the page — which no test did.
    disclosureResponse = () => of(accounting([entry()]));
    component.switchToAccessLog();
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
    component.switchToAccessLog();
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
    component.switchToAccessLog();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.pli-external').length).toBe(1);
  });

  it('shows a failure state instead of telling the patient nobody looked', () => {
    // The service used to catchError into an empty array, so an outage
    // rendered as "Nobody has accessed your records yet" — a false
    // statement about a privacy-critical fact, not a blank screen.
    disclosureResponse = () => throwError(() => new Error('500'));
    component.switchToAccessLog();
    fixture.detectChanges();

    expect(component.logFailed()).toBe(true);
    expect(component.accessLog().length).toBe(0);
  });

  it('retries after a failure rather than caching the failure as an answer', () => {
    disclosureResponse = () => throwError(() => new Error('500'));
    component.switchToAccessLog();
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
    component.switchToAccessLog();
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
    component.switchToAccessLog();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.access-summary')).toBeNull();
  });

  it('falls back to a neutral label when the backend sends no category', () => {
    expect(component.categoryLabelKey(entry({ category: null }))).toBe(
      'PORTAL.SHARING.CATEGORY.UNKNOWN',
    );
    expect(component.categoryLabelKey(entry({ category: 'INSURANCE' }))).toBe(
      'PORTAL.SHARING.CATEGORY.INSURANCE',
    );
  });
});
