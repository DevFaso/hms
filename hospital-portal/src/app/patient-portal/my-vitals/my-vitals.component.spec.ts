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
    expect(component.expandedGroupId()).toBeNull();
    component.toggleExpand('test-id');
    expect(component.expandedGroupId()).toBe('test-id');
    component.toggleExpand('test-id');
    expect(component.expandedGroupId()).toBeNull();
  });

  it('should group vitals by groupId', () => {
    component.vitals.set([
      {
        id: 'a-heart',
        type: 'HEART_RATE',
        value: '72',
        unit: 'bpm',
        recordedAt: '2026-04-11T10:00:00Z',
        source: 'Nurse Station',
        groupId: 'group-a',
      },
      {
        id: 'a-temp',
        type: 'TEMPERATURE',
        value: '37',
        unit: '°C',
        recordedAt: '2026-04-11T10:00:00Z',
        source: 'Nurse Station',
        groupId: 'group-a',
      },
      {
        id: 'b-heart',
        type: 'HEART_RATE',
        value: '80',
        unit: 'bpm',
        recordedAt: '2026-04-10T10:00:00Z',
        source: 'Nurse Station',
        groupId: 'group-b',
      },
    ]);

    const groups = component.groupedVitals();
    expect(groups.length).toBe(2);
    expect(groups[0].id).toBe('group-a');
    expect(groups[0].vitals.length).toBe(2);
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
