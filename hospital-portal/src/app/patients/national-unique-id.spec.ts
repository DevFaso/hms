import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RoleContextService } from '../core/role-context.service';
import { PatientService } from '../services/patient.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { NationalUniqueIdEditorComponent } from './national-unique-id-editor.component';
import { NationalUniqueIdViewComponent } from './national-unique-id-view.component';
import { normalizedNationalUniqueId } from './national-unique-id';
import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';

describe('Optional protected national IU', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [
        PatientPickerComponent,
        NationalUniqueIdEditorComponent,
        NationalUniqueIdViewComponent,
        TranslateModule.forRoot(),
      ],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const roles = TestBed.inject(RoleContextService);
    roles.setRoles(['ROLE_RECEPTIONIST']);
    roles.activeHospitalId = 'hospital-a';
    const translate = TestBed.inject(TranslateService);
    for (const [locale, values] of Object.entries({ en, fr, es }))
      translate.setTranslation(locale, values);
    translate.use('fr');
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('keeps leading zeros, accepts separators, and refuses partial/non-ASCII numbers', () => {
    expect(normalizedNationalUniqueId('0001-2345 6789')).toBe('000123456789');
    expect(normalizedNationalUniqueId('')).toBe('');
    for (const invalid of ['12345678901', '1234567890123', '00012345678A', '١٢٣٤٥٦٧٨٩٠١٢']) {
      expect(normalizedNationalUniqueId(invalid)).toBeNull();
    }
  });

  it('searches by a JSON POST body and never a URL parameter', () => {
    TestBed.inject(PatientService).searchNationalUniqueId('000123456789').subscribe();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/patients/national-unique-id/search',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ nationalUniqueId: '000123456789' });
    expect(request.request.urlWithParams).not.toContain('000123456789');
    request.flush([]);
  });

  it('the exact-IU picker never invokes phone/name GET lookups or a partial IU search', fakeAsync(() => {
    const fixture = TestBed.createComponent(PatientPickerComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance,
      http = TestBed.inject(HttpTestingController);
    c.setNationalUniqueIdMode(true);
    c.onQueryChange('0001-2345 6789');
    tick(220);
    const request = http.expectOne('/patients/national-unique-id/search');
    expect(request.request.method).toBe('POST');
    expect(request.request.body.nationalUniqueId).toBe('000123456789');
    request.flush([]);
    c.onQueryChange('00012345678');
    tick(220);
    http.expectNone(() => true);
    expect(c.suggestions()).toEqual([]);
  }));

  it('is optional and never sends its displayed mask back as an edit', () => {
    const fixture = TestBed.createComponent(NationalUniqueIdEditorComponent);
    fixture.componentRef.setInput('patientId', 'patient-a');
    fixture.componentRef.setInput('masked', '•••• •••• 6789');
    const updated = jasmine.createSpy('updated'),
      ready = jasmine.createSpy('ready');
    fixture.componentInstance.updated.subscribe(updated);
    fixture.componentInstance.ready.subscribe(ready);
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('#nationalUniqueId') as HTMLInputElement;
    expect(input.type).toBe('text');
    expect(input.required).toBeFalse();
    expect(input.value).toBe('');
    expect(updated).toHaveBeenCalledWith({});
    expect(ready).toHaveBeenCalledWith(true);
    expect(fixture.nativeElement.textContent).toContain('Identifiant unique (IU)');
    (
      fixture.nativeElement.querySelector(
        '[data-testid="clear-national-unique-id"]',
      ) as HTMLButtonElement
    ).click();
    expect(updated.calls.mostRecent().args[0].nationalUniqueId).toBe('');
  });

  it('rechecks a server refusal after a concurrent duplicate so saving with a reason stays possible', fakeAsync(() => {
    const fixture = TestBed.createComponent(NationalUniqueIdEditorComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.change('000123456789');
    tick(250);
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/patients/national-unique-id/check')
      .flush({ accessiblePatientIds: [], registeredElsewhere: false });
    fixture.componentRef.setInput('serverError', 'An identifier was registered concurrently');
    fixture.detectChanges();
    tick(250);
    http
      .expectOne('/patients/national-unique-id/check')
      .flush({ accessiblePatientIds: ['newly-visible'], registeredElsewhere: false });
    fixture.detectChanges();
    expect(c.value).toBe('0001 2345 6789');
    expect(c.warning?.accessiblePatientIds).toEqual(['newly-visible']);
    expect(fixture.nativeElement.querySelector('[aria-invalid="true"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain(
      'An identifier was registered concurrently',
    );
  }));

  it('a failed full disclosure never sends a clear or prevents keeping an untouched identifier', () => {
    const fixture = TestBed.createComponent(NationalUniqueIdEditorComponent);
    fixture.componentRef.setInput('patientId', 'patient-a');
    fixture.componentRef.setInput('masked', '•••• •••• 6789');
    const updated = jasmine.createSpy('updated'),
      ready = jasmine.createSpy('ready');
    fixture.componentInstance.updated.subscribe(updated);
    fixture.componentInstance.ready.subscribe(ready);
    fixture.detectChanges();
    fixture.componentInstance.reveal();
    TestBed.inject(HttpTestingController)
      .expectOne('/patients/national-unique-id/patient-a/reveal')
      .flush({}, { status: 500, statusText: 'Synthetic audit refusal' });
    expect(updated.calls.mostRecent().args[0]).toEqual({});
    expect(ready.calls.mostRecent().args).toEqual([true]);
  });

  for (const previousEdit of ['invalid', 'confirmed duplicate']) {
    it(`showing the stored value discards a ${previousEdit} edit and restores unchanged saving`, fakeAsync(() => {
      const fixture = TestBed.createComponent(NationalUniqueIdEditorComponent);
      fixture.componentRef.setInput('patientId', 'patient-a');
      const updated = jasmine.createSpy('updated'),
        ready = jasmine.createSpy('ready');
      const c = fixture.componentInstance,
        http = TestBed.inject(HttpTestingController);
      c.updated.subscribe(updated);
      c.ready.subscribe(ready);
      fixture.detectChanges();
      if (previousEdit === 'invalid') {
        c.change('123');
        expect(ready.calls.mostRecent().args).toEqual([false]);
      } else {
        c.change('111122223333');
        tick(250);
        http.expectOne('/patients/national-unique-id/check').flush({
          accessiblePatientIds: ['duplicate-patient'],
          registeredElsewhere: false,
        });
        c.override = true;
        c.reason = 'Checked with the patient';
        c.emit();
        expect(updated.calls.mostRecent().args[0].nationalUniqueId).toBe('111122223333');
      }
      c.reveal();
      http
        .expectOne('/patients/national-unique-id/patient-a/reveal')
        .flush({ nationalUniqueId: '0001 2345 6789' });
      expect(c.value).toBe('0001 2345 6789');
      expect(c.errorKey).toBe('');
      expect(c.warning).toBeNull();
      expect(c.reason).toBe('');
      expect(c.override).toBeFalse();
      expect(updated.calls.mostRecent().args[0]).toEqual({});
      expect(ready.calls.mostRecent().args).toEqual([true]);
      http.expectNone('/patients/national-unique-id/check');
    }));
  }

  it('links only accessible duplicates and requires explicit override plus a reason', fakeAsync(() => {
    const fixture = TestBed.createComponent(NationalUniqueIdEditorComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance,
      ready = jasmine.createSpy('ready'),
      updated = jasmine.createSpy('updated');
    c.ready.subscribe(ready);
    c.updated.subscribe(updated);
    c.change('0001 2345 6789');
    tick(250);
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/patients/national-unique-id/check',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.urlWithParams).not.toContain('0001');
    expect(request.request.body.nationalUniqueId).toBe('000123456789');
    request.flush({ accessiblePatientIds: ['patient-visible'], registeredElsewhere: false });
    fixture.detectChanges();
    expect(
      (fixture.nativeElement.querySelector('a') as HTMLAnchorElement).getAttribute('href'),
    ).toBe('/patients/patient-visible');
    expect(ready).toHaveBeenCalledWith(false);
    c.override = true;
    c.emit();
    expect(ready.calls.mostRecent().args).toEqual([false]);
    c.reason = 'Checked with the patient';
    c.emit();
    expect(ready.calls.mostRecent().args).toEqual([true]);
    expect(updated.calls.mostRecent().args[0]).toEqual({
      nationalUniqueId: '000123456789',
      allowNationalUniqueIdDuplicate: true,
      nationalUniqueIdDuplicateReason: 'Checked with the patient',
    });
  }));

  it('shows an out-of-scope warning with no link and ignores a stale response after a scope change', fakeAsync(() => {
    const fixture = TestBed.createComponent(NationalUniqueIdEditorComponent);
    fixture.componentRef.setInput('scope', 'hospital-a');
    fixture.detectChanges();
    fixture.componentInstance.change('000123456789');
    tick(250);
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/patients/national-unique-id/check')
      .flush({ accessiblePatientIds: [], registeredElsewhere: true });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('enregistré ailleurs');
    fixture.componentInstance.change('999123456789');
    tick(250);
    const stale = http.expectOne('/patients/national-unique-id/check');
    fixture.componentRef.setInput('scope', 'hospital-b');
    fixture.detectChanges();
    stale.flush({ accessiblePatientIds: ['foreign-now'], registeredElsewhere: false });
    fixture.detectChanges();
    expect(fixture.componentInstance.value).toBe('');
    expect(fixture.componentInstance.warning).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('foreign-now');
  }));

  it('requires an audited explicit reveal and clears full data when identity access is lost', () => {
    const fixture = TestBed.createComponent(NationalUniqueIdViewComponent);
    fixture.componentRef.setInput('patientId', 'patient-a');
    fixture.componentRef.setInput('masked', '•••• •••• 6789');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('0001 2345');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/patients/national-unique-id/patient-a/reveal',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({});
    request.flush({ nationalUniqueId: '0001 2345 6789' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('0001 2345 6789');
    TestBed.inject(RoleContextService).setRoles(['ROLE_PHARMACIST']);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('0001 2345 6789');
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });

  // QA D2030: inside the patient header the revealed number never appeared and
  // the button stayed disabled, because the view defaulted to OnPush and set
  // plain fields in the HTTP callback. Rendered through a host, as on the page.
  it('shows the revealed number and re-enables the header when hosted in a page', () => {
    @Component({
      selector: 'app-iu-host',
      changeDetection: ChangeDetectionStrategy.Eager,
      imports: [NationalUniqueIdViewComponent],
      template: `<app-national-unique-id-view patientId="patient-a" masked="•••• •••• 6789" />`,
    })
    class HostComponent {}
    TestBed.inject(RoleContextService).setRoles(['ROLE_RECEPTIONIST']);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const view = fixture.nativeElement as HTMLElement;
    (view.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/patients/national-unique-id/patient-a/reveal')
      .flush({ nationalUniqueId: '0001 2345 6789' });
    fixture.detectChanges();
    expect(view.textContent).toContain('0001 2345 6789');
    const label = view.querySelector('.iu-label');
    const value = view.querySelector('.iu-value');
    expect(label?.textContent?.trim()).toBeTruthy();
    expect(value?.textContent?.trim()).toBe('0001 2345 6789');
    const hide = view.querySelector('button') as HTMLButtonElement;
    expect(hide.disabled).toBe(false);
  });

  for (const locale of ['fr', 'en', 'es']) {
    it(`renders actual ${locale} labels without raw keys`, () => {
      TestBed.inject(TranslateService).use(locale);
      const fixture = TestBed.createComponent(NationalUniqueIdEditorComponent);
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).not.toContain('NATIONAL_IU.');
      expect(
        (fixture.nativeElement.querySelector('label') as HTMLLabelElement).textContent?.trim(),
      ).toBeTruthy();
    });
  }
});
