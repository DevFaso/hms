import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyVitalsComponent } from './my-vitals';

describe('MyVitalsComponent', () => {
  let component: MyVitalsComponent;
  let fixture: ComponentFixture<MyVitalsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyVitalsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyVitalsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show loading initially', () => {
    expect(component.loading()).toBeTrue();
  });

  it('showRecordForm is false initially', () => {
    expect(component.showRecordForm()).toBeFalse();
  });

  it('openRecordForm sets showRecordForm to true', () => {
    component.openRecordForm();
    expect(component.showRecordForm()).toBeTrue();
  });

  it('closeRecordForm sets showRecordForm to false', () => {
    component.openRecordForm();
    component.closeRecordForm();
    expect(component.showRecordForm()).toBeFalse();
  });

  it('openRecordForm resets the form', () => {
    component.updateNumericField('heartRateBpm', '80');
    component.openRecordForm();
    expect(component.vitalForm().heartRateBpm).toBeUndefined();
  });

  it('updateNumericField sets a numeric value', () => {
    component.updateNumericField('systolicBpMmHg', '120');
    expect(component.vitalForm().systolicBpMmHg).toBe(120);
  });

  it('updateNumericField clears to undefined on empty string', () => {
    component.updateNumericField('systolicBpMmHg', '120');
    component.updateNumericField('systolicBpMmHg', '');
    expect(component.vitalForm().systolicBpMmHg).toBeUndefined();
  });

  it('updateField sets a string value', () => {
    component.updateField('bodyPosition', 'SITTING');
    expect(component.vitalForm().bodyPosition).toBe('SITTING');
  });

  it('hasAnyValue returns false for empty form', () => {
    expect(component.hasAnyValue()).toBeFalse();
  });

  it('hasAnyValue returns true when a numeric field is set', () => {
    component.updateNumericField('heartRateBpm', '72');
    expect(component.hasAnyValue()).toBeTrue();
  });

  it('submitting is false initially', () => {
    expect(component.submitting()).toBeFalse();
  });

  it('getVitalIcon returns correct icon for HEART_RATE', () => {
    expect(component.getVitalIcon('HEART_RATE')).toBe('heart_check');
  });

  it('getVitalIcon returns fallback for unknown type', () => {
    expect(component.getVitalIcon('UNKNOWN')).toBe('monitor_heart');
  });
});
