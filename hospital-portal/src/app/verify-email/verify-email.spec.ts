import { Location } from '@angular/common';
import { SpyLocation } from '@angular/common/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { VerifyEmailComponent } from './verify-email';

describe('VerifyEmailComponent — the e-mail activation link lands somewhere', () => {
  let httpMock: HttpTestingController;

  function open(
    query: Record<string, string>,
    fragment: string | null = null,
  ): ComponentFixture<VerifyEmailComponent> {
    TestBed.configureTestingModule({
      imports: [VerifyEmailComponent, TranslateModule.forRoot()],
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
    const fixture = TestBed.createComponent(VerifyEmailComponent);
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<VerifyEmailComponent>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  afterEach(() => httpMock.verify());

  it('exchanges the token of the link fragment by POST: no address, nothing in a request URL', () => {
    const fixture = open({}, 'token=t-frag');
    const req = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        r.url === '/auth/verify-email' &&
        r.params.keys().length === 0 &&
        r.urlWithParams === '/auth/verify-email',
    );
    expect(req.request.body).toEqual({ token: 't-frag' });
    // The fragment leaves the address bar once read.
    expect((TestBed.inject(Location) as SpyLocation).path()).toBe('/verify');
    req.flush({ message: 'Email verified successfully.', success: true });
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('verified');
    expect(fixture.componentInstance.resendEmail).toBe('');
  });

  it('a link sent before (address and token in the query) still activates, both in the body', () => {
    const fixture = open({ email: 'a@b.c', token: 't1' });
    const req = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        r.url === '/auth/verify-email' &&
        r.params.keys().length === 0 &&
        r.urlWithParams === '/auth/verify-email',
    );
    expect(req.request.body).toEqual({ email: 'a@b.c', token: 't1' });
    // The link's query leaves the address bar once read.
    expect((TestBed.inject(Location) as SpyLocation).path()).toBe('/verify');
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
        r.body?.email === 'a@b.c' &&
        r.urlWithParams === '/auth/resend-verification',
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

  it('QA D1306: an empty address marks the field itself, not only the banner', () => {
    const fixture = open({});
    const component = fixture.componentInstance;
    component.resendEmail = '';
    component.resend();
    const input = (fixture.nativeElement as HTMLElement).querySelector('#verify-email');
    expect(input?.getAttribute('aria-invalid')).toBe('true');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('#verify-email-invalid-msg')
        ?.textContent,
    ).toBe('LOGIN.ENTER_EMAIL');
  });
});
