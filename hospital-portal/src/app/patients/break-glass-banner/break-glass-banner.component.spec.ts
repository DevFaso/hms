import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { BreakGlassBannerComponent } from './break-glass-banner.component';
import { BreakGlassService, BreakGlassSession } from '../../services/break-glass.service';
import { ToastService } from '../../core/toast.service';
import { AuthService } from '../../auth/auth.service';

const liveSession: BreakGlassSession = {
  id: 's1',
  patientId: 'p1',
  userId: 'u1',
  userName: 'dr.alice',
  hospitalId: 'h1',
  hospitalName: 'City Clinic',
  reason: 'Trauma',
  startedAt: '2026-04-30T10:00:00',
  expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
  revokedAt: null,
  revokedByUserId: null,
  revokeReason: null,
  auditCount: 0,
  live: true,
};

describe('BreakGlassBannerComponent', () => {
  let fixture: ComponentFixture<BreakGlassBannerComponent>;
  let bgSpy: jasmine.SpyObj<BreakGlassService>;
  let authSpy: jasmine.SpyObj<AuthService>;

  function init(roles: string[] = ['ROLE_DOCTOR']): void {
    bgSpy = jasmine.createSpyObj<BreakGlassService>('BreakGlassService', [
      'declare',
      'revoke',
      'findMyLiveSession',
      'listLiveForPatient',
    ]);
    authSpy = jasmine.createSpyObj<AuthService>('AuthService', ['getRoles']);
    authSpy.getRoles.and.returnValue(roles);

    TestBed.configureTestingModule({
      imports: [BreakGlassBannerComponent, TranslateModule.forRoot()],
      providers: [
        { provide: BreakGlassService, useValue: bgSpy },
        { provide: AuthService, useValue: authSpy },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
      ],
    });

    fixture = TestBed.createComponent(BreakGlassBannerComponent);
  }

  function setInputs(patientId: string | null, hospitalId: string | null = 'h1') {
    fixture.componentRef.setInput('patientId', patientId);
    fixture.componentRef.setInput('hospitalId', hospitalId);
    fixture.detectChanges();
  }

  it('hides the banner entirely for non-clinical roles', () => {
    init(['ROLE_PATIENT']);
    bgSpy.findMyLiveSession.and.returnValue(of(null));
    setInputs('p1');
    expect(fixture.nativeElement.querySelector('.bg-banner')).toBeNull();
  });

  it('shows the prompt button when clinician has no active session', () => {
    init(['ROLE_DOCTOR']);
    bgSpy.findMyLiveSession.and.returnValue(of(null));
    setInputs('p1');
    expect(fixture.nativeElement.querySelector('.bg-banner')).not.toBeNull();
    // Active class should NOT be set when there is no session
    expect(fixture.nativeElement.querySelector('.bg-active')).toBeNull();
  });

  it('renders an active banner when a live session is loaded', () => {
    init(['ROLE_DOCTOR']);
    bgSpy.findMyLiveSession.and.returnValue(of(liveSession));
    setInputs('p1');
    expect(fixture.nativeElement.querySelector('.bg-active')).not.toBeNull();
  });

  it('falls back gracefully when /me returns an error', () => {
    init(['ROLE_DOCTOR']);
    bgSpy.findMyLiveSession.and.returnValue(throwError(() => new Error('boom')));
    setInputs('p1');
    expect(fixture.nativeElement.querySelector('.bg-active')).toBeNull();
  });

  it('E9 #64 — openDeclare opens the same declaration modal for a clinician with a scope', () => {
    init(['ROLE_DOCTOR']);
    bgSpy.findMyLiveSession.and.returnValue(of(null));
    setInputs('p1');

    expect(fixture.componentInstance.openDeclare()).toBeTrue();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.bg-modal')).not.toBeNull();
  });

  it('E9 #64 — openDeclare says why when the role cannot declare, and opens nothing', () => {
    init(['ROLE_PATIENT']);
    bgSpy.findMyLiveSession.and.returnValue(of(null));
    setInputs('p1');
    const toast = TestBed.inject(ToastService) as jasmine.SpyObj<ToastService>;

    expect(fixture.componentInstance.openDeclare()).toBeFalse();
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledWith('BREAK_GLASS.NOT_ALLOWED');
    expect(fixture.nativeElement.querySelector('.bg-modal')).toBeNull();
  });

  it('E9 #64 — openDeclare asks for a hospital scope before opening', () => {
    init(['ROLE_DOCTOR']);
    bgSpy.findMyLiveSession.and.returnValue(of(null));
    setInputs('p1', null);
    const toast = TestBed.inject(ToastService) as jasmine.SpyObj<ToastService>;

    expect(fixture.componentInstance.openDeclare()).toBeFalse();

    expect(toast.error).toHaveBeenCalledWith('BREAK_GLASS.NEED_HOSPITAL');
  });

  it('does not call the API when patientId is missing', () => {
    init(['ROLE_DOCTOR']);
    bgSpy.findMyLiveSession.and.returnValue(of(null));
    setInputs(null);
    expect(bgSpy.findMyLiveSession).not.toHaveBeenCalled();
  });
});
