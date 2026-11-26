import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';
import { NursingNotesPanelComponent } from './nursing-notes-panel.component';
import { NursingNote, NursingNoteService } from '../../services/nursing-note.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

describe('NursingNotesPanelComponent (QA D927)', () => {
  let service: jasmine.SpyObj<NursingNoteService>;

  const dar: NursingNote = {
    id: 'n1',
    patientId: 'p1',
    template: 'DAR',
    dataSubjective: 'Douleur 7/10 post-morphine',
    actionSummary: 'Réévaluation douleur post-morphine',
    responseSummary: 'Douleur 3/10 à 30 min',
    authorName: 'Awa Traoré',
    documentedAt: '2026-10-27T10:30:00',
  };
  const older: NursingNote = {
    id: 'n0',
    patientId: 'p1',
    template: 'SOAPIE',
    narrative: 'Visite précédente',
    documentedAt: '2026-09-01T09:00:00',
  };

  function render(since: string | null, until: string | null = null) {
    TestBed.configureTestingModule({
      imports: [NursingNotesPanelComponent, TranslateModule.forRoot()],
      providers: [{ provide: NursingNoteService, useValue: service }],
    });
    const fixture = TestBed.createComponent(NursingNotesPanelComponent);
    fixture.componentInstance.patientId = 'p1';
    fixture.componentInstance.hospitalId = 'h1';
    fixture.componentInstance.since = since;
    fixture.componentInstance.until = until;
    fixture.componentInstance.ngOnChanges();
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('NursingNoteService', ['listRecent']);
  });

  it('chooses a hospital for notes without changing the chart scope or accepting stale notes', () => {
    const role = roleContextStub({
      superAdmin: true,
      hospitalId: null,
      roles: ['ROLE_SUPER_ADMIN'],
    });
    const previous = new Subject<NursingNote[]>();
    service.listRecent.and.returnValues(previous, of([dar]));
    TestBed.configureTestingModule({
      imports: [NursingNotesPanelComponent, TranslateModule.forRoot()],
      providers: [
        { provide: NursingNoteService, useValue: service },
        { provide: RoleContextService, useValue: role },
      ],
    });
    const fixture = TestBed.createComponent(NursingNotesPanelComponent);
    const panel = fixture.componentInstance;
    panel.patientId = 'p1';
    panel.ngOnChanges();
    fixture.detectChanges();
    expect(service.listRecent).not.toHaveBeenCalled();
    panel.selectLocalHospital({ id: 'h1', name: 'First' } as never);
    panel.selectLocalHospital({ id: 'h2', name: 'Second' } as never);
    previous.next([older]);
    expect(service.listRecent).toHaveBeenCalledWith('p1', 'h1');
    expect(service.listRecent).toHaveBeenCalledWith('p1', 'h2');
    expect(panel.notes().map((note) => note.id)).toEqual(['n1']);
    expect(role.effectiveHospitalIdForRequest()).toBeNull();
    panel.patientId = 'p2';
    panel.ngOnChanges();
    expect(panel.localHospital()).toBeNull();
    expect(panel.notes()).toEqual([]);
  });

  it('QA D1076 class: names the note template instead of printing its code', () => {
    service.listRecent.and.returnValue(of([dar]));
    const fixture = render('2026-10-27T08:00:00');
    const label = (fixture.nativeElement as HTMLElement).querySelector('.nursing-notes__template');
    expect(label?.textContent?.trim()).toBe('DAR note');
  });

  it('shows the care note the nurse wrote, with its DAR parts and author', () => {
    service.listRecent.and.returnValue(of([older, dar]));
    const fixture = render('2026-10-27T08:00:00');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(service.listRecent).toHaveBeenCalledWith('p1', 'h1');
    expect(text).toContain('Réévaluation douleur post-morphine');
    expect(text).toContain('Douleur 3/10 à 30 min');
    expect(text).toContain('Awa Traoré');
    // A note from an earlier visit is not this visit's.
    expect(text).not.toContain('Visite précédente');
  });

  it("a closed visit does not list a later visit's notes, and reads further back (review of #971)", () => {
    const later: NursingNote = {
      id: 'n2',
      patientId: 'p1',
      template: 'SOAPIE',
      narrative: 'Visite suivante',
      documentedAt: '2026-11-15T09:00:00',
    };
    service.listRecent.and.returnValue(of([later, dar, older]));
    const fixture = render('2026-10-27T08:00:00', '2026-10-27T16:00:00');
    expect(service.listRecent).toHaveBeenCalledWith('p1', 'h1', 50);
    expect(fixture.componentInstance.notes().map((n) => n.id)).toEqual(['n1']);
  });

  it('lists every note newest first when no visit start is given', () => {
    service.listRecent.and.returnValue(of([older, dar]));
    const fixture = render(null);
    expect(fixture.componentInstance.notes().map((n) => n.id)).toEqual(['n1', 'n0']);
  });

  it('a late answer for the previous patient never shows under the next one', () => {
    const first = new Subject<NursingNote[]>();
    service.listRecent.and.returnValues(first, of([dar]));
    const fixture = render(null);
    fixture.componentInstance.patientId = 'p2';
    fixture.componentInstance.ngOnChanges();
    first.next([older]);
    expect(fixture.componentInstance.notes().map((n) => n.id)).toEqual(['n1']);
  });

  it('says so when the notes cannot be loaded', () => {
    service.listRecent.and.returnValue(throwError(() => new Error('boom')));
    const fixture = render(null);
    expect(fixture.componentInstance.failed()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'NURSING_NOTES.LOAD_FAILED',
    );
    service.listRecent.and.returnValue(of([dar]));
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    expect(service.listRecent).toHaveBeenCalledTimes(2);
    expect(fixture.componentInstance.failed()).toBeFalse();
    expect(fixture.componentInstance.notes().map((note) => note.id)).toEqual(['n1']);
  });
});

/** QA D2583 (r65): a saved care note takes an addendum where it is read. */
describe('NursingNotesPanelComponent addendum (QA D2583)', () => {
  let service: jasmine.SpyObj<NursingNoteService>;
  let roles: string[];
  const note: NursingNote = {
    id: 'n1',
    patientId: 'p1',
    hospitalId: 'h1',
    template: 'DAR',
    narrative: 'Pansement refait',
    documentedAt: '2026-11-20T00:30:00',
  };

  function render() {
    TestBed.configureTestingModule({
      imports: [NursingNotesPanelComponent, TranslateModule.forRoot()],
      providers: [
        { provide: NursingNoteService, useValue: service },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(NursingNotesPanelComponent);
    fixture.componentInstance.patientId = 'p1';
    fixture.componentInstance.hospitalId = 'h1';
    fixture.componentInstance.ngOnChanges();
    fixture.detectChanges();
    return fixture;
  }

  function open(fixture: ReturnType<typeof render>) {
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="nursing-note-addendum-open"]')!
      .click();
    fixture.detectChanges();
  }

  beforeEach(() => {
    roles = ['ROLE_NURSE'];
    service = jasmine.createSpyObj('NursingNoteService', ['listRecent', 'appendAddendum']);
    service.listRecent.and.returnValue(of([note]));
  });

  it('offers « Ajouter un complément » to a nurse, and refuses an empty one at the field', () => {
    const fixture = render();
    open(fixture);
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="nursing-note-addendum-save"]',
      sent: service.appendAddendum,
    });
    expect(marked).toEqual(['nn-addendum-n1']);
  });

  it('saves the addendum, unattested unless ticked, and shows it under the note', () => {
    service.appendAddendum.and.returnValue(
      of({ ...note, addenda: [{ id: 'a1', content: 'Rougeur à revoir', authorName: 'Awa' }] }),
    );
    const fixture = render();
    open(fixture);
    fixture.componentInstance.addendumText = 'Rougeur à revoir';
    fixture.componentInstance.saveAddendum(note);
    fixture.detectChanges();
    expect(service.appendAddendum).toHaveBeenCalledWith('n1', 'Rougeur à revoir', false, 'h1');
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="note-addendum-content"]')?.textContent).toContain(
      'Rougeur à revoir',
    );
    // Saved without the attestation: said so, never "signed".
    expect(host.querySelector('[data-testid="note-addendum-unattested"]')).not.toBeNull();
  });

  it("marks the server's refusal at the addendum field", () => {
    service.appendAddendum.and.returnValue(
      throwError(() => fieldErrorsResponse({ content: 'Le complément est requis.' })),
    );
    const fixture = render();
    open(fixture);
    fixture.componentInstance.addendumText = 'x';
    fixture.componentInstance.saveAddendum(note);
    fixture.detectChanges();
    expectFieldsMarked(fixture, { 'nn-addendum-n1': 'Le complément est requis.' });
  });

  it("is read only for a role that cannot write one, and on another hospital's note", () => {
    roles = ['ROLE_SUPER_ADMIN'];
    let fixture = render();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="nursing-note-addendum-open"]',
      ),
    ).toBeNull();
    TestBed.resetTestingModule();
    roles = ['ROLE_NURSE'];
    service.listRecent.and.returnValue(of([{ ...note, hospitalId: 'h2' }]));
    fixture = render();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="nursing-note-addendum-open"]',
      ),
    ).toBeNull();
  });
});
