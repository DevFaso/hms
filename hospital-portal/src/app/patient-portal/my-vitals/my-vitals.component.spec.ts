import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { resetStoredLangForTests } from '../../shared/i18n/app-locale';
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

  describe('out-of-range readings (QA D980)', () => {
    const reading = (type: string, value: string) =>
      ({
        id: type,
        type,
        value,
        unit: '',
        recordedAt: '2026-10-27T08:00:00',
        source: 'NURSE',
      }) as never;

    it('flags 142/90 as above normal', () => {
      expect(component.rangeFlag(reading('BLOOD_PRESSURE', '142/90'))).toBe('HIGH');
      expect(component.rangeFlag(reading('BLOOD_PRESSURE', '118/76'))).toBeNull();
      expect(component.rangeFlag(reading('BLOOD_PRESSURE', '85/55'))).toBe('LOW');
    });

    it('flags the other readings against the range printed under them', () => {
      expect(component.rangeFlag(reading('TEMPERATURE', '39,2'))).toBe('HIGH');
      expect(component.rangeFlag(reading('OXYGEN_SATURATION', '91'))).toBe('LOW');
      expect(component.rangeFlag(reading('HEART_RATE', '72'))).toBeNull();
      expect(component.rangeFlag(reading('WEIGHT', '72'))).toBeNull();
    });

    it('says it in words on the card', () => {
      component.vitals.set([reading('BLOOD_PRESSURE', '142/90')]);
      component.expandedGroupId.set(component.groupedVitals()[0].id);
      fixture.detectChanges();
      const flag = fixture.nativeElement.querySelector('[data-testid="flag-BLOOD_PRESSURE"]');
      expect(flag?.getAttribute('data-flag')).toBe('HIGH');
    });
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

  describe('QA D344: French patient vitals', () => {
    beforeEach(() => {
      localStorage.setItem('lang', 'fr');
      resetStoredLangForTests();
      const translate = TestBed.inject(TranslateService);
      translate.setTranslation('fr', {
        PORTAL: { VITALS: { UNIT_BREATHS_MIN: 'cycles/min', FASTING: 'à jeun' } },
      });
      translate.use('fr');
    });

    afterEach(() => {
      localStorage.removeItem('lang');
      resetStoredLangForTests();
    });

    it('writes decimals with a comma and translates the word units', () => {
      expect(component.displayValue('37.9')).toBe('37,9');
      expect(component.displayValue('120/80')).toBe('120/80');
      expect(component.unitLabel('breaths/min')).toBe('cycles/min');
      expect(component.unitLabel('mmHg')).toBe('mmHg');
      expect(component.getNormalRange('TEMPERATURE')).toBe('36,1 – 37,2 °C');
      expect(component.getNormalRange('RESPIRATORY_RATE')).toBe('12 – 20 cycles/min');
      expect(component.getNormalRange('BLOOD_GLUCOSE')).toContain('à jeun');
    });
  });
});
