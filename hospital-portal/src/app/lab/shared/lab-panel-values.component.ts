import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { LabResultComponent } from '../../services/lab.service';
import { LabValuePipe } from './lab-value.pipe';
import { formatLabReferenceRange } from './reference-range-text';
import { labSeverityFlag, labSeverityTone } from './lab-severity';
import { LabCorrectionBadgeComponent } from './lab-correction-badge.component';

/**
 * r63 (NFS panels): a panel result's component values, each with its unit,
 * the range it was graded against and its own grade. Used by the result
 * detail and the release worklist, so both read a full blood count the same.
 */
@Component({
  selector: 'app-lab-panel-values',
  standalone: true,
  imports: [TranslateModule, LabValuePipe, LabCorrectionBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="panel-values" role="region" [attr.aria-label]="'LAB_PANEL.TITLE' | translate">
      <!-- QA D1016: a panel that corrects a released one says so beside its values -->
      <app-lab-correction-badge [corrected]="corrected()" />
      <table class="panel-values-table">
        <caption class="sr-only">
          {{
            'LAB_PANEL.TITLE' | translate
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col">{{ 'LAB_PANEL.COL_COMPONENT' | translate }}</th>
            <th scope="col">{{ 'LAB_PANEL.COL_VALUE' | translate }}</th>
            <th scope="col">{{ 'LAB_PANEL.COL_RANGE' | translate }}</th>
            <th scope="col">{{ 'LAB_PANEL.COL_FLAG' | translate }}</th>
          </tr>
        </thead>
        <tbody>
          @for (c of components(); track c.code) {
            <tr [class]="'tone-' + tone(c)">
              <td>
                {{ c.name }} <span class="code">({{ c.code }})</span>
              </td>
              <td class="value">{{ c.value | labValue: c.unit }}</td>
              <td>{{ range(c) }}</td>
              <td>{{ 'LAB_RELEASE.FLAG_' + flag(c) | translate }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .panel-values {
      overflow-x: auto;
      max-width: 100%;
    }
    .panel-values-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.85rem;
    }
    th,
    td {
      text-align: left;
      padding: 0.3rem 0.5rem;
      border-bottom: 1px solid #e2e8f0;
      overflow-wrap: anywhere;
    }
    th {
      font-weight: 600;
      color: #475569;
    }
    .code {
      color: var(--text-secondary);
    }
    .value {
      font-weight: 600;
      white-space: nowrap;
    }
    .tone-critical td {
      color: #b91c1c;
      font-weight: 600;
    }
    .tone-abnormal .value {
      color: #b45309;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class LabPanelValuesComponent {
  readonly components = input<LabResultComponent[]>([]);
  /** The result these values belong to corrects a released one (D81). */
  readonly corrected = input<boolean>(false);

  flag(c: LabResultComponent): string {
    return labSeverityFlag({ severityFlag: c.severityFlag });
  }

  tone(c: LabResultComponent): string {
    return labSeverityTone(labSeverityFlag({ severityFlag: c.severityFlag }));
  }

  range(c: LabResultComponent): string {
    return c.referenceRange
      ? formatLabReferenceRange({
          minValue: c.referenceRange.minValue,
          maxValue: c.referenceRange.maxValue,
          unit: c.referenceRange.unit ?? c.unit,
        })
      : '—';
  }
}
