import { Location } from '@angular/common';
import { SpyLocation } from '@angular/common/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { ResetPasswordComponent } from './reset-password';

describe('ResetPasswordComponent — a refused link is never shown as a reset (QA D2051/D2052)', () => {
  let httpMock: HttpTestingController;

  function open(
    query: Record<string, string>,
    fragment: string | null = null,
  ): ComponentFixture<ResetPasswordComponent> {
    TestBed.configureTestingModule({
      imports: [ResetPasswordComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: Location, useClass: SpyLocation },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { fragment, queryParamMap: convertToParamMap(query) } },
        },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ResetPasswordComponent);
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<ResetPasswordComponent>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  afterEach(() => httpMock.verify());

  it('a 204 shows the translated success', () => {
    const fixture = open({ token: 'live' });
    const c = fixture.componentInstance;
    c.newPassword = 'Valid-Pass-2026';
    c.confirmPassword = 'Valid-Pass-2026';
    c.submit();
    const req = httpMock.expectOne('/auth/password/confirm');
    expect(req.request.body).toEqual({ token: 'live', newPassword: 'Valid-Pass-2026' });
    req.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();

    expect(c.success).toBe('RESET.SUCCESS_MSG');
    expect(text(fixture)).toContain('RESET.SUCCESS_TITLE');
  });

  it('a 400 for a bad, expired or used link shows the server message, not the success', () => {
    const fixture = open({ token: 'spent' });
    const c = fixture.componentInstance;
    c.newPassword = 'Valid-Pass-2026';
    c.confirmPassword = 'Valid-Pass-2026';
    c.submit();
    httpMock.expectOne('/auth/password/confirm').flush(
      {
        message: 'Ce lien est invalide, a expiré ou a déjà été utilisé. Demandez-en un nouveau.',
      },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    expect(c.success).toBe('');
    expect(text(fixture)).not.toContain('RESET.SUCCESS_TITLE');
    expect(text(fixture)).toContain('Demandez-en un nouveau');
  });

  it('a 400 with no message falls back to the translated link error', () => {
    const fixture = open({ token: 'x' });
    const c = fixture.componentInstance;
    c.newPassword = 'Valid-Pass-2026';
    c.confirmPassword = 'Valid-Pass-2026';
    c.submit();
    httpMock
      .expectOne('/auth/password/confirm')
      .flush(null, { status: 400, statusText: 'Bad Request' });

    expect(c.error).toBe('RESET.ERR_LINK_INVALID');
    expect(c.success).toBe('');
  });

  for (const status of [0, 429, 500, 502, 503, 504]) {
    it(`a ${status} leaves the outcome unknown and permits retry`, () => {
      const fixture = open({ token: 'live' });
      const c = fixture.componentInstance;
      c.newPassword = 'Valid-Pass-2026';
      c.confirmPassword = c.newPassword;
      c.submit();
      const req = httpMock.expectOne('/auth/password/confirm');
      if (status === 0) {
        req.error(new ProgressEvent('error'));
      } else {
        req.flush({ message: 'Gateway unavailable' }, { status, statusText: 'Unavailable' });
      }
      fixture.detectChanges();

      expect(c.error).toBe('RESET.ERR_UNAVAILABLE');
      expect(text(fixture)).toContain('RESET.ERR_UNAVAILABLE');
      expect(text(fixture)).not.toContain('RESET.ERR_LINK_INVALID');
      expect(c.success).toBe('');
      expect(c.loading).toBeFalse();
      expect(fixture.nativeElement.querySelector('form')).not.toBeNull();

      c.submit();
      expect(c.error).toBe('');
      httpMock
        .expectOne('/auth/password/confirm')
        .flush(null, { status: 204, statusText: 'No Content' });
      expect(c.success).toBe('RESET.SUCCESS_MSG');
    });
  }

  it('validation messages are translation keys, not English literals', () => {
    const fixture = open({ token: 'x' });
    const c = fixture.componentInstance;

    c.submit();
    expect(c.error).toBe('RESET.ERR_REQUIRED');
    const host = fixture.nativeElement as HTMLElement;
    const password = host.querySelector<HTMLInputElement>('#newPassword')!;
    const confirm = host.querySelector<HTMLInputElement>('#confirmPassword')!;
    expect(password.getAttribute('aria-invalid')).toBe('true');
    expect(confirm.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(password);
    expect(host.querySelector('#newPassword-invalid-msg')?.textContent).toBe(c.error);

    c.newPassword = 'short';
    c.confirmPassword = 'short';
    c.submit();
    expect(c.error).toBe('RESET.ERR_MIN_LENGTH');
    expect(password.getAttribute('aria-invalid')).toBe('true');
    expect(confirm.hasAttribute('aria-invalid')).toBeFalse();
    expect(document.activeElement).toBe(password);

    c.newPassword = 'Valid-Pass-2026';
    c.confirmPassword = 'Other-Pass-2026';
    c.submit();
    expect(c.error).toBe('RESET.ERR_MISMATCH');
    expect(password.hasAttribute('aria-invalid')).toBeFalse();
    expect(confirm.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(confirm);

    c.confirmPassword = c.newPassword;
    c.submit();
    expect(host.querySelector('[aria-invalid]')).toBeNull();
    expect(host.querySelector('.invalid-field-msg')).toBeNull();
    httpMock
      .expectOne('/auth/password/confirm')
      .flush(null, { status: 204, statusText: 'No Content' });
    httpMock.expectNone('/auth/password/confirm');
  });

  it('the token of the link fragment is posted in the body and leaves the address bar', () => {
    const fixture = open({}, 'token=frag-1');
    expect((TestBed.inject(Location) as SpyLocation).path()).toBe('/reset-password');
    const c = fixture.componentInstance;
    c.newPassword = 'Valid-Pass-2026';
    c.confirmPassword = 'Valid-Pass-2026';
    c.submit();
    const req = httpMock.expectOne(
      (r) => r.url === '/auth/password/confirm' && r.urlWithParams === '/auth/password/confirm',
    );
    expect(req.request.body).toEqual({ token: 'frag-1', newPassword: 'Valid-Pass-2026' });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('a link with no token is refused up front', () => {
    const fixture = open({});
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(text(fixture)).toContain('RESET.INVALID_MSG');
    httpMock.expectNone('/auth/password/confirm');
    expect(text(fixture)).toContain('RESET.INVALID_TITLE');
  });
});
