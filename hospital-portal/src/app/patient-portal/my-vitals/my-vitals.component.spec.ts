import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyVitalsComponent } from './my-vitals.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MyVitalsComponent', () => {
  let component: MyVitalsComponent;
  let fixture: ComponentFixture<MyVitalsComponent>;

  const mockPortalService = {
    getMyVitals: () => of([]),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyVitalsComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: mockPortalService }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyVitalsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no vitals', () => {
    expect(component.vitals().length).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('should toggle expand', () => {
    expect(component.expandedId()).toBeNull();
    component.toggleExpand('test-id');
    expect(component.expandedId()).toBe('test-id');
    component.toggleExpand('test-id');
    expect(component.expandedId()).toBeNull();
  });

  it('should return correct vital icon', () => {
    expect(component.getVitalIcon('BLOOD_PRESSURE')).toBe('vital_signs');
    expect(component.getVitalIcon('HEART_RATE')).toBe('heart_check');
    expect(component.getVitalIcon('UNKNOWN')).toBe('monitor_heart');
  });

  it('should return normal range for known types', () => {
    expect(component.getNormalRange('BLOOD_PRESSURE')).toContain('120/80');
    expect(component.getNormalRange('UNKNOWN')).toBe('');
  });
});
