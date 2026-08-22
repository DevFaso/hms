import {
  ChangeDetectionStrategy,
  Component,
  computed,
  EventEmitter,
  inject,
  Input,
  OnDestroy,
  OnInit,
  Output,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { Subject, of } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { EncounterNoteRequest, EncounterNoteTemplate } from '../../services/encounter.service';
import { SmartPhrase, SmartPhraseService } from '../../services/smart-phrase.service';

type NoteFieldKey =
  | 'chiefComplaint'
  | 'historyOfPresentIllness'
  | 'reviewOfSystems'
  | 'physicalExam'
  | 'diagnosticResults'
  | 'subjective'
  | 'objective'
  | 'assessment'
  | 'plan'
  | 'implementation'
  | 'evaluation'
  | 'patientInstructions';

interface SmartPhrasePopupState {
  fieldKey: NoteFieldKey;
  triggerStart: number;
  prefix: string;
  suggestions: SmartPhrase[];
  highlightedIndex: number;
}

/**
 * Per-section EncounterNote form (P1 #12 follow-up #4 — item 5).
 *
 * Replaces the single-textarea fallback on the encounter detail panel with
 * the structured sections that were already on EncounterNote (chief complaint,
 * HPI, ROS, exam, diagnostic results, SOAP / SOAPIE, patient instructions).
 *
 * SmartPhrase autocomplete (item 6) listens for a `.` token in any section,
 * queries `/smart-phrases/autocomplete` with the active hospital, and replaces
 * the trigger token with the macro expansion when the clinician picks one
 * (Enter / Tab / click).
 */
@Component({
  selector: 'app-encounter-note-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './encounter-note-form.component.html',
  styleUrl: './encounter-note-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EncounterNoteFormComponent implements OnInit, OnDestroy {
  private readonly smartPhraseService = inject(SmartPhraseService);

  @Input() saving = false;
  @Input() hospitalId: string | null = null;
  @Output() readonly noteSubmit = new EventEmitter<EncounterNoteRequest>();
  @Output() readonly noteCancel = new EventEmitter<void>();

  readonly templates: EncounterNoteTemplate[] = ['SOAP', 'SOAPIE'];

  readonly form = signal<EncounterNoteRequest>({
    template: 'SOAP',
    chiefComplaint: '',
    historyOfPresentIllness: '',
    reviewOfSystems: '',
    physicalExam: '',
    diagnosticResults: '',
    subjective: '',
    objective: '',
    assessment: '',
    plan: '',
    implementation: '',
    evaluation: '',
    patientInstructions: '',
    lateEntry: false,
    attestAccuracy: false,
    attestNoAbbreviations: false,
    attestSpellCheck: false,
    requiresCosign: false,
  });

  readonly isSoapie = computed(() => this.form().template === 'SOAPIE');

  readonly popup = signal<SmartPhrasePopupState | null>(null);

  private readonly searchTerm$ = new Subject<{
    field: NoteFieldKey;
    prefix: string;
    triggerStart: number;
  }>();
  private destroyed = false;

  ngOnInit(): void {
    this.searchTerm$
      .pipe(
        debounceTime(150),
        distinctUntilChanged(
          (a, b) =>
            a.field === b.field && a.prefix === b.prefix && a.triggerStart === b.triggerStart,
        ),
        switchMap((q) =>
          this.smartPhraseService.autocomplete(q.prefix, this.hospitalId ?? undefined).pipe(
            catchError(() => of([] as SmartPhrase[])),
            switchMap((suggestions) =>
              of({ field: q.field, prefix: q.prefix, triggerStart: q.triggerStart, suggestions }),
            ),
          ),
        ),
      )
      .subscribe((result) => {
        if (this.destroyed) return;
        if (!result.suggestions.length) {
          this.popup.set(null);
          return;
        }
        this.popup.set({
          fieldKey: result.field,
          triggerStart: result.triggerStart,
          prefix: result.prefix,
          suggestions: result.suggestions,
          highlightedIndex: 0,
        });
      });
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.searchTerm$.complete();
  }

  setTemplate(template: EncounterNoteTemplate): void {
    if (this.form().template === template) return;
    this.patch({ template });
  }

  onTextareaInput(field: NoteFieldKey, event: Event): void {
    const el = event.target as HTMLTextAreaElement;
    const value = el.value;
    this.patch({ [field]: value } as Partial<EncounterNoteRequest>);
    this.maybeOpenPopup(field, el);
  }

  private maybeOpenPopup(field: NoteFieldKey, el: HTMLTextAreaElement): void {
    const cursor = el.selectionStart ?? 0;
    const upToCursor = el.value.slice(0, cursor);
    const triggerMatch = /(?:^|[\s\n])(\.[\w-]*)$/.exec(upToCursor);
    if (!triggerMatch) {
      this.popup.set(null);
      return;
    }
    const prefix = triggerMatch[1].toLowerCase();
    const triggerStart = upToCursor.length - prefix.length;
    if (prefix.length < 2) {
      // Need at least ".x" before we hit the network
      this.popup.set(null);
      return;
    }
    this.searchTerm$.next({ field, prefix, triggerStart });
  }

  onTextareaKeydown(field: NoteFieldKey, event: KeyboardEvent): void {
    const popup = this.popup();
    if (!popup || popup.fieldKey !== field) {
      return;
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.popup.set({
          ...popup,
          highlightedIndex: (popup.highlightedIndex + 1) % popup.suggestions.length,
        });
        return;
      case 'ArrowUp':
        event.preventDefault();
        this.popup.set({
          ...popup,
          highlightedIndex:
            (popup.highlightedIndex - 1 + popup.suggestions.length) % popup.suggestions.length,
        });
        return;
      case 'Enter':
      case 'Tab':
        event.preventDefault();
        this.acceptSuggestion(popup.highlightedIndex, event.target as HTMLTextAreaElement);
        return;
      case 'Escape':
        event.preventDefault();
        this.popup.set(null);
        return;
      default:
        return;
    }
  }

  pickSuggestion(index: number, fieldKey: NoteFieldKey): void {
    const current = this.popup();
    if (!current || current.fieldKey !== fieldKey) return;
    const textarea = document.querySelector<HTMLTextAreaElement>(
      `textarea[data-note-field="${fieldKey}"]`,
    );
    if (!textarea) return;
    this.acceptSuggestion(index, textarea);
  }

  private acceptSuggestion(index: number, el: HTMLTextAreaElement): void {
    const popup = this.popup();
    if (!popup) return;
    const suggestion = popup.suggestions[index];
    if (!suggestion) return;
    const fieldKey = popup.fieldKey;
    const currentValue = (this.form()[fieldKey] as string) ?? '';
    const before = currentValue.slice(0, popup.triggerStart);
    const after = currentValue.slice(popup.triggerStart + popup.prefix.length);
    const next = before + suggestion.expansion + after;
    this.patch({ [fieldKey]: next } as Partial<EncounterNoteRequest>);
    this.popup.set(null);
    // Move cursor to the end of the inserted block on the next tick
    setTimeout(() => {
      const newPos = before.length + suggestion.expansion.length;
      el.focus();
      try {
        el.setSelectionRange(newPos, newPos);
      } catch {
        // some browsers reject selection on detached elements; harmless
      }
    });
    this.smartPhraseService.recordUsage(suggestion.id).subscribe({
      error: () => {
        // best-effort usage telemetry — never break the clinical flow
      },
    });
  }

  toggleAttestation(field: 'attestAccuracy' | 'attestNoAbbreviations' | 'attestSpellCheck'): void {
    const current = this.form()[field] ?? false;
    this.patch({ [field]: !current } as Partial<EncounterNoteRequest>);
  }

  toggleLateEntry(): void {
    const current = this.form().lateEntry ?? false;
    const next = !current;
    this.patch({
      lateEntry: next,
      eventOccurredAt: next ? this.form().eventOccurredAt : undefined,
    });
  }

  setEventOccurredAt(value: string): void {
    this.patch({ eventOccurredAt: value || undefined });
  }

  patch(delta: Partial<EncounterNoteRequest>): void {
    this.form.update((current) => ({ ...current, ...delta }));
  }

  cancelEdit(): void {
    this.noteCancel.emit();
  }

  submit(formRef: NgForm): void {
    if (formRef.invalid) {
      return;
    }
    const value = this.form();
    // Strip empty strings so we don't write blank columns over real data
    const payload: EncounterNoteRequest = {
      template: value.template,
    };
    const stringFields: NoteFieldKey[] = [
      'chiefComplaint',
      'historyOfPresentIllness',
      'reviewOfSystems',
      'physicalExam',
      'diagnosticResults',
      'subjective',
      'objective',
      'assessment',
      'plan',
      'implementation',
      'evaluation',
      'patientInstructions',
    ];
    for (const key of stringFields) {
      const v = (value[key] as string | undefined)?.trim();
      if (v) {
        (payload as unknown as Record<string, unknown>)[key] = v;
      }
    }
    if (value.summary?.trim()) {
      payload.summary = value.summary.trim();
    }
    if (value.lateEntry) {
      payload.lateEntry = true;
      payload.eventOccurredAt = value.eventOccurredAt;
    }
    payload.attestAccuracy = value.attestAccuracy ?? false;
    payload.attestNoAbbreviations = value.attestNoAbbreviations ?? false;
    payload.attestSpellCheck = value.attestSpellCheck ?? false;
    // Signing is no longer part of the save: the old form stamped a browser
    // timestamp and two free-text names into the body, which the backend now
    // refuses. The ceremony lives behind the Sign button on the detail panel.
    if (value.requiresCosign) {
      payload.requiresCosign = true;
    }
    this.noteSubmit.emit(payload);
  }
}
