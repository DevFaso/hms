import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { Login } from './login';
import { OidcAuthService } from '../auth/oidc-auth.service';
import { environment } from '../../environments/environment';

describe('Login — KC-2b SSO entry point', () => {
  let component: Login;
  let oidcSpy: jasmine.SpyObj<OidcAuthService>;
  const originalOidcEnabled = environment.oidc.enabled;

  beforeEach(() => {
    oidcSpy = jasmine.createSpyObj<OidcAuthService>(
      'OidcAuthService',
      ['isEnabled', 'isAvailable', 'discoveryFailed', 'login'],
    );
    oidcSpy.isEnabled.and.callFake(() => environment.oidc.enabled);
    // KC-2b (G-8): the SSO button now binds to isAvailable() — i.e.
    // enabled AND discovery succeeded. In these tests there's no
    // discovery hop, so default to mirroring the env flag.
    oidcSpy.isAvailable.and.callFake(() => environment.oidc.enabled);
    oidcSpy.discoveryFailed.and.returnValue(false);

    TestBed.configureTestingModule({
      imports: [Login, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: OidcAuthService, useValue: oidcSpy },
      ],
    });
    const fixture = TestBed.createComponent(Login);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    environment.oidc.enabled = originalOidcEnabled;
  });

  it('oidcLoginEnabled mirrors the environment flag via OidcAuthService', () => {
    environment.oidc.enabled = false;
    expect(component.oidcLoginEnabled).toBeFalse();

    environment.oidc.enabled = true;
    expect(component.oidcLoginEnabled).toBeTrue();
  });

  it('loginWithKeycloak() delegates to OidcAuthService.login() only when enabled', () => {
    environment.oidc.enabled = false;
    component.loginWithKeycloak();
    expect(oidcSpy.login).not.toHaveBeenCalled();

    environment.oidc.enabled = true;
    component.loginWithKeycloak();
    expect(oidcSpy.login).toHaveBeenCalledTimes(1);
  });

  it('loginWithKeycloak() clears any prior error banner before redirecting', () => {
    environment.oidc.enabled = true;
    component.error = 'previous failure';
    component.loginWithKeycloak();
    expect(component.error).toBe('');
  });
});
