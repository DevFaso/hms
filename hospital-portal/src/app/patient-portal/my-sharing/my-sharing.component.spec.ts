import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MySharingComponent } from './my-sharing.component';
import { PatientPortalService } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

describe('MySharingComponent', () => {
  let component: MySharingComponent;
  let fixture: ComponentFixture<MySharingComponent>;

  const mockPortalService = {
    getMyConsents: () => of([]),
    getMyAccessLog: () => of([]),
    revokeConsent: () => of({}),
    grantConsent: () => of({}),
    getMyProfile: () => of({ hospitalId: 'test-hospital-id' }),
  };

  const mockToast = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MySharingComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: mockPortalService },
        { provide: ToastService, useValue: mockToast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MySharingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should default to consents tab', () => {
    expect(component.activeTab()).toBe('consents');
  });

  it('should switch to access log tab', () => {
    component.switchToAccessLog();
    expect(component.activeTab()).toBe('access-log');
  });

  it('should open and close share form', () => {
    component.openShareForm();
    expect(component.showShareForm()).toBe(true);
    component.cancelShare();
    expect(component.showShareForm()).toBe(false);
  });
});
