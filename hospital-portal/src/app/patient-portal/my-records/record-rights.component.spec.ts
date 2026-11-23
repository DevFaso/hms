import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { RecordRightsComponent } from './record-rights.component';
import { RoleContextService } from '../../core/role-context.service';

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
});
