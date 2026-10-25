import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { CloseUnseenDialogComponent } from './close-unseen-dialog.component';
import { ToastService } from '../../core/toast.service';

/**
 * QA D788: the desk closes a visit no clinician has seen as left without being
 * seen (or cancelled), always with a reason; it is never a check-out.
 */
describe('CloseUnseenDialogComponent', () => {
  let fixture: ComponentFixture<CloseUnseenDialogComponent>;
  let component: CloseUnseenDialogComponent;
  let httpMock: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    toast = jasmine.createSpyObj('ToastService', ['success', 'error', 'info']);
    await TestBed.configureTestingModule({
      imports: [CloseUnseenDialogComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();
    spyOn(TestBed.inject(TranslateService), 'instant').and.callFake(
      (key: string | string[]) => key as string,
    );
    fixture = TestBed.createComponent(CloseUnseenDialogComponent);
    component = fixture.componentInstance;
    component.encounterId = 'enc-1';
    component.patientName = 'Awa';
    fixture.detectChanges();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('posts the outcome and the trimmed reason to close-unseen, never to checkout', () => {
    spyOn(component.closed, 'emit');
    component.reason.set('  Parti avant appel  ');
    component.submit();

    const req = httpMock.expectOne('/encounters/enc-1/close-unseen');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      outcome: 'LEFT_WITHOUT_BEING_SEEN',
      reason: 'Parti avant appel',
    });
    req.flush({});
    httpMock.expectNone('/encounters/enc-1/checkout');
    expect(component.closed.emit).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith('RECEPTION.CLOSE_UNSEEN_DONE');
  });

  it('asks for a reason and posts nothing without one', () => {
    component.reason.set('   ');
    component.submit();
    httpMock.expectNone('/encounters/enc-1/close-unseen');
    expect(component.error()).toBe('RECEPTION.CLOSE_UNSEEN_REASON_REQUIRED');
  });

  it('shows the server refusal (a visit already seen) and stays open', () => {
    spyOn(component.closed, 'emit');
    component.outcome.set('CANCELLED');
    component.reason.set('Erreur');
    component.submit();
    httpMock
      .expectOne('/encounters/enc-1/close-unseen')
      .flush({ message: 'Deja vue' }, { status: 400, statusText: 'Bad Request' });
    expect(component.error()).toBe('Deja vue');
    expect(component.closed.emit).not.toHaveBeenCalled();
    expect(component.saving()).toBeFalse();
  });
});
