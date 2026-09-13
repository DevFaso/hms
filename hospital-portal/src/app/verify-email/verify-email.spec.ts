import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { VerifyEmailComponent } from './verify-email';

describe('VerifyEmailComponent — the e-mail activation link lands somewhere', () => {
  let httpMock: HttpTestingController;

  function open(query: Record<string, string>): ComponentFixture<VerifyEmailComponent> {
    TestBed.configureTestingModule({
      imports: [VerifyEmailComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
        },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(VerifyEmailComponent);
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<VerifyEmailComponent>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  afterEach(() => httpMock.verify());

  it('activates through GET /auth/verify-email and offers to sign in', () => {
    const fixture = open({ email: 'a@b.c', token: 't1' });
    const req = httpMock.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/auth/verify-email' &&
        r.params.get('email') === 'a@b.c' &&
        r.params.get('token') === 't1',
    );
    req.flush({ message: 'Email verified successfully.', success: true });
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('verified');
    expect(text(fixture)).toContain('VERIFY.SUCCESS_TITLE');
  });

  it('a refused link shows the failure and a resend form prefilled with the address', () => {
    const fixture = open({ email: 'a@b.c', token: 'stale' });
    httpMock
      .expectOne((r) => r.url === '/auth/verify-email')
      .flush(
        { message: 'Email verification failed.', success: false },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('failed');
    expect(text(fixture)).toContain('VERIFY.FAILED_TITLE');
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '#verify-email',
    );
    expect(input).toBeTruthy();
    expect(fixture.componentInstance.resendEmail).toBe('a@b.c');
  });

  it('a link with no token asks nothing of the backend and explains what is missing', () => {
    const fixture = open({ email: 'a@b.c' });
    httpMock.expectNone((r) => r.url === '/auth/verify-email');
    expect(fixture.componentInstance.state()).toBe('missing');
    expect(text(fixture)).toContain('VERIFY.MISSING_TITLE');
  });

  it('resend posts the address and answers the same way whether or not it exists', () => {
    const fixture = open({});
    const component = fixture.componentInstance;
    component.resendEmail = 'a@b.c';
    component.resend();
    const req = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        r.url === '/auth/resend-verification' &&
        r.params.get('email') === 'a@b.c',
    );
    req.flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(component.resendSent()).toBe('VERIFY.RESENT');
    expect(text(fixture)).toContain('VERIFY.RESENT');
    expect((fixture.nativeElement as HTMLElement).querySelector('#verify-email')).toBeNull();
  });

  it('resend with an empty address is refused locally', () => {
    const fixture = open({});
    const component = fixture.componentInstance;
    component.resendEmail = '   ';
    component.resend();
    httpMock.expectNone((r) => r.url === '/auth/resend-verification');
    expect(component.resendError()).toBe('LOGIN.ENTER_EMAIL');
  });
});
