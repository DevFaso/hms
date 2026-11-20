import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';

import { LabCriticalPendingComponent } from './lab-critical-pending';
import { LabResultResponse } from '../../services/lab.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';

/**
 * r65 D2183: the hospital admin paged from escalation round 2 sees what is
 * paging, read-only, and is told to get a clinician to read it back.
 */
describe('LabCriticalPendingComponent', () => {
  let fixture: ComponentFixture<LabCriticalPendingComponent>;
  let httpMock: HttpTestingController;

  function row(overrides: Partial<LabResultResponse> = {}): LabResultResponse {
    return {
      id: 'r-1',
      patientFullName: 'Awa TestDiallo',
      labTestName: 'NFS',
      labOrderCode: 'LAB-000003',
      resultValue: 'HB 5.5 g/dL; HCT 42 %',
      resultUnit: null,
      orderedByName: 'Test Médecin 5',
      criticalNotifiedAt: '2026-11-19T01:08:00',
      criticalEscalationLevel: 3,
      ...overrides,
    } as LabResultResponse;
  }

  function setup(hospitalId: string | null): void {
    TestBed.configureTestingModule({
      imports: [LabCriticalPendingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId,
            roles: ['ROLE_HOSPITAL_ADMIN'],
          }),
        },
      ],
    });
    fixture = TestBed.createComponent(LabCriticalPendingComponent);
    httpMock = TestBed.inject(HttpTestingController);
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => httpMock.verify());

  it('lists the hospital critical results still paging, longest first, with no acknowledge control', () => {
    setup('h-1');
    fixture.detectChanges();
    httpMock
      .expectOne((r) => r.url === '/lab-results/hospital/h-1/critical/unacknowledged')
      .flush([
        row({ id: 'late', criticalNotifiedAt: '2026-11-19T05:00:00' }),
        row({ id: 'early' }),
      ]);
    fixture.detectChanges();

    const cards = Array.from(host().querySelectorAll('[data-testid^="critical-pending-row-"]'));
    expect(cards.map((c) => c.getAttribute('data-testid'))).toEqual([
      'critical-pending-row-early',
      'critical-pending-row-late',
    ]);
    expect(cards[0].textContent).toContain('Test Médecin 5');
    expect(host().querySelector('[data-testid="critical-pending-what-to-do"]')).not.toBeNull();
    expect(host().querySelector('button[data-testid*="acknowledge"]')).toBeNull();
  });

  it('says it could not load rather than drawing an empty list', () => {
    setup('h-1');
    fixture.detectChanges();
    httpMock
      .expectOne((r) => r.url === '/lab-results/hospital/h-1/critical/unacknowledged')
      .flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="critical-pending-error"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="critical-pending-empty"]')).toBeNull();
  });

  it('asks for a hospital, without a request, when there is no scope', () => {
    setup(null);
    fixture.detectChanges();
    httpMock.expectNone(() => true);
    expect(host().querySelector('[data-testid="critical-pending-no-scope"]')).not.toBeNull();
  });

  it('fits a phone screen without a sideways scroll', () => {
    setup('h-1');
    fixture.detectChanges();
    httpMock
      .expectOne((r) => r.url === '/lab-results/hospital/h-1/critical/unacknowledged')
      .flush([row()]);
    fixture.detectChanges();
    const list = host().querySelector<HTMLElement>('.critical-pending-container')!;
    list.style.width = '390px';
    expect(list.scrollWidth).toBeLessThanOrEqual(list.clientWidth + 1);
  });
});
