import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { ResetPasswordComponent } from './reset-password';

describe('ResetPasswordComponent — a refused link is never shown as a reset (QA D2051/D2052)', () => {
  let httpMock: HttpTestingController;

  function open(query: Record<string, string>): ComponentFixture<ResetPasswordComponent> {
    TestBed.configureTestingModule({
      imports: [ResetPasswordComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
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
    httpMock
      .expectOne('/auth/password/confirm')
      .flush(
        { message: 'Ce lien est invalide, a expiré ou a déjà été utilisé. Demandez-en un nouveau.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    expect(c.success).toBe('');
    expect(text(fixture)).not.toContain('RESET.SUCCESS_TITLE');
    expect(text(fixture)).toContain('Demandez-en un nouveau');
  });

  it('an answer with no message falls back to the translated link error', () => {
    const fixture = open({ token: 'x' });
    const c = fixture.componentInstance;
    c.newPassword = 'Valid-Pass-2026';
    c.confirmPassword = 'Valid-Pass-2026';
    c.submit();
    httpMock.expectOne('/auth/password/confirm').flush(null, { status: 502, statusText: 'Bad Gateway' });

    expect(c.error).toBe('RESET.ERR_LINK_INVALID');
    expect(c.success).toBe('');
  });

  it('validation messages are translation keys, not English literals', () => {
    const fixture = open({ token: 'x' });
    const c = fixture.componentInstance;

    c.submit();
    expect(c.error).toBe('RESET.ERR_REQUIRED');

    c.newPassword = 'short';
    c.confirmPassword = 'short';
    c.submit();
    expect(c.error).toBe('RESET.ERR_MIN_LENGTH');

    c.newPassword = 'Valid-Pass-2026';
    c.confirmPassword = 'Other-Pass-2026';
    c.submit();
    expect(c.error).toBe('RESET.ERR_MISMATCH');
    httpMock.expectNone('/auth/password/confirm');
  });

  it('a link with no token is refused up front', () => {
    const fixture = open({});
    expect(fixture.componentInstance.error).toBe('RESET.ERR_TOKEN_MISSING');
    expect(text(fixture)).toContain('RESET.INVALID_TITLE');
  });
});
