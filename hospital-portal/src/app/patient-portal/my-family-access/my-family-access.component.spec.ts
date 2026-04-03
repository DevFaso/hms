import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyFamilyAccessComponent } from './my-family-access.component';
import { PatientPortalService } from '../../services/patient-portal.service';
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
