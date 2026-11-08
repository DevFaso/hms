import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import { TranslateModule } from '@ngx-translate/core';
import { AfterVisitSummary } from '../../services/encounter.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

@Component({
  selector: 'app-visit-summary-dialog',
  standalone: true,
  imports: [CommonModule, TranslateModule, CdkTrapFocus, ServerTimePipe],
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
      s.followUpAppointmentDate
    );
  });
}
