import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { DoctorPatientFlowComponent } from './doctor-patient-flow';
import { PatientFlowItem } from '../../services/dashboard.service';

describe('DoctorPatientFlowComponent — card source label (QA D994)', () => {
  let component: DoctorPatientFlowComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DoctorPatientFlowComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    component = TestBed.createComponent(DoctorPatientFlowComponent).componentInstance;
  });

  const card = (over: Partial<PatientFlowItem>): PatientFlowItem =>
    ({
      patientId: 'p',
      patientName: 'X',
      elapsedMinutes: 0,
      urgency: 'ROUTINE',
      state: 'ARRIVED',
      flowSource: 'ENCOUNTER',
      ...over,
    }) as PatientFlowItem;

  it('labels an urgences visit Emergency, not Outpatient', () => {
    expect(component.getSourceLabel(card({ encounterType: 'EMERGENCY' }))).toBe(
      'DASHBOARD.PATIENT_FLOW_EMERGENCY',
    );
  });

  it('labels admissions and inpatient visits Inpatient, the rest Outpatient', () => {
    expect(component.getSourceLabel(card({ flowSource: 'ADMISSION' }))).toBe(
      'DASHBOARD.PATIENT_FLOW_INPATIENT',
    );
    expect(component.getSourceLabel(card({ encounterType: 'INPATIENT' }))).toBe(
      'DASHBOARD.PATIENT_FLOW_INPATIENT',
    );
    expect(component.getSourceLabel(card({ encounterType: 'OUTPATIENT' }))).toBe(
      'DASHBOARD.PATIENT_FLOW_OUTPATIENT',
    );
  });
});
