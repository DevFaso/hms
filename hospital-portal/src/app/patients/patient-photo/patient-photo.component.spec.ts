import { TestBed, ComponentFixture } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { PatientPhotoComponent } from './patient-photo.component';
import { RegistrationExtrasService } from '../../services/registration-extras.service';
import { ToastService } from '../../core/toast.service';

describe('PatientPhotoComponent', () => {
  let fixture: ComponentFixture<PatientPhotoComponent>;
  let component: PatientPhotoComponent;
  let extrasSpy: jasmine.SpyObj<RegistrationExtrasService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    extrasSpy = jasmine.createSpyObj('RegistrationExtrasService', [
      'uploadPhoto',
      'getPhotoBlob',
      'deletePhoto',
    ]);
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);

    await TestBed.configureTestingModule({
      imports: [PatientPhotoComponent, TranslateModule.forRoot()],
      providers: [
        { provide: RegistrationExtrasService, useValue: extrasSpy },
        { provide: ToastService, useValue: toastSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PatientPhotoComponent);
    component = fixture.componentInstance;
    component.patientId = 'p1';
    component.initials = 'AK';
  });

  it('renders initials when the patient has no photo', () => {
    component.photoUpdatedAt = null;
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('AK');
    expect(extrasSpy.getPhotoBlob).not.toHaveBeenCalled();
  });

  it('fetches the photo as an authenticated blob when one exists', () => {
    // <img src> URLs carry no bearer token — the blob fetch is the contract.
    extrasSpy.getPhotoBlob.and.returnValue(of(new Blob([new Uint8Array([1])])));
    component.photoUpdatedAt = '2026-08-22T10:00:00';
    component.ngOnChanges();
    fixture.detectChanges();

    expect(extrasSpy.getPhotoBlob).toHaveBeenCalledWith('p1');
    expect(component.photoUrl()).toBeTruthy();
  });

  it('the avatar is not clickable without an authorized role', () => {
    component.canEdit = false;
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('[data-testid="patient-photo-avatar"]');
    expect(button.disabled).toBeTrue();
    component.openModal();
    expect(component.showModal()).toBeFalse();
  });

  it('uploads a selected file and reloads the photo', () => {
    component.canEdit = true;
    extrasSpy.uploadPhoto.and.returnValue(of({ photoUpdatedAt: '2026-08-22T11:00:00' }));
    extrasSpy.getPhotoBlob.and.returnValue(of(new Blob([new Uint8Array([1])])));
    fixture.detectChanges();
    component.openModal();

    const file = new File([new Uint8Array([1, 2])], 'face.jpg', { type: 'image/jpeg' });
    const input = document.createElement('input');
    input.type = 'file';
    const event = { target: { files: [file], value: '' } } as unknown as Event;
    component.onFileSelected(event);

    expect(extrasSpy.uploadPhoto).toHaveBeenCalledWith('p1', file, 'face.jpg');
    expect(toastSpy.success).toHaveBeenCalled();
    expect(input).toBeTruthy();
  });

  it('surfaces upload refusals verbatim', () => {
    component.canEdit = true;
    extrasSpy.uploadPhoto.and.returnValue(
      throwError(() => ({ error: { message: 'The photo exceeds the 5 MB limit.' } })),
    );
    fixture.detectChanges();

    const file = new File([new Uint8Array([1])], 'big.jpg', { type: 'image/jpeg' });
    component.onFileSelected({ target: { files: [file], value: '' } } as unknown as Event);

    expect(toastSpy.error).toHaveBeenCalledWith('The photo exceeds the 5 MB limit.');
  });

  it('removePhoto clears the avatar back to initials', () => {
    component.canEdit = true;
    extrasSpy.deletePhoto.and.returnValue(of(void 0));
    fixture.detectChanges();

    component.removePhoto();

    expect(extrasSpy.deletePhoto).toHaveBeenCalledWith('p1');
    expect(component.photoUrl()).toBeNull();
  });
});
