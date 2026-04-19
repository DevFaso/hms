import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { MyRecordsComponent } from './my-records.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MyRecordsComponent', () => {
  let component: MyRecordsComponent;
  let fixture: ComponentFixture<MyRecordsComponent>;

  const mockPortalService = {
    getHealthSummary: () =>
      of({
        profile: { firstName: 'Jane', lastName: 'Doe', dateOfBirth: '1990-01-01' },
        allergies: ['Penicillin'],
        activeDiagnoses: ['Hypertension'],
      }),
    getMyEncounters: () => of([]),
    getMyLabResults: () => of([]),
    getMyMedications: () => of([]),
    getMyImmunizations: () => of([]),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyRecordsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: mockPortalService },
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyRecordsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load health summary', () => {
    expect(component.summary()).toBeTruthy();
    expect(component.summary()!.allergies.length).toBe(1);
  });

  it('should set loading to false after data loads', () => {
    expect(component.loading()).toBe(false);
  });
});
