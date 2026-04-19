import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyMedicalHistoryComponent } from './my-medical-history.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MyMedicalHistoryComponent', () => {
  let component: MyMedicalHistoryComponent;
  let fixture: ComponentFixture<MyMedicalHistoryComponent>;

  const mockPortalService = {
    getMyMedicalHistory: () =>
      of([
        {
          id: 'd1',
          description: 'Hypertension',
          icdCode: 'I10',
          status: 'ACTIVE',
          diagnosedAt: '2023-01-15T00:00:00Z',
          diagnosedByName: 'Dr. Smith',
        },
      ]),
    getMySurgicalHistory: () =>
      of([
        {
          id: 's1',
          procedureDisplay: 'Appendectomy',
          procedureDate: '2020-06-10',
          outcome: 'Successful',
        },
      ]),
    getMyFamilyHistory: () =>
      of([
        {
          id: 'f1',
          relationship: 'Father',
          relativeName: 'John',
          conditionDisplay: 'Diabetes',
          conditionCode: 'E11',
          severity: 'MODERATE',
          ageAtOnset: 50,
        },
      ]),
    getMySocialHistory: () =>
      of({
        tobaccoUse: false,
        tobaccoQuitDate: null,
        tobaccoType: null,
        alcoholUse: true,
        alcoholFrequency: 'Social',
        alcoholDrinksPerWeek: 3,
      }),
  };

  beforeEach(async () => {
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [MyMedicalHistoryComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: mockPortalService }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyMedicalHistoryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load all history data', () => {
    expect(component.loading()).toBe(false);
    expect(component.medicalHistory().length).toBe(1);
    expect(component.surgicalHistory().length).toBe(1);
    expect(component.familyHistory().length).toBe(1);
    expect(component.socialHistory()).toBeTruthy();
  });

  it('should return correct tobacco status for never smoker', () => {
    expect(component.getTobaccoStatus()).toBe('Never');
  });

  it('should return correct alcohol status', () => {
    expect(component.getAlcoholStatus()).toBe('Social');
  });

  it('should toggle note editing', () => {
    expect(component.editingSection()).toBeNull();
    component.toggleNoteEdit('medical');
    expect(component.editingSection()).toBe('medical');
    component.toggleNoteEdit('medical');
    expect(component.editingSection()).toBeNull();
  });

  it('should save notes encrypted in localStorage', async () => {
    component.onNoteChange('medical', 'test note');
    // Manually call saveNotes via toggleNoteEdit (which calls void this.saveNotes)
    component.toggleNoteEdit('medical'); // open
    component.toggleNoteEdit('medical'); // close → triggers save
    // Allow async encryption to complete
    await new Promise((resolve) => setTimeout(resolve, 200));

    const stored = localStorage.getItem('portal-notes-medical');
    expect(stored).toBeTruthy();
    // Stored value should be encrypted JSON, not plain text
    expect(stored).not.toBe('test note');
    const parsed = JSON.parse(stored!);
    expect(parsed.s).toBeTruthy();
    expect(parsed.i).toBeTruthy();
    expect(parsed.c).toBeTruthy();
  });

  it('should get notes by section', () => {
    component.onNoteChange('surgical', 'surgical note');
    expect(component.getNotes('surgical')).toBe('surgical note');
    expect(component.getNotes('unknown')).toBe('');
  });
});
