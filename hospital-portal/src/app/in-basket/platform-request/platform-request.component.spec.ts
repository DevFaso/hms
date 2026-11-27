import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';

import { PlatformRequestComponent } from './platform-request.component';
import { ToastService } from '../../core/toast.service';

describe('PlatformRequestComponent (D4308)', () => {
  let fixture: ComponentFixture<PlatformRequestComponent>;
  let component: PlatformRequestComponent;
  let http: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    await TestBed.configureTestingModule({
      imports: [PlatformRequestComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PlatformRequestComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('sends the subject and the message once, then confirms and closes', () => {
    component.open.set(true);
    component.subject = '  Plan  ';
    component.message = ' Nous avons besoin de dix comptes de plus. ';
    component.send();
    component.send();

    const request = http.expectOne('/platform-requests');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      subject: 'Plan',
      message: 'Nous avons besoin de dix comptes de plus.',
    });
    request.flush({ id: 'r-1' });

    expect(toast.success).toHaveBeenCalledWith('inBasket.platformRequest.sent');
    expect(component.open()).toBeFalse();
    expect(component.message).toBe('');
  });

  it('shows the reason the server gave when the request is refused', () => {
    component.open.set(true);
    component.subject = 'Plan';
    component.message = 'Texte';
    component.send();
    http.expectOne('/platform-requests').flush(
      { message: 'Le message ne peut pas dépasser 2000 caractères.' },
      {
        status: 400,
        statusText: 'Bad Request',
      },
    );
    fixture.detectChanges();

    const error = fixture.nativeElement.querySelector('[data-testid="platform-request-error"]');
    expect(error.textContent.trim()).toBe('Le message ne peut pas dépasser 2000 caractères.');
    expect(component.open()).toBeTrue();
  });

  it('sends nothing while a field is empty', () => {
    component.open.set(true);
    component.subject = 'Plan';
    component.message = '   ';
    component.send();
    http.expectNone('/platform-requests');
    expect(component.sending()).toBeFalse();
  });
});
