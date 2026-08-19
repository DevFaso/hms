import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { EmpiCandidatesPanelComponent } from './empi-candidates-panel.component';
import { EmpiCandidateMatch, EmpiService } from '../../services/empi.service';
import { ToastService } from '../../core/toast.service';

describe('EmpiCandidatesPanelComponent', () => {
  let fixture: ComponentFixture<EmpiCandidatesPanelComponent>;
  let empi: jasmine.SpyObj<EmpiService>;
  let toast: jasmine.SpyObj<ToastService>;

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
    empi = jasmine.createSpyObj<EmpiService>('EmpiService', ['findCandidates']);
    empi.findCandidates.and.returnValue(of([sample]));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [EmpiCandidatesPanelComponent, TranslateModule.forRoot()],
      providers: [
        { provide: EmpiService, useValue: empi },
        { provide: ToastService, useValue: toast },
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
});
