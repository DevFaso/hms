import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { RecordRightsComponent } from './record-rights.component';
import { RoleContextService } from '../../core/role-context.service';
import { expectEmptySubmitRefusal, expectFieldsMarked } from '../../testing/form-refusal-behaviour';

describe('RecordRightsComponent own-account requests', () => {
  let http: HttpTestingController;
  let allowed: boolean;
  beforeEach(() => {
    allowed = true;
    TestBed.configureTestingModule({
      imports: [RecordRightsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: RoleContextService, useValue: { hasAnyActiveRole: () => allowed } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function create() {
    const fixture = TestBed.createComponent(RecordRightsComponent);
    fixture.detectChanges();
    if (allowed) http.expectOne('/me/patient/roi-requests').flush([]);
    return fixture;
  }
  it('refuses blank fields and submits a canonical full-copy request without an identity selector', () => {
    const fixture = create();
    const component = fixture.componentInstance;
    component.submit();
    http.expectNone('/me/patient/roi-requests');
    component.hospitalId = 'hospital-a';
    component.purpose = ' My own copy ';
    component.submit();
    component.submit();
    const req = http.expectOne('/me/patient/roi-requests');
    expect(req.request.body).toEqual({
      hospitalId: 'hospital-a',
      requestKind: 'COPY',
      purpose: 'My own copy',
      scopeDescription: 'FULL_RECORD',
    });
    req.flush({ id: 'request-a', status: 'PENDING', requestedOn: '2026-11-23' });
    expect(component.requests().length).toBe(1);
    expect(component.sent()).toBeTrue();
    expect(component.purpose).toBe('');
    expect(component.saving()).toBeFalse();
  });
  it('keeps every request control inside a narrow form even with a long hospital name', () => {
    const fixture = create();
    fixture.componentRef.setInput('hospitals', [
      {
        hospitalId: 'hospital-a',
        hospitalName:
          'cdx Centre hospitalier régional de Ouagadougou — consultation et suivi des dossiers',
      },
    ]);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    host.style.width = '280px';
    host.style.maxWidth = '280px';
    host.style.display = 'block';
    const form = host.querySelector('form')!.getBoundingClientRect();
    const outer = host.getBoundingClientRect();
    expect(form.width).toBeGreaterThan(0);
    expect(form.right).toBeLessThanOrEqual(outer.right + 1);
    for (const control of host.querySelectorAll('select, textarea, button[type="submit"]')) {
      const box = control.getBoundingClientRect();
      expect(box.left)
        .withContext(control.id || control.tagName)
        .toBeGreaterThanOrEqual(form.left - 1);
      expect(box.right)
        .withContext(control.id || control.tagName)
        .toBeLessThanOrEqual(form.right + 1);
    }
  });

  it('requires a correction description and preserves it when the server refuses', () => {
    const component = create().componentInstance;
    component.hospitalId = 'hospital-a';
    component.purpose = 'Correction';
    component.kind = 'CORRECTION';
    component.submit();
    http.expectNone('/me/patient/roi-requests');
    component.description = 'Address is outdated';
    component.submit();
    const req = http.expectOne('/me/patient/roi-requests');
    expect(req.request.body.scopeDescription).toBe('Address is outdated');
    req.flush({}, { status: 503, statusText: 'Unavailable' });
    expect(component.description).toBe('Address is outdated');
    expect(component.sent()).toBeFalse();
    expect(component.error()).not.toBe('');
    expect(component.saving()).toBeFalse();
  });
  it('shows failed history as unavailable and retries without fabricating an empty success', () => {
    const fixture = TestBed.createComponent(RecordRightsComponent);
    fixture.detectChanges();
    http
      .expectOne('/me/patient/roi-requests')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    expect(fixture.componentInstance.loadFailed()).toBeTrue();
    fixture.componentInstance.load();
    http.expectOne('/me/patient/roi-requests').flush([]);
    expect(fixture.componentInstance.loadFailed()).toBeFalse();
  });
  it('never loads or submits on a staff account', () => {
    allowed = false;
    const fixture = create();
    fixture.componentInstance.load();
    fixture.componentInstance.submit();
    http.expectNone('/me/patient/roi-requests');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });

  it('marks the required fields at an empty submit without filing a request', () => {
    const fixture = create();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="record-rights-submit"]',
    });
    expect(marked).toContain('rights-hospital');
    expect(marked).toContain('rights-purpose');
    http.expectNone('/me/patient/roi-requests');
  });

  it('marks server field refusals beside the correction fields', () => {
    const fixture = create(),
      component = fixture.componentInstance;
    component.kind = 'CORRECTION';
    component.hospitalId = 'hospital-a';
    component.purpose = 'Correction';
    component.description = 'cdx description';
    fixture.detectChanges();
    component.submit();
    http.expectOne('/me/patient/roi-requests').flush(
      {
        fieldErrors: {
          purpose: 'cdx Purpose refused',
          scopeDescription: 'cdx Correction refused',
        },
      },
      { status: 400, statusText: 'Invalid request' },
    );
    fixture.detectChanges();
    expectFieldsMarked(fixture, {
      'rights-purpose': 'cdx Purpose refused',
      'rights-description': 'cdx Correction refused',
    });
    expect(component.description).toBe('cdx description');
  });

  it('QA D4270: an approved copy is downloaded from the request, and an expired or declined one is not offered', () => {
    const fixture = TestBed.createComponent(RecordRightsComponent);
    fixture.detectChanges();
    const row = (id: string, more: object) => ({
      id,
      patientId: 'p-1',
      requesterType: 'PATIENT',
      requestKind: 'COPY',
      status: 'FULFILLED',
      requestedOn: '2026-11-27',
      scopeDescription: 'FULL_RECORD',
      ...more,
    });
    http
      .expectOne('/me/patient/roi-requests')
      .flush([
        row('ready', { copyDownloadable: true, copyAvailableUntil: '2026-12-27' }),
        row('expired', { copyDownloadable: false, copyAvailableUntil: '2026-10-01' }),
        row('declined', { status: 'DENIED' }),
      ]);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="record-copy-download-expired"]')).toBeNull();
    expect(host.querySelector('[data-testid="record-copy-download-declined"]')).toBeNull();
    expect(host.querySelectorAll('[data-testid="record-copy-expired"]').length).toBe(1);
    const button = host.querySelector<HTMLButtonElement>(
      '[data-testid="record-copy-download-ready"]',
    );
    expect(button).not.toBeNull();

    const saved = spyOn(URL, 'createObjectURL').and.returnValue('blob:copy');
    button?.click();
    const download = http.expectOne('/me/patient/roi-requests/ready/record.pdf');
    expect(download.request.method).toBe('GET');
    expect(download.request.responseType).toBe('blob');
    download.flush(new Blob(['%PDF-1.4'], { type: 'application/pdf' }));
    expect(saved).toHaveBeenCalled();
    expect(fixture.componentInstance.downloadingId()).toBeNull();
  });

  it('QA D4270: a refused download says the reason the server gave', async () => {
    const fixture = TestBed.createComponent(RecordRightsComponent);
    fixture.detectChanges();
    http.expectOne('/me/patient/roi-requests').flush([]);
    fixture.componentInstance.download({
      id: 'late',
      patientId: 'p-1',
      requesterType: 'PATIENT',
      status: 'FULFILLED',
      requestedOn: '2026-11-27',
      copyDownloadable: true,
    });
    http
      .expectOne('/me/patient/roi-requests/late/record.pdf')
      .flush(new Blob([JSON.stringify({ message: 'Le délai est dépassé.' })]), {
        status: 400,
        statusText: 'Bad Request',
      });
    // The refusal arrives as a Blob: reading it is asynchronous.
    for (let i = 0; i < 50 && !fixture.componentInstance.error(); i++) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    expect(fixture.componentInstance.error()).toBe('Le délai est dépassé.');
  });
});
