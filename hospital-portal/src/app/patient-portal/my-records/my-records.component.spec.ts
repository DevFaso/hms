import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyRecordsComponent } from './my-records.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MyRecordsComponent', () => {
  let component: MyRecordsComponent;
  let fixture: ComponentFixture<MyRecordsComponent>;

  const mockPortalService = {
    getHealthSummary: () => of({ profile: {}, allergies: [], activeDiagnoses: [] }),
    getMyEncounters: () => of([]),
    getMyLabResults: () => of([]),
    getMyMedications: () => of([]),
    getMyImmunizations: () => of([]),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyRecordsComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: mockPortalService }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyRecordsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should default to overview tab', () => {
    expect(component.activeTab()).toBe('overview');
  });

  it('should switch tabs', () => {
    component.activeTab.set('encounters');
    expect(component.activeTab()).toBe('encounters');
  });
});
