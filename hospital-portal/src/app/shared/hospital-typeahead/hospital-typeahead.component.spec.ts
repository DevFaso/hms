import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';

import { HospitalResponse } from '../../services/hospital.service';
import { HospitalTypeaheadComponent } from './hospital-typeahead.component';

/**
 * Unit tests for the cross-tenant scope chip's typeahead.
 *
 * Spec-points (docs/super-admin-cross-tenant-design.md):
 *   - debounced (300ms) server-side search
 *   - LIMIT 20
 *   - "All hospitals" sentinel emits selectAll
 *   - sub-2-char queries do NOT hit the network
 *
 * NOTE on timing: this codebase's test setup is incompatible with
 * `fakeAsync` (see comment in
 * `admissions/order-set-picker/order-set-picker.component.spec.ts`).
 * We wait the real 320 ms (just past the 300 ms debounce) inside
 * `setTimeout`-based `done` callbacks instead.
 */
describe('HospitalTypeaheadComponent', () => {
  // 300 ms component debounce + small jitter buffer.
  const DEBOUNCE_WAIT_MS = 320;

  let fixture: ComponentFixture<HospitalTypeaheadComponent>;
  let component: HospitalTypeaheadComponent;
  let httpMock: HttpTestingController;

  const sampleHospital: HospitalResponse = {
    id: 'h-1',
    name: 'Memorial Hospital',
    code: 'MEM',
    address: '',
    city: 'Boston',
    state: 'MA',
    zipCode: '',
    country: 'US',
    province: '',
    region: '',
    sector: '',
    poBox: '',
    phoneNumber: '',
    email: '',
    website: '',
    organizationId: '',
    organizationName: '',
    organizationCode: '',
    active: true,
    createdAt: '',
    updatedAt: '',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HospitalTypeaheadComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(HospitalTypeaheadComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    // Each test calls `fixture.detectChanges()` itself after configuring
    // any input signals it needs (e.g. `autoFocus=false` to avoid the
    // ngOnInit setTimeout-focus side-effect).
  });

  afterEach(() => httpMock.verify());

  it('renders the search input and the All-hospitals option by default', () => {
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('[data-testid="hospital-typeahead-input"]')).toBeTruthy();
    expect(root.querySelector('[data-testid="hospital-typeahead-all"]')).toBeTruthy();
  });

  it('hides the All-hospitals sentinel when [hideAllOption] is true', () => {
    fixture.componentRef.setInput('hideAllOption', true);
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="hospital-typeahead-all"]',
      ),
    ).toBeNull();
  });

  it('does NOT issue a request for sub-2-character queries', (done) => {
    fixture.componentRef.setInput('autoFocus', false);
    fixture.detectChanges();
    component['onQueryChange']('a');
    setTimeout(() => {
      // expectNone(matcher) returns void; assert explicitly so Jasmine
      // doesn't flag the spec as having "no expectations".
      const matches = httpMock.match(() => true);
      expect(matches.length).toBe(0);
      done();
    }, DEBOUNCE_WAIT_MS);
  });

  it('debounces keystrokes and issues a single LIMIT-20 search', (done) => {
    fixture.componentRef.setInput('autoFocus', false);
    fixture.detectChanges();
    component['onQueryChange']('m');
    component['onQueryChange']('me');
    component['onQueryChange']('mem');
    component['onQueryChange']('memo');
    setTimeout(() => {
      const req = httpMock.expectOne(
        (r) => r.url === '/super-admin/hospitals/search' && r.params.get('q') === 'memo',
      );
      expect(req.request.params.get('limit')).toBe('20');
      req.flush([sampleHospital]);
      done();
    }, DEBOUNCE_WAIT_MS);
  });

  it('emits selectHospital with the picked match', (done) => {
    fixture.componentRef.setInput('autoFocus', false);
    fixture.detectChanges();
    let emitted: HospitalResponse | undefined;
    component.selectHospital.subscribe((h) => (emitted = h));

    component['onQueryChange']('memo');
    setTimeout(() => {
      httpMock.expectOne('/super-admin/hospitals/search?q=memo&limit=20').flush([sampleHospital]);
      fixture.detectChanges();
      const option: HTMLElement | null = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="hospital-typeahead-option-h-1"]',
      );
      expect(option).toBeTruthy();
      option!.click();
      expect(emitted).toEqual(sampleHospital);
      done();
    }, DEBOUNCE_WAIT_MS);
  });

  it('emits selectAll when the All-hospitals sentinel is clicked', () => {
    fixture.detectChanges();
    const emitted = jasmine.createSpy('selectAll');
    component.selectAll.subscribe(emitted);
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLElement>('[data-testid="hospital-typeahead-all"]')!
      .click();
    expect(emitted).toHaveBeenCalledTimes(1);
  });

  it('shows a NO_MATCHES message when the search returns zero results', (done) => {
    fixture.componentRef.setInput('autoFocus', false);
    fixture.detectChanges();
    component['onQueryChange']('zzz');
    setTimeout(() => {
      httpMock.expectOne('/super-admin/hospitals/search?q=zzz&limit=20').flush([]);
      fixture.detectChanges();
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('HOSPITAL_SCOPE.NO_MATCHES');
      done();
    }, DEBOUNCE_WAIT_MS);
  });

  it('shows a SEARCH_ERROR message on backend failure', (done) => {
    fixture.componentRef.setInput('autoFocus', false);
    fixture.detectChanges();
    component['onQueryChange']('memo');
    setTimeout(() => {
      httpMock
        .expectOne('/super-admin/hospitals/search?q=memo&limit=20')
        .error(new ProgressEvent('error'), { status: 500, statusText: 'fail' });
      fixture.detectChanges();
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('HOSPITAL_SCOPE.SEARCH_ERROR');
      done();
    }, DEBOUNCE_WAIT_MS);
  });
});
