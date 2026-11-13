import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { TranslateModule } from '@ngx-translate/core';
import { AfterVisitSummary } from '../../services/encounter.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import {
  VisitSummarySectionsComponent,
  hasContentSections,
} from '../visit-summary-sections/visit-summary-sections.component';

@Component({
  selector: 'app-visit-summary-dialog',
  standalone: true,
  imports: [
    CommonModule,
    TranslateModule,
    ModalDialogDirective,
    ServerTimePipe,
    DateTimePipe,
    VisitSummarySectionsComponent,
  ],
  templateUrl: './visit-summary-dialog.component.html',
  styleUrl: './visit-summary-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VisitSummaryDialogComponent {
  readonly summary = input.required<AfterVisitSummary>();
  readonly closed = output<void>();
  readonly hasSections = computed(() => {
    const s = this.summary();
    return !!(
      s.chiefComplaint ||
      s.dischargeDiagnoses.length ||
      s.prescriptionSummary ||
      s.referralSummary ||
      s.followUpInstructions ||
      s.patientEducationMaterials ||
      s.followUpAppointmentDate ||
      s.hospitalPhone ||
      s.departmentPhone ||
      hasContentSections(s)
    );
  });
}
