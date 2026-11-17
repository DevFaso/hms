import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { Error403Component } from './error-403';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * D7: a super-admin who picked an inherited role at sign-in was bounced off
 * every super-admin page with no word on why. The page now names the role
 * they are in and the one to pick instead.
 */
describe('Error403Component', () => {
  let roleContext: RoleContextService;
  let auth: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['logout']);
    TestBed.configureTestingModule({
      imports: [Error403Component, TranslateModule.forRoot()],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    roleContext = TestBed.inject(RoleContextService);
  });

  afterEach(() => roleContext.clearSession());

  function render(): HTMLElement {
    const fixture = TestBed.createComponent(Error403Component);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('tells a super-admin signed in as a lesser role to sign in again as super-admin', () => {
    roleContext.setHeldRoles(['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN']);
    roleContext.activeRole = 'ROLE_HOSPITAL_ADMIN';
    roleContext.noteRefusal(['ROLE_SUPER_ADMIN']);

    const el = render();

    expect(el.querySelector('.role-hint')?.textContent).toContain('ERROR_403.ROLE_HINT');
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
    el.querySelector<HTMLButtonElement>('.relogin-btn')?.click();
    expect(auth.logout).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/login');
  });

  it('shows no hint when no held role would open the page', () => {
    roleContext.setHeldRoles(['ROLE_NURSE']);
    roleContext.activeRole = 'ROLE_NURSE';
    roleContext.noteRefusal(['ROLE_SUPER_ADMIN']);

    const el = render();

    expect(el.querySelector('.role-hint')).toBeNull();
    expect(el.querySelector('h2')?.textContent).toContain('ERROR_403.TITLE');
  });
});
