import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { DoctorWorklistComponent } from './doctor-worklist';
import { DoctorWorklistItem } from '../../services/dashboard.service';
import { ComponentRef } from '@angular/core';

function makeItem(overrides: Partial<DoctorWorklistItem> = {}): DoctorWorklistItem {
  return {
    patientId: 'p1',
    encounterId: 'e1',
    patientName: 'John Doe',
    mrn: 'MRN001',
    age: 30,
    sex: 'M',
    urgency: 'ROUTINE',
    encounterStatus: 'CHECKED_IN',
    updatedAt: '2026-04-14T10:00:00',
    ...overrides,
  };
}

describe('DoctorWorklistComponent', () => {
  let fixture: ComponentFixture<DoctorWorklistComponent>;
  let component: DoctorWorklistComponent;
  let componentRef: ComponentRef<DoctorWorklistComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DoctorWorklistComponent, TranslateModule.forRoot()],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(DoctorWorklistComponent);
    component = fixture.componentInstance;
    componentRef = fixture.componentRef;
  });

  describe('Tab Filters', () => {
    const items: DoctorWorklistItem[] = [
      makeItem({ encounterStatus: 'CHECKED_IN', patientId: 'p1' }),
      makeItem({ encounterStatus: 'TRIAGE', patientId: 'p2' }),
      makeItem({ encounterStatus: 'WAITING', patientId: 'p3', encounterId: 'e3' }),
      makeItem({ encounterStatus: 'IN_PROGRESS', patientId: 'p4' }),
      makeItem({ encounterStatus: 'CONSULTATION', patientId: 'p5' }),
      makeItem({ encounterStatus: 'COMPLETED', patientId: 'p6' }),
      makeItem({ encounterStatus: 'SCHEDULED', patientId: 'p7' }),
    ];

    beforeEach(() => {
      componentRef.setInput('items', items);
      fixture.detectChanges();
    });

    it('ALL tab should show all items', () => {
      component.setTab('ALL');
      expect(component.filteredItems().length).toBe(7);
    });

    it('WAITING tab should include CHECKED_IN, TRIAGE, WAITING, SCHEDULED', () => {
      component.setTab('WAITING');
      const statuses = component.filteredItems().map((i) => i.encounterStatus);
      expect(statuses).toContain('CHECKED_IN');
      expect(statuses).toContain('TRIAGE');
      expect(statuses).toContain('WAITING');
      expect(statuses).toContain('SCHEDULED');
      expect(statuses).not.toContain('IN_PROGRESS');
      expect(statuses).not.toContain('COMPLETED');
    });

    it('IN_PROGRESS tab should only include IN_PROGRESS', () => {
      component.setTab('IN_PROGRESS');
      expect(component.filteredItems().length).toBe(1);
      expect(component.filteredItems()[0].encounterStatus).toBe('IN_PROGRESS');
    });

    it('CONSULTS tab should only include CONSULTATION', () => {
      component.setTab('CONSULTS');
      expect(component.filteredItems().length).toBe(1);
      expect(component.filteredItems()[0].encounterStatus).toBe('CONSULTATION');
    });

    it('COMPLETED tab should only include COMPLETED', () => {
      component.setTab('COMPLETED');
      expect(component.filteredItems().length).toBe(1);
      expect(component.filteredItems()[0].encounterStatus).toBe('COMPLETED');
    });

    it('tab counts should be correct', () => {
      const counts = component.tabCounts();
      expect(counts['ALL']).toBe(7);
      expect(counts['WAITING']).toBe(4);
      expect(counts['IN_PROGRESS']).toBe(1);
      expect(counts['CONSULTS']).toBe(1);
      expect(counts['COMPLETED']).toBe(1);
    });
  });

  describe('Status Labels', () => {
    it('should label CHECKED_IN as Checked In', () => {
      expect(component.getStatusLabel('CHECKED_IN')).toBe('Checked In');
    });
    it('should label TRIAGE as In Triage', () => {
      expect(component.getStatusLabel('TRIAGE')).toBe('In Triage');
    });
    it('should label WAITING as Waiting', () => {
      expect(component.getStatusLabel('WAITING')).toBe('Waiting');
    });
    it('should return raw value for unknown status', () => {
      expect(component.getStatusLabel('UNKNOWN')).toBe('UNKNOWN');
    });
  });

  describe('Start Encounter', () => {
    it('should emit encounterStarted when requestStartEncounter is called', () => {
      const spy = spyOn(component.encounterStarted, 'emit');
      component.requestStartEncounter('enc-123');
      expect(spy).toHaveBeenCalledWith('enc-123');
    });
  });
});
