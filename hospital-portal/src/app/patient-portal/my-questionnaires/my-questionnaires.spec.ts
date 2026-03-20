import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyQuestionnairesComponent } from './my-questionnaires';
import { PortalQuestionnaire } from '../../services/patient-portal.service';

const MOCK_QUESTION: PortalQuestionnaire = {
  id: 'q1',
  title: 'Pre-Visit Health Check',
  description: 'Please complete before your appointment.',
  questions: [
    { id: 'a1', question: 'Are you in pain?', type: 'YES_NO', required: true },
    { id: 'a2', question: 'Pain level?', type: 'SCALE', required: false },
    {
      id: 'a3',
      question: 'Primary concern?',
      type: 'CHOICE',
      required: true,
      options: ['Back', 'Chest', 'Head'],
    },
    { id: 'a4', question: 'Medical history notes?', type: 'TEXT', required: false },
  ],
};

describe('MyQuestionnairesComponent', () => {
  let component: MyQuestionnairesComponent;
  let fixture: ComponentFixture<MyQuestionnairesComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyQuestionnairesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyQuestionnairesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should default to pending tab', () => {
    expect(component.activeTab()).toBe('pending');
  });

  it('should show loading state initially', () => {
    expect(component.loading()).toBeTrue();
  });

  it('switchTab changes active tab and closes form', () => {
    component.openForm(MOCK_QUESTION);
    component.switchTab('submitted');
    expect(component.activeTab()).toBe('submitted');
    expect(component.activeForm()).toBeNull();
  });

  it('openForm sets activeForm and pre-fills YES_NO answers', () => {
    component.openForm(MOCK_QUESTION);
    expect(component.activeForm()).toBe(MOCK_QUESTION);
    expect(component.answers['a1']).toBe('no');
  });

  it('closeForm clears activeForm and answers', () => {
    component.openForm(MOCK_QUESTION);
    component.answers['a1'] = 'yes';
    component.closeForm();
    expect(component.activeForm()).toBeNull();
    expect(component.answers).toEqual({});
  });

  it('isFormValid returns false when required field missing', () => {
    component.openForm(MOCK_QUESTION);
    // a3 is required CHOICE, not filled
    delete (component.answers as Record<string, string>)['a3'];
    expect(component.isFormValid()).toBeFalse();
  });

  it('isFormValid returns true when all required fields filled', () => {
    component.openForm(MOCK_QUESTION);
    component.answers['a1'] = 'yes';
    component.answers['a3'] = 'Back';
    expect(component.isFormValid()).toBeTrue();
  });

  it('scaleRange returns 1-10 array', () => {
    expect(component.scaleRange()).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  });

  it('questionTypeLabel returns readable labels', () => {
    expect(component.questionTypeLabel('YES_NO')).toBe('Yes / No');
    expect(component.questionTypeLabel('SCALE')).toBe('Scale 1–10');
    expect(component.questionTypeLabel('CHOICE')).toBe('Multiple choice');
    expect(component.questionTypeLabel('TEXT')).toBe('Text');
  });

  it('getAnswerDisplay returns answer or em dash', () => {
    const answers = { q1: 'yes' };
    expect(component.getAnswerDisplay(answers, 'q1')).toBe('yes');
    expect(component.getAnswerDisplay(answers, 'missing')).toBe('—');
  });

  it('trackById returns question id', () => {
    const q = { id: 'x1', question: 'Q', type: 'TEXT' as const, required: false };
    expect(component.trackById(0, q)).toBe('x1');
  });
});
