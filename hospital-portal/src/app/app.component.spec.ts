import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { AppComponent } from './app.component';
import { AuthService } from './auth/auth.service';
import { RoleContextService } from './core/role-context.service';
import { SessionScopeService } from './core/session-scope.service';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it(`should have 'hospital-portal' as title`, () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app.title).toEqual('hospital-portal');
  });
});

/**
 * E9 #55b: on every bootstrap the roles come from the token synchronously
 * (route guards run at once) and the hospital scope from the live session,
 * with the stored profile as the fallback. The token's hospital claims are
 * never read.
 */
describe('AppComponent — session scope on bootstrap', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let sessionScope: jasmine.SpyObj<SessionScopeService>;
  let roleContext: RoleContextService;

  beforeEach(async () => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['getToken', 'isExpired', 'getRoles']);
    sessionScope = jasmine.createSpyObj<SessionScopeService>('SessionScopeService', [
      'applyStoredProfile',
      'hydrate',
    ]);
    sessionScope.hydrate.and.returnValue(of(null));
    await TestBed.configureTestingModule({
      imports: [AppComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        RoleContextService,
        { provide: AuthService, useValue: auth },
        { provide: SessionScopeService, useValue: sessionScope },
      ],
    }).compileComponents();
    roleContext = TestBed.inject(RoleContextService);
  });

  it('seeds roles from the token, applies the stored profile, then hydrates from the session', () => {
    auth.getToken.and.returnValue('a.b.c');
    auth.isExpired.and.returnValue(false);
    auth.getRoles.and.returnValue(['ROLE_NURSE', 'ROLE_PATIENT']);

    TestBed.createComponent(AppComponent).componentInstance.ngOnInit();

    expect(roleContext.activeRoles).toEqual(['ROLE_NURSE', 'ROLE_PATIENT']);
    expect(sessionScope.applyStoredProfile).toHaveBeenCalledTimes(1);
    expect(sessionScope.hydrate).toHaveBeenCalledTimes(1);
  });

  it('does nothing without a live token', () => {
    auth.getToken.and.returnValue(null);

    TestBed.createComponent(AppComponent).componentInstance.ngOnInit();

    expect(sessionScope.applyStoredProfile).not.toHaveBeenCalled();
    expect(sessionScope.hydrate).not.toHaveBeenCalled();
  });

  it('does nothing with an expired token', () => {
    auth.getToken.and.returnValue('a.b.c');
    auth.isExpired.and.returnValue(true);

    TestBed.createComponent(AppComponent).componentInstance.ngOnInit();

    expect(sessionScope.hydrate).not.toHaveBeenCalled();
  });
});
