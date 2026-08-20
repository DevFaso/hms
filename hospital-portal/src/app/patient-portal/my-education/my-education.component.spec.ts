import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MyEducationComponent } from './my-education.component';
import {
  PatientPortalService,
  PatientEducationItem,
  PatientEducationQuestion,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

describe('MyEducationComponent', () => {
  let fixture: ComponentFixture<MyEducationComponent>;
  let component: MyEducationComponent;
  let portal: jasmine.SpyObj<PatientPortalService>;
  let toast: jasmine.SpyObj<ToastService>;

  function item(overrides: Partial<PatientEducationItem> = {}): PatientEducationItem {
    return {
      progressId: 'pg-1',
      comprehensionStatus: 'NOT_STARTED',
      progressPercentage: 0,
      startedAt: null,
      completedAt: null,
      lastAccessedAt: null,
      rating: null,
      feedback: null,
      needsClarification: null,
      clarificationRequest: null,
      confirmedUnderstanding: null,
      resourceId: 'r-1',
      title: 'Warning signs in pregnancy',
      description: 'What to watch for',
      resourceType: 'ARTICLE',
      category: 'WARNING_SIGNS',
      contentUrl: null,
      textContent: 'Call the clinic if you notice severe headache.',
      thumbnailUrl: null,
      videoUrl: null,
      estimatedDuration: 8,
      tags: [],
      primaryLanguage: 'en',
      isWarningSignContent: false,
      ...overrides,
    };
  }

  const question: PatientEducationQuestion = {
    id: 'q-1',
    resourceId: 'r-1',
    questionText: 'Is this bleeding normal?',
    isUrgent: true,
    isAnswered: false,
    answerText: null,
    answeredAt: null,
    requiresInPersonDiscussion: null,
    appointmentScheduled: null,
    createdAt: '2026-08-20T10:00:00',
  };

  beforeEach(async () => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getMyEducation',
      'getMyEducationItem',
      'updateMyEducationProgress',
      'getMyEducationQuestions',
      'submitMyEducationQuestion',
    ]);
    portal.getMyEducation.and.returnValue(of([]));
    portal.getMyEducationQuestions.and.returnValue(of([]));
    portal.updateMyEducationProgress.and.returnValue(of(item()));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [MyEducationComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: portal },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyEducationComponent);
    component = fixture.componentInstance;
  });

  it('renders the empty state when nothing is assigned', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="edu-empty"]')).not.toBeNull();
  });

  it('splits assigned from completed material', () => {
    portal.getMyEducation.and.returnValue(
      of([
        item({ resourceId: 'r-1' }),
        item({ resourceId: 'r-2', completedAt: '2026-08-01T09:00:00' }),
      ]),
    );
    fixture.detectChanges();

    expect(component.assigned().map((i) => i.resourceId)).toEqual(['r-1']);
    expect(component.completed().map((i) => i.resourceId)).toEqual(['r-2']);
  });

  it('surfaces a banner when unread warning-sign material is assigned', () => {
    portal.getMyEducation.and.returnValue(of([item({ isWarningSignContent: true })]));
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="edu-warning-banner"]'),
    ).not.toBeNull();
  });

  it('does not warn when the warning-sign material is already completed', () => {
    portal.getMyEducation.and.returnValue(
      of([item({ isWarningSignContent: true, completedAt: '2026-08-01T09:00:00' })]),
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="edu-warning-banner"]')).toBeNull();
  });

  it('opening unread material records that reading started', () => {
    fixture.detectChanges();
    component.openReader(item());

    expect(portal.updateMyEducationProgress).toHaveBeenCalledWith('r-1', {
      progressPercentage: 1,
    });
    // Silent bookkeeping — the patient should not get a toast for opening a page.
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('re-opening completed material does not reset its progress', () => {
    fixture.detectChanges();
    component.openReader(item({ completedAt: '2026-08-01T09:00:00', progressPercentage: 100 }));

    expect(portal.updateMyEducationProgress).not.toHaveBeenCalled();
  });

  it('marking complete sends 100% and toasts', () => {
    fixture.detectChanges();
    component.markComplete(item());

    expect(portal.updateMyEducationProgress).toHaveBeenCalledWith('r-1', {
      progressPercentage: 100,
    });
    expect(toast.success).toHaveBeenCalled();
  });

  it('confirming understanding sends the confirmation flag', () => {
    fixture.detectChanges();
    component.confirmUnderstanding(item({ completedAt: '2026-08-01T09:00:00' }));

    expect(portal.updateMyEducationProgress).toHaveBeenCalledWith('r-1', {
      progressPercentage: 100,
      confirmedUnderstanding: true,
    });
  });

  it('a failed save surfaces an error toast', () => {
    portal.updateMyEducationProgress.and.returnValue(throwError(() => new Error('500')));
    fixture.detectChanges();
    component.markComplete(item());

    expect(toast.error).toHaveBeenCalled();
  });

  it('rating sends the star value', () => {
    fixture.detectChanges();
    component.rate(item(), 4);
    expect(portal.updateMyEducationProgress).toHaveBeenCalledWith('r-1', { rating: 4 });
  });

  it('rejects a question that is too short without calling the service', () => {
    fixture.detectChanges();
    component.openAsk(null);
    component.askText = 'hm';
    component.submitQuestion();

    expect(portal.submitMyEducationQuestion).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  it('submits a question about a specific resource', () => {
    portal.submitMyEducationQuestion.and.returnValue(of(question));
    fixture.detectChanges();
    component.openAsk(item());
    component.askText = 'Is this bleeding normal?';
    component.askUrgent = true;
    component.submitQuestion();

    expect(portal.submitMyEducationQuestion).toHaveBeenCalledWith({
      resourceId: 'r-1',
      questionText: 'Is this bleeding normal?',
      isUrgent: true,
    });
    expect(component.questions().length).toBe(1);
    expect(component.askOpen).toBeFalse();
  });

  it('submits a general question with no resource attached', () => {
    portal.submitMyEducationQuestion.and.returnValue(of(question));
    fixture.detectChanges();
    component.openAsk(null);
    component.askText = 'When is my next class?';
    component.submitQuestion();

    expect(portal.submitMyEducationQuestion).toHaveBeenCalledWith({
      resourceId: undefined,
      questionText: 'When is my next class?',
      isUrgent: false,
    });
  });

  it('loads questions lazily, once, when the tab is opened', () => {
    portal.getMyEducationQuestions.and.returnValue(of([question]));
    fixture.detectChanges();

    component.selectTab('questions');
    component.selectTab('assigned');
    component.selectTab('questions');

    expect(portal.getMyEducationQuestions).toHaveBeenCalledTimes(1);
    expect(component.questions().length).toBe(1);
  });
});
