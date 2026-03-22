import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyProfileComponent } from './my-profile';

describe('MyProfileComponent', () => {
  let component: MyProfileComponent;
  let fixture: ComponentFixture<MyProfileComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyProfileComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyProfileComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show loading initially', () => {
    expect(component.loading()).toBeTrue();
  });

  it('profile is null initially', () => {
    expect(component.profile()).toBeNull();
  });

  it('showEditForm is false initially', () => {
    expect(component.showEditForm()).toBeFalse();
  });

  it('saving is false initially', () => {
    expect(component.saving()).toBeFalse();
  });

  it('getInitials returns ? when no profile', () => {
    expect(component.getInitials()).toBe('?');
  });

  it('getInitials returns initials from profile', () => {
    component.profile.set({
      patientId: '1',
      firstName: 'John',
      lastName: 'Doe',
      dateOfBirth: '1990-01-01',
      gender: 'MALE',
      email: 'john@example.com',
      phone: '555-0000',
      address: '123 Main St',
      emergencyContactName: '',
      emergencyContactPhone: '',
      emergencyContactRelationship: '',
      insuranceProvider: '',
      insuranceMemberId: '',
      insurancePlan: '',
      bloodType: 'A+',
      allergies: [],
      preferredLanguage: 'English',
      primaryCareProvider: '',
      facility: 'Test Hospital',
      memberSince: '2020-01-01',
      profileImageUrl: null,
    });
    expect(component.getInitials()).toBe('JD');
  });

  it('openEditForm sets showEditForm to true when profile exists', () => {
    component.profile.set({
      patientId: '1',
      firstName: 'Jane',
      lastName: 'Smith',
      dateOfBirth: '1985-06-15',
      gender: 'FEMALE',
      email: 'jane@example.com',
      phone: '555-1234',
      address: '456 Oak Ave',
      emergencyContactName: 'Bob',
      emergencyContactPhone: '555-5678',
      emergencyContactRelationship: 'Spouse',
      insuranceProvider: 'Blue Cross',
      insuranceMemberId: 'BC123',
      insurancePlan: 'Gold',
      bloodType: 'O+',
      allergies: ['Penicillin'],
      preferredLanguage: 'English',
      primaryCareProvider: 'Dr. Johnson',
      facility: 'City Hospital',
      memberSince: '2019-03-01',
      profileImageUrl: null,
    });
    component.openEditForm();
    expect(component.showEditForm()).toBeTrue();
  });

  it('openEditForm populates editForm from profile', () => {
    component.profile.set({
      patientId: '1',
      firstName: 'Jane',
      lastName: 'Smith',
      dateOfBirth: '1985-06-15',
      gender: 'FEMALE',
      email: 'jane@example.com',
      phone: '555-1234',
      address: '456 Oak Ave',
      emergencyContactName: 'Bob',
      emergencyContactPhone: '555-5678',
      emergencyContactRelationship: 'Spouse',
      insuranceProvider: 'Blue Cross',
      insuranceMemberId: 'BC123',
      insurancePlan: 'Gold',
      bloodType: 'O+',
      allergies: ['Penicillin'],
      preferredLanguage: 'English',
      primaryCareProvider: 'Dr. Johnson',
      facility: 'City Hospital',
      memberSince: '2019-03-01',
      profileImageUrl: null,
    });
    component.openEditForm();
    expect(component.editForm().email).toBe('jane@example.com');
    expect(component.editForm().phoneNumberPrimary).toBe('555-1234');
    expect(component.editForm().emergencyContactName).toBe('Bob');
  });

  it('closeEditForm sets showEditForm to false', () => {
    component.profile.set({
      patientId: '1',
      firstName: 'A',
      lastName: 'B',
      dateOfBirth: '',
      gender: '',
      email: '',
      phone: '',
      address: '',
      emergencyContactName: '',
      emergencyContactPhone: '',
      emergencyContactRelationship: '',
      insuranceProvider: '',
      insuranceMemberId: '',
      insurancePlan: '',
      bloodType: '',
      allergies: [],
      preferredLanguage: '',
      primaryCareProvider: '',
      facility: '',
      memberSince: '',
      profileImageUrl: null,
    });
    component.openEditForm();
    component.closeEditForm();
    expect(component.showEditForm()).toBeFalse();
  });

  it('updateField updates the edit form', () => {
    component.updateField('email', 'new@email.com');
    expect(component.editForm().email).toBe('new@email.com');
  });

  it('openEditForm does nothing when no profile', () => {
    component.openEditForm();
    expect(component.showEditForm()).toBeFalse();
  });
});
