import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { RegistrationsComponent } from './registrations';

/**
 * RCPT-CLIN: the desk neither reads the discharge note nor writes it, and the server keeps
 * the stored one. A box left on the desk's form would drop what they type without a word.
 */
describe('RegistrationsComponent discharge note', () => {
  function openFormAs(roles: string[]): HTMLElement {
    TestBed.configureTestingModule({
      imports: [RegistrationsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles }),
        },
      ],
    });
    const fixture = TestBed.createComponent(RegistrationsComponent);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .match(() => true)
      .forEach((call) => call.flush([]));
    fixture.componentInstance.showFormModal.set(true);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('offers no discharge note to a receptionist', () => {
    const page = openFormAs(['ROLE_RECEPTIONIST']);
    expect(page.querySelector('#reg-physician')).withContext('the form is open').not.toBeNull();
    expect(page.querySelector('#reg-note')).toBeNull();
  });

  it('keeps it for a hospital administrator', () => {
    expect(openFormAs(['ROLE_HOSPITAL_ADMIN']).querySelector('#reg-note')).not.toBeNull();
  });
});

/** QA D1430: one click deactivated a patient's registration. */
describe('RegistrationsComponent deactivation', () => {
  const row = {
    id: 'r1',
    patientId: 'p1',
    hospitalId: 'h1',
    patientFullName: 'Awa Traoré',
    active: true,
    stayStatus: 'REGISTERED',
  };

  function page() {
    TestBed.configureTestingModule({
      imports: [RegistrationsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h1',
            roles: ['ROLE_RECEPTIONIST'],
          }),
        },
      ],
    });
    const fixture = TestBed.createComponent(RegistrationsComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.match(() => true).forEach((call) => call.flush([]));
    fixture.componentInstance.registrations.set([row] as never);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('the row button only opens the question: nothing is sent', () => {
    const { fixture, http, el } = page();
    (el.querySelector('[data-testid="registration-deactivate"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el.querySelector('[data-testid="reason-dialog"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="registration-deactivate-who"]')?.textContent).toContain(
      'Awa Traoré',
    );
    http.expectNone((r) => r.method === 'PUT');
  });

  it('the reason goes with the PUT; the row shows it once saved', () => {
    const { fixture, http, el } = page();
    fixture.componentInstance.deactivate(row as never, 'Parti vivre ailleurs');
    const put = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/r1'));
    expect(put.request.body.active).toBeFalse();
    expect(put.request.body.deactivationReason).toBe('Parti vivre ailleurs');
    put.flush({ ...row, active: false, deactivationReason: 'Parti vivre ailleurs' });
    fixture.detectChanges();

    expect(
      el.querySelector('[data-testid="registration-deactivation-reason"]')?.textContent,
    ).toContain('Parti vivre ailleurs');
    expect(fixture.componentInstance.deactivating()).toBeNull();
  });

  it('the box in the edit form asks too: refused on the field until there is a reason', () => {
    const { fixture, http, el } = page();
    const c = fixture.componentInstance;
    c.openEdit(row as never);
    fixture.detectChanges();
    expect(el.querySelector('#reg-deactivation-reason')).toBeNull();

    c.form.active = false;
    fixture.detectChanges();
    const box = el.querySelector('#reg-deactivation-reason') as HTMLElement;
    expect(box).not.toBeNull();
    c.submitForm();
    http.expectNone((r) => r.method === 'PUT');
    expect(box.getAttribute('aria-invalid')).toBe('true');

    c.form.deactivationReason = 'Doublon';
    c.submitForm();
    expect(box.getAttribute('aria-invalid')).toBeNull();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect(put.request.body.deactivationReason).toBe('Doublon');
  });
});
