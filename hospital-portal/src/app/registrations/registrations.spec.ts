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
