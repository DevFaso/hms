import { Component, Input, Output, EventEmitter, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { InsuranceIssue } from '../reception.service';

@Component({
  selector: 'app-insurance-issues-panel',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './insurance-issues-panel.component.html',
  styleUrl: './insurance-issues-panel.component.scss',
})
export class InsuranceIssuesPanelComponent {
  @Input() issues: InsuranceIssue[] = [];
  @Output() patientClicked = new EventEmitter<string>();

  private readonly translate = inject(TranslateService);

  issueLabel(type: InsuranceIssue['issueType']): string {
    const keyMap: Record<string, string> = {
      MISSING_INSURANCE: 'RECEPTION.MISSING_INSURANCE_LABEL',
      EXPIRED_INSURANCE: 'RECEPTION.EXPIRED_INSURANCE_LABEL',
      NO_PRIMARY: 'RECEPTION.NO_PRIMARY_LABEL',
    };
    return this.translate.instant(keyMap[type] ?? type);
  }

  issueClass(type: InsuranceIssue['issueType']): string {
    return type === 'MISSING_INSURANCE' ? 'chip-danger' : 'chip-warn';
  }
}
