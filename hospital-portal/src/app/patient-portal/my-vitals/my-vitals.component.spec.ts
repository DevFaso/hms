import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { resetStoredLangForTests } from '../../shared/i18n/app-locale';
import { of, throwError } from 'rxjs';
import { MyVitalsComponent } from './my-vitals.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MyVitalsComponent', () => {
  let component: MyVitalsComponent;
  let fixture: ComponentFixture<MyVitalsComponent>;

  const emptyPage = { items: [], records: 0, total: 0, page: 0, size: 10 };
  const mockPortalService = {
    getMyVitalsPage: jasmine.createSpy('getMyVitalsPage').and.returnValue(of(emptyPage)),
  };

  beforeEach(async () => {
    mockPortalService.getMyVitalsPage.and.returnValue(of(emptyPage));
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

  describe('every record is reachable (QA D1379)', () => {
    const vital = (record: number) =>
      ({
        id: `r${record}-heart_rate`,
        groupId: `r${record}`,
        type: 'HEART_RATE',
        value: '72',
        unit: 'bpm',
        recordedAt: `2026-10-${String(10 + record).padStart(2, '0')}T08:00:00`,
        source: 'NURSE_STATION',
      }) as never;

    it('shows the server total and pages to the older records', () => {
      mockPortalService.getMyVitalsPage.calls.reset();
      mockPortalService.getMyVitalsPage.and.callFake((page: number) =>
        of({
          items: page === 0 ? [vital(9), vital(8)] : [vital(1)],
          records: page === 0 ? 2 : 1,
          total: 45,
          page,
          size: 10,
        }),
      );
      component.loadPage(0);
      fixture.detectChanges();
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('[data-testid="vitals-pager"]')).not.toBeNull();
      expect(component.total()).toBe(45);
      (el.querySelector('[data-testid="vitals-pager-next"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(mockPortalService.getMyVitalsPage).toHaveBeenCalledWith(1, 10);
      expect(component.page()).toBe(1);
      expect(component.groupedVitals().length).toBe(1);
    });

    it('says the load failed instead of "no readings"', () => {
      mockPortalService.getMyVitalsPage.and.returnValue(throwError(() => new Error('down')));
      component.loadPage(0);
      fixture.detectChanges();
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('[data-testid="vitals-load-failed"]')).not.toBeNull();
    });
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

    it('does not compare a reading that is not in the unit of the range', () => {
      const r = (type: string, value: string, unit: string) =>
        ({
          id: type,
          type,
          value,
          unit,
          recordedAt: '2026-10-27T08:00:00',
          source: 'NURSE',
        }) as never;
      expect(component.rangeFlag(r('TEMPERATURE', '98.6', '°F'))).toBeNull();
      expect(component.rangeFlag(r('BLOOD_GLUCOSE', '5.5', 'mmol/L'))).toBeNull();
      expect(component.rangeFlag(r('TEMPERATURE', '39.2', '°C'))).toBe('HIGH');
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
