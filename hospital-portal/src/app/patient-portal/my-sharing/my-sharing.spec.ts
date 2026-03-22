import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MySharingComponent } from './my-sharing';

describe('MySharingComponent', () => {
  let component: MySharingComponent;
  let fixture: ComponentFixture<MySharingComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MySharingComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
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

  it('should start with loading consents true', () => {
    expect(component.loadingConsents()).toBe(true);
  });

  it('should start with empty consents list', () => {
    expect(component.consents().length).toBe(0);
  });

  it('should not show grant form initially', () => {
    expect(component.showGrantForm()).toBe(false);
  });

  it('should show grant form when openGrantForm is called', () => {
    component.openGrantForm();
    expect(component.showGrantForm()).toBe(true);
  });

  it('should hide grant form when closeGrantForm is called', () => {
    component.openGrantForm();
    component.closeGrantForm();
    expect(component.showGrantForm()).toBe(false);
  });

  it('should reset grant form when opening', () => {
    component.updateGrantField('purpose', 'Test');
    component.openGrantForm();
    expect(component.grantForm().purpose).toBe('');
  });

  it('should update grant form fields', () => {
    component.updateGrantField('purpose', 'Referral follow-up');
    expect(component.grantForm().purpose).toBe('Referral follow-up');
  });

  it('should validate grant form requires all fields', () => {
    expect(component.isGrantValid()).toBe(false);
  });

  it('should be valid when all grant fields are filled', () => {
    component.updateGrantField('fromHospitalId', 'h1');
    component.updateGrantField('toHospitalId', 'h2');
    component.updateGrantField('purpose', 'Treatment');
    component.updateGrantField('consentExpiration', '2025-12-31');
    expect(component.isGrantValid()).toBe(true);
  });

  it('should not grant when form is invalid', () => {
    component.confirmGrant();
    expect(component.granting()).toBe(false);
  });

  it('should switch to access-log tab', () => {
    component.switchToAccessLog();
    expect(component.activeTab()).toBe('access-log');
  });

  it('should start granting false', () => {
    expect(component.granting()).toBe(false);
  });

  it('should start revoking false', () => {
    expect(component.revoking()).toBe(false);
  });

  it('should start with empty hospitals list', () => {
    expect(component.hospitals().length).toBe(0);
  });
});
