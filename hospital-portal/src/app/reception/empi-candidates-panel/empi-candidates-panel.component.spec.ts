import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { EmpiCandidatesPanelComponent } from './empi-candidates-panel.component';
import { EmpiCandidateMatch, EmpiService } from '../../services/empi.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';

describe('EmpiCandidatesPanelComponent', () => {
  let fixture: ComponentFixture<EmpiCandidatesPanelComponent>;
  let empi: jasmine.SpyObj<EmpiService>;
  let toast: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let roleContext: jasmine.SpyObj<RoleContextService>;

  const sample: EmpiCandidateMatch = {
    patientId: 'p-1',
    displayName: 'Awa Diallo',
    score: 0.95,
    nameMatched: true,
    dobMatched: true,
    sexMatched: true,
    nationalIdMatched: false,
  };

  beforeEach(async () => {
    empi = jasmine.createSpyObj<EmpiService>('EmpiService', ['findCandidates', 'mergeByPatient']);
    empi.findCandidates.and.returnValue(of([sample]));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    roleContext = jasmine.createSpyObj<RoleContextService>('RoleContextService', [
      'hasAnyActiveRole',
    ]);
    roleContext.hasAnyActiveRole.and.returnValue(true); // admin by default in specs

    await TestBed.configureTestingModule({
      imports: [EmpiCandidatesPanelComponent, TranslateModule.forRoot()],
      providers: [
        { provide: EmpiService, useValue: empi },
        { provide: ToastService, useValue: toast },
        { provide: Router, useValue: router },
        { provide: RoleContextService, useValue: roleContext },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(EmpiCandidatesPanelComponent);
  });

  it('renders the form on initial mount + no results yet', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="empi-form"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="empi-results"]')).toBeNull();
  });

  it('rejects a search with all fields blank + does not call the service', () => {
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      search: (e: Event) => void;
    };
    component.search({ preventDefault: () => undefined } as unknown as Event);
    expect(empi.findCandidates).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  it('calls the service when at least one form field is set + renders ranked results', () => {
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      form: Record<string, string>;
      search: (e: Event) => void;
    };
    component.form['firstName'] = 'Awa';
    component.search({ preventDefault: () => undefined } as unknown as Event);
    fixture.detectChanges();

    expect(empi.findCandidates).toHaveBeenCalledOnceWith(
      jasmine.objectContaining({ firstName: 'Awa', lastName: null }),
    );
    expect(fixture.nativeElement.querySelector('[data-patient-id="p-1"]')).not.toBeNull();
  });

  it('renders the empty state when the service returns no matches', () => {
    empi.findCandidates.and.returnValue(of([]));
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      form: Record<string, string>;
      search: (e: Event) => void;
    };
    component.form['lastName'] = 'NoSuchName';
    component.search({ preventDefault: () => undefined } as unknown as Event);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="empi-empty"]')).not.toBeNull();
  });

  it('renders the error state when the service errors out', () => {
    empi.findCandidates.and.returnValue(throwError(() => new Error('500')));
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      form: Record<string, string>;
      search: (e: Event) => void;
    };
    component.form['lastName'] = 'Anything';
    component.search({ preventDefault: () => undefined } as unknown as Event);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="empi-error"]')).not.toBeNull();
  });

  /* ── P1 #8: confirm navigates; admin merge flow ── */

  const second: EmpiCandidateMatch = { ...sample, patientId: 'p-2', displayName: 'Awa D.' };

  function searchWithResults(matches: EmpiCandidateMatch[]): {
    confirmMatch: (m: EmpiCandidateMatch) => void;
    toggleMergeSelection: (m: EmpiCandidateMatch) => void;
    executeMerge: () => void;
  } {
    empi.findCandidates.and.returnValue(of(matches));
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      form: Record<string, string>;
      search: (e: Event) => void;
      confirmMatch: (m: EmpiCandidateMatch) => void;
      toggleMergeSelection: (m: EmpiCandidateMatch) => void;
      executeMerge: () => void;
    };
    component.form['lastName'] = 'Diallo';
    component.search({ preventDefault: () => undefined } as unknown as Event);
    fixture.detectChanges();
    return component;
  }

  it('confirmMatch opens the confirmed patient chart when standalone', () => {
    const component = searchWithResults([sample]);
    component.confirmMatch(sample);
    expect(toast.success).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/patients', 'p-1']);
  });

  it('merge requires arming: first click arms, second executes', () => {
    empi.mergeByPatient.and.returnValue(
      of({
        id: 'me-1',
        primaryIdentityId: 'i-1',
        secondaryIdentityId: 'i-2',
        mergeType: 'MANUAL',
        mergedAt: '2026-08-20T12:00:00',
      }),
    );
    const component = searchWithResults([sample, second]);
    component.toggleMergeSelection(sample);
    component.toggleMergeSelection(second);

    component.executeMerge(); // arms only
    expect(empi.mergeByPatient).not.toHaveBeenCalled();

    component.executeMerge(); // executes
    expect(empi.mergeByPatient).toHaveBeenCalledWith('p-1', 'p-2');
    expect(toast.success).toHaveBeenCalled();
  });

  it('merge failure surfaces the merge-failed toast', () => {
    empi.mergeByPatient.and.returnValue(throwError(() => new Error('409')));
    const component = searchWithResults([sample, second]);
    component.toggleMergeSelection(sample);
    component.toggleMergeSelection(second);
    component.executeMerge();
    component.executeMerge();
    expect(toast.error).toHaveBeenCalled();
  });

  it('hides the merge affordance for non-admin roles', async () => {
    roleContext.hasAnyActiveRole.and.returnValue(false);
    // Recreate so the readonly isAdmin field re-evaluates.
    fixture = TestBed.createComponent(EmpiCandidatesPanelComponent);
    searchWithResults([sample]);
    expect(fixture.nativeElement.querySelector('[data-testid="empi-select-p-1"]')).toBeNull();
  });
});
