import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MyFamilyAccessComponent } from './my-family-access.component';
import { PatientPortalService, ProxyResponse } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

describe('MyFamilyAccessComponent', () => {
  let component: MyFamilyAccessComponent;
  let fixture: ComponentFixture<MyFamilyAccessComponent>;

  const mockPortalService = {
    getMyProxies: () => of([]),
    getMyProxyAccess: () => of([]),
    grantProxy: () => of({}),
    revokeProxy: () => of({}),
  };

  const mockToast = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyFamilyAccessComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: PatientPortalService, useValue: mockPortalService },
        { provide: ToastService, useValue: mockToast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyFamilyAccessComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should default to granted tab', () => {
    expect(component.activeTab()).toBe('granted');
  });

  it('should toggle grant form', () => {
    expect(component.showGrantForm()).toBe(false);
    component.showGrantForm.set(true);
    expect(component.showGrantForm()).toBe(true);
  });

  it('should not grant when fields missing', () => {
    component.form = { proxyUsername: '', relationship: '', permissions: 'ALL' };
    component.grantAccess();
    expect(mockToast.error).toHaveBeenCalled();
  });
});

/* ── P1 #9: canonical VIEW_* scopes + drill-in to the proxy data viewer ── */

describe('MyFamilyAccessComponent — proxy scopes and drill-in', () => {
  let fixture: ComponentFixture<MyFamilyAccessComponent>;
  let component: MyFamilyAccessComponent;
  let portal: jasmine.SpyObj<PatientPortalService>;
  let toast: jasmine.SpyObj<ToastService>;

  const received: ProxyResponse = {
    id: 'g-1',
    grantorPatientId: 'p-77',
    grantorName: 'Awa Diallo',
    proxyUserId: 'u-2',
    proxyUsername: 'caregiver.sam',
    proxyDisplayName: 'Sam Kabore',
    relationship: 'CAREGIVER',
    status: 'ACTIVE',
    permissions: 'ALL',
    expiresAt: null,
    revokedAt: null,
    notes: null,
    createdAt: '2026-08-01T10:00:00',
  };

  beforeEach(async () => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getMyProxies',
      'getMyProxyAccess',
      'grantProxy',
      'revokeProxy',
    ]);
    portal.getMyProxies.and.returnValue(of([]));
    portal.getMyProxyAccess.and.returnValue(of([received]));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [MyFamilyAccessComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: PatientPortalService, useValue: portal },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyFamilyAccessComponent);
    component = fixture.componentInstance;
  });

  it('offers only canonical VIEW_* scopes in the permissions picker', () => {
    fixture.detectChanges();
    component.showGrantForm.set(true);
    fixture.detectChanges();

    const values = Array.from(
      fixture.nativeElement.querySelectorAll('select') as NodeListOf<HTMLSelectElement>,
    )
      .flatMap((sel) => Array.from(sel.options))
      .map((o) => o.value)
      .filter((v) => v.includes('APPOINTMENTS') || v.includes('BILLING') || v.includes('RECORDS'));

    expect(values.length).toBeGreaterThan(0);
    values.forEach((v) => expect(v.startsWith('VIEW_')).toBeTrue());
  });

  it('grants access with the selected canonical scope', () => {
    fixture.detectChanges();
    portal.grantProxy.and.returnValue(of(received));

    component.form = {
      proxyUsername: 'caregiver.sam',
      relationship: 'CAREGIVER',
      permissions: 'VIEW_APPOINTMENTS',
    };
    component.grantAccess();

    expect(portal.grantProxy).toHaveBeenCalledWith(
      jasmine.objectContaining({ permissions: 'VIEW_APPOINTMENTS' }),
    );
    expect(toast.success).toHaveBeenCalled();
  });

  // #948 review: the expiry is checked against the server's UTC clock and
  // listed back through serverTime, so the end of the picked day is sent as
  // the reader's 23:59:59 on the UTC clock, not as the wall clock.
  it('sends the expiry as the end of the reader day on the UTC clock', () => {
    fixture.detectChanges();
    portal.grantProxy.and.returnValue(of(received));
    component.form = {
      proxyUsername: 'caregiver.sam',
      relationship: 'CAREGIVER',
      permissions: 'VIEW_APPOINTMENTS',
    };
    component.expiresOn = '2026-10-24';
    component.grantAccess();

    const endOfDay = new Date(2026, 9, 24, 23, 59, 59);
    const sent = portal.grantProxy.calls.mostRecent().args[0];
    expect(sent.expiresAt).toBe(endOfDay.toISOString().slice(0, 19));
    expect(new Date(`${sent.expiresAt}Z`).getTime()).toBe(endOfDay.getTime());
  });

  it('offers today in the reader zone as the earliest expiry', () => {
    const d = new Date();
    const p = (n: number) => String(n).padStart(2, '0');
    expect(component.minExpiry).toBe(`${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`);
  });

  it('links each received grant to its proxy data viewer', () => {
    fixture.detectChanges();
    component.switchToReceived();
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector(
      '[data-testid="view-data-p-77"]',
    ) as HTMLAnchorElement;
    expect(link).not.toBeNull();
    expect(link.getAttribute('href')).toBe('/my-family-access/p-77');
  });

  it('QA D893: an unknown username says so, in the server message, not a generic failure', () => {
    fixture.detectChanges();
    const message = "Aucun compte ne porte l'identifiant inconnu.xyz.";
    portal.grantProxy.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404, error: { message } })),
    );
    component.form = {
      proxyUsername: 'inconnu.xyz',
      relationship: 'CAREGIVER',
      permissions: 'ALL',
    };
    component.grantAccess();
    expect(toast.error).toHaveBeenCalledWith(message);
    expect(component.granting()).toBeFalse();
  });

  it('QA D893: a 404 without a message still names the unknown user', () => {
    fixture.detectChanges();
    portal.grantProxy.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    component.form = {
      proxyUsername: 'inconnu.xyz',
      relationship: 'CAREGIVER',
      permissions: 'ALL',
    };
    component.grantAccess();
    expect(toast.error).toHaveBeenCalledWith('PORTAL.FAMILY.USER_NOT_FOUND');
  });

  it('QA D893: a server error keeps the generic failure (no technical text)', () => {
    fixture.detectChanges();
    portal.grantProxy.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 500, error: { message: 'NullPointerException' } }),
      ),
    );
    component.form = { proxyUsername: 'sam', relationship: 'CAREGIVER', permissions: 'ALL' };
    component.grantAccess();
    expect(toast.error).toHaveBeenCalledWith('PORTAL.FAMILY.GRANT_FAILED');
  });

  it('surfaces a toast when revoking fails', () => {
    fixture.detectChanges();
    portal.revokeProxy.and.returnValue(throwError(() => new Error('500')));
    component.revokeAccess('g-1');
    expect(toast.error).toHaveBeenCalled();
  });

  // QA D1197 (round 22): "Révoquer" acted on one click and dropped focus to BODY.
  it('QA D1197: revoking asks for a confirmation; Cancel keeps the access, Confirm revokes', async () => {
    const granted = { ...received, id: 'proxy-9', proxyDisplayName: 'Salif Ouédraogo' };
    portal.getMyProxies.and.returnValue(of([granted]));
    portal.revokeProxy.and.returnValue(of(undefined));
    const host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
    try {
      fixture.detectChanges();
      const revoke = host.querySelector<HTMLButtonElement>('[data-testid="revoke-proxy-proxy-9"]')!;
      revoke.focus();
      revoke.click();
      fixture.detectChanges();
      expect(portal.revokeProxy).withContext('one click no longer revokes').not.toHaveBeenCalled();
      const dialog = host.querySelector<HTMLElement>('[data-testid="confirm-dialog"]')!;
      expect(dialog.getAttribute('role')).toBe('dialog');
      expect(dialog.textContent).toContain('Salif Ouédraogo');
      host.querySelector<HTMLButtonElement>('[data-testid="confirm-dialog-cancel"]')!.click();
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="confirm-dialog"]')).toBeNull();
      expect(document.activeElement).toBe(revoke);
      expect(portal.revokeProxy).not.toHaveBeenCalled();

      revoke.click();
      fixture.detectChanges();
      host.querySelector<HTMLButtonElement>('[data-testid="confirm-dialog-confirm"]')!.click();
      fixture.detectChanges();
      expect(portal.revokeProxy).toHaveBeenCalledOnceWith('proxy-9');
      expect(host.querySelector('[data-testid="confirm-dialog"]')).toBeNull();
    } finally {
      host.remove();
    }
  });
});
