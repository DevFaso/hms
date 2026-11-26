import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyMedicalHistoryComponent } from './my-medical-history.component';
import { PatientPortalService } from '../../services/patient-portal.service';
import { AuthService } from '../../auth/auth.service';

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

  let userId: string | null;
  const authStub = {
    getUserProfile: () => (userId ? { id: userId } : null),
    getUserId: () => userId,
  };

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    userId = 'patient-user-1';

    await TestBed.configureTestingModule({
      imports: [MyMedicalHistoryComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: mockPortalService },
        { provide: AuthService, useValue: authStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyMedicalHistoryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
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

  it("keeps a saved note in this tab's sessionStorage under the user's own key, never in localStorage", () => {
    component.toggleNoteEdit('medical'); // open
    component.onNoteChange('medical', 'test note');
    component.toggleNoteEdit('medical'); // close → save

    expect(sessionStorage.getItem('hms.patientNotes.patient-user-1.medical')).toBe('test note');
    expect(localStorage.length).toBe(0);
  });

  it("shows the signed-in user's notes only, not another user's from the same tab", () => {
    sessionStorage.setItem('hms.patientNotes.patient-user-1.family', 'mine');
    sessionStorage.setItem('hms.patientNotes.someone-else.surgical', 'theirs');
    const f = TestBed.createComponent(MyMedicalHistoryComponent);
    f.detectChanges();
    expect(f.componentInstance.familyNotes()).toBe('mine');
    expect(f.componentInstance.surgicalNotes()).toBe('');
  });

  it('keeps nothing when no user is signed in', () => {
    userId = null;
    component.toggleNoteEdit('social');
    component.onNoteChange('social', 'draft');
    component.toggleNoteEdit('social');
    expect(sessionStorage.length).toBe(0);
    expect(localStorage.length).toBe(0);
  });

  it('an emptied note is dropped, and Escape restores the saved one', () => {
    sessionStorage.setItem('hms.patientNotes.patient-user-1.medical', 'saved');
    component.toggleNoteEdit('medical');
    component.onNoteChange('medical', 'typed but discarded');
    component.cancelNoteEdit('medical');
    expect(component.medicalNotes()).toBe('saved');

    component.toggleNoteEdit('medical');
    component.onNoteChange('medical', '');
    component.toggleNoteEdit('medical');
    expect(sessionStorage.getItem('hms.patientNotes.patient-user-1.medical')).toBeNull();
  });

  it('should get notes by section', () => {
    component.onNoteChange('surgical', 'surgical note');
    expect(component.getNotes('surgical')).toBe('surgical note');
    expect(component.getNotes('unknown')).toBe('');
  });

  // QA D1214 / round-23 item 7: A3's /me/patient/medical-history answers two
  // ACTIVE B50.9 rows (a problem-list entry and a V14 diagnosis, distinct ids).
  // The report said the page was empty; the tour log (r23 tour-A3.log, line 6)
  // shows it rendered. This pins the shape the API returns: both rows shown.
  it('QA D1214: two ACTIVE rows of the same diagnosis from the two stores are both listed', async () => {
    const row = {
      description: 'Paludisme à Plasmodium falciparum, simple',
      icdCode: 'B50.9',
      status: 'ACTIVE',
      diagnosedByName: null,
    };
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [MyMedicalHistoryComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AuthService, useValue: authStub },
        {
          provide: PatientPortalService,
          useValue: {
            ...mockPortalService,
            getMyMedicalHistory: () =>
              of([
                { ...row, id: 'problem-1', diagnosedAt: '2026-10-31T23:40:00Z' },
                { ...row, id: 'diagnosis-1', diagnosedAt: '2026-10-31T23:41:00Z' },
              ]),
          },
        },
      ],
    }).compileComponents();
    const f = TestBed.createComponent(MyMedicalHistoryComponent);
    f.detectChanges();
    const rows = (f.nativeElement as HTMLElement).querySelectorAll(
      '.history-section:first-of-type .history-table tbody tr',
    );
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Paludisme');
  });
});
