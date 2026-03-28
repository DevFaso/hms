import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { InsuranceIssue } from './reception.service';

@Component({
  selector: 'app-insurance-issues-panel',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './insurance-issues-panel.html',
})
export class InsuranceIssuesPanelComponent {
  @Input() issues: InsuranceIssue[] = [];
  @Output() patientClicked = new EventEmitter<string>();

  issueLabel(type: InsuranceIssue['issueType']): string {
    const map: Record<string, string> = {
      MISSING_INSURANCE: 'Missing Insurance',
      EXPIRED_INSURANCE: 'Expired Insurance',
      NO_PRIMARY: 'No Primary Insurance',
    };
    return map[type] ?? type;
  }

  issueClass(type: InsuranceIssue['issueType']): string {
    return type === 'MISSING_INSURANCE' ? 'chip-danger' : 'chip-warn';
  }
}
