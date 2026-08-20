import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientPortalService,
  PatientEducationItem,
  PatientEducationQuestion,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

type EducationTab = 'assigned' | 'completed' | 'questions';

@Component({
  selector: 'app-my-education',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule, EnumLabelPipe, TranslateModule],
  templateUrl: './my-education.component.html',
  styleUrls: ['./my-education.component.scss', '../patient-portal-pages.scss'],
})
export class MyEducationComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  items = signal<PatientEducationItem[]>([]);
  questions = signal<PatientEducationQuestion[]>([]);
  loading = signal(true);
  questionsLoaded = signal(false);
  activeTab = signal<EducationTab>('assigned');

  /** Anything not yet finished — what the patient still needs to read. */
  readonly assigned = computed(() => this.items().filter((i) => !i.completedAt));
  readonly completed = computed(() => this.items().filter((i) => !!i.completedAt));
  /** Warning-sign material is safety content; surface it first. */
  readonly warningSigns = computed(() =>
    this.assigned().filter((i) => i.isWarningSignContent === true),
  );

  // ── Reader state ────────────────────────────────────────────────
  reading = signal<PatientEducationItem | null>(null);
  savingProgress = signal(false);

  // ── Question state ──────────────────────────────────────────────
  askTarget = signal<PatientEducationItem | null>(null);
  askText = '';
  askUrgent = false;
  askSubmitting = signal(false);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.portal.getMyEducation().subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  selectTab(tab: EducationTab): void {
    this.activeTab.set(tab);
    if (tab === 'questions' && !this.questionsLoaded()) {
      this.portal.getMyEducationQuestions().subscribe({
        next: (qs) => {
          this.questions.set(qs);
          this.questionsLoaded.set(true);
        },
      });
    }
  }

  // ── Reading ──────────────────────────────────────────────────────

  openReader(item: PatientEducationItem): void {
    this.reading.set(item);
    // Opening counts as starting: record it so staff can see engagement,
    // but never downgrade a resource the patient already finished.
    if (!item.completedAt && (item.progressPercentage ?? 0) === 0) {
      this.saveProgress(item, { progressPercentage: 1 }, false);
    }
  }

  closeReader(): void {
    this.reading.set(null);
  }

  markComplete(item: PatientEducationItem): void {
    this.saveProgress(item, { progressPercentage: 100 }, true);
  }

  confirmUnderstanding(item: PatientEducationItem): void {
    this.saveProgress(item, { progressPercentage: 100, confirmedUnderstanding: true }, true);
  }

  rate(item: PatientEducationItem, rating: number): void {
    this.saveProgress(item, { rating }, true);
  }

  private saveProgress(
    item: PatientEducationItem,
    update: Parameters<PatientPortalService['updateMyEducationProgress']>[1],
    notify: boolean,
  ): void {
    this.savingProgress.set(true);
    this.portal.updateMyEducationProgress(item.resourceId, update).subscribe({
      next: (updated) => {
        this.items.update((list) =>
          list.map((i) => (i.resourceId === updated.resourceId ? updated : i)),
        );
        if (this.reading()?.resourceId === updated.resourceId) {
          this.reading.set(updated);
        }
        this.savingProgress.set(false);
        if (notify) {
          this.toast.success(this.translate.instant('PORTAL.EDUCATION.SAVED'));
        }
      },
      error: () => {
        this.savingProgress.set(false);
        if (notify) {
          this.toast.error(this.translate.instant('PORTAL.EDUCATION.SAVE_FAILED'));
        }
      },
    });
  }

  // ── Questions ────────────────────────────────────────────────────

  openAsk(item: PatientEducationItem | null): void {
    this.askTarget.set(item);
    this.askText = '';
    this.askUrgent = false;
  }

  closeAsk(): void {
    this.askTarget.set(null);
    this.askText = '';
  }

  get askOpen(): boolean {
    return this.askTarget() !== null;
  }

  submitQuestion(): void {
    const text = this.askText.trim();
    if (text.length < 5) {
      this.toast.error(this.translate.instant('PORTAL.EDUCATION.QUESTION_TOO_SHORT'));
      return;
    }
    this.askSubmitting.set(true);
    this.portal
      .submitMyEducationQuestion({
        resourceId: this.askTarget()?.resourceId,
        questionText: text,
        isUrgent: this.askUrgent,
      })
      .subscribe({
        next: (question) => {
          this.questions.update((list) => [question, ...list]);
          this.questionsLoaded.set(true);
          this.askSubmitting.set(false);
          this.closeAsk();
          this.toast.success(this.translate.instant('PORTAL.EDUCATION.QUESTION_SENT'));
        },
        error: () => {
          this.askSubmitting.set(false);
          this.toast.error(this.translate.instant('PORTAL.EDUCATION.QUESTION_FAILED'));
        },
      });
  }

  /** Stars are 1-5; render as an array for the template. */
  readonly stars = [1, 2, 3, 4, 5];
}
