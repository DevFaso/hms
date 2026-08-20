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

  it('surfaces a toast when revoking fails', () => {
    fixture.detectChanges();
    portal.revokeProxy.and.returnValue(throwError(() => new Error('500')));
    component.revokeAccess('g-1');
    expect(toast.error).toHaveBeenCalled();
  });
});
