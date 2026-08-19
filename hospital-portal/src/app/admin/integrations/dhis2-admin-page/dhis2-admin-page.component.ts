import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';

import { Dhis2Service } from '../../../services/integrations/dhis2.service';
import {
  DHIS2_UID_REGEX,
  Dhis2AuthMode,
  Dhis2FacilityConfig,
  Dhis2FacilityConfigRequest,
  Dhis2PeriodType,
  ENV_VAR_REGEX,
} from '../../../services/integrations/dhis2.model';
import { RoleContextService } from '../../../core/role-context.service';
import { ToastService } from '../../../core/toast.service';
import { Dhis2ExportPanelComponent } from '../dhis2-export-panel/dhis2-export-panel.component';
import { Dhis2MappingEditorComponent } from '../dhis2-mapping-editor/dhis2-mapping-editor.component';

type Tab = 'config' | 'mappings' | 'exports';

/**
 * Three-tab shell at {@code /admin/integrations/dhis2}: facility
 * config form, dataElement mappings editor, manual export panel.
 *
 * <p>Tabs use the WAI-ARIA APG pattern: stable {@code id} on each
 * tab + matching {@code aria-controls}, plus {@code aria-labelledby}
 * pointing back from each tabpanel.
 */
@Component({
  selector: 'app-dhis2-admin-page',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    Dhis2ExportPanelComponent,
    Dhis2MappingEditorComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="dhis2-admin" data-testid="dhis2-admin-page">
      <header class="dhis2-admin__header">
        <h1>{{ 'DHIS2.ADMIN.HEADING' | translate }}</h1>
      </header>

      <nav role="tablist" class="dhis2-admin__tabs">
        <button
          type="button"
          role="tab"
          id="dhis2-tab-config"
          data-testid="dhis2-tab-config"
          aria-controls="dhis2-panel-config"
          [attr.aria-selected]="activeTab() === 'config'"
          [attr.tabindex]="activeTab() === 'config' ? 0 : -1"
          (click)="activeTab.set('config')"
        >
          {{ 'DHIS2.ADMIN.TAB_CONFIG' | translate }}
        </button>
        <button
          type="button"
          role="tab"
          id="dhis2-tab-mappings"
          data-testid="dhis2-tab-mappings"
          aria-controls="dhis2-panel-mappings"
          [attr.aria-selected]="activeTab() === 'mappings'"
          [attr.tabindex]="activeTab() === 'mappings' ? 0 : -1"
          (click)="activeTab.set('mappings')"
        >
          {{ 'DHIS2.ADMIN.TAB_MAPPINGS' | translate }}
        </button>
        <button
          type="button"
          role="tab"
          id="dhis2-tab-exports"
          data-testid="dhis2-tab-exports"
          aria-controls="dhis2-panel-exports"
          [attr.aria-selected]="activeTab() === 'exports'"
          [attr.tabindex]="activeTab() === 'exports' ? 0 : -1"
          (click)="activeTab.set('exports')"
        >
          {{ 'DHIS2.ADMIN.TAB_EXPORTS' | translate }}
        </button>
      </nav>

      <section
        role="tabpanel"
        id="dhis2-panel-config"
        aria-labelledby="dhis2-tab-config"
        [hidden]="activeTab() !== 'config'"
      >
        <form
          class="dhis2-admin__config-form"
          (ngSubmit)="onSaveConfig()"
          data-testid="dhis2-config-form"
        >
          <label>
            {{ 'DHIS2.CONFIG.BASE_URL' | translate }}
            <input
              type="url"
              data-testid="dhis2-config-base-url"
              [(ngModel)]="configForm.baseUrl"
              name="baseUrl"
              required
            />
          </label>
          <label>
            {{ 'DHIS2.CONFIG.AUTH_MODE' | translate }}
            <select
              data-testid="dhis2-config-auth-mode"
              [(ngModel)]="configForm.authMode"
              name="authMode"
            >
              <option value="PAT">PAT</option>
              <option value="BASIC">BASIC</option>
            </select>
          </label>
          <label>
            {{ 'DHIS2.CONFIG.AUTH_SECRET_ENV_VAR' | translate }}
            <input
              type="text"
              data-testid="dhis2-config-auth-env-var"
              [(ngModel)]="configForm.authSecretEnvVar"
              name="authSecretEnvVar"
              required
              [pattern]="envVarPattern"
            />
          </label>
          <label>
            {{ 'DHIS2.CONFIG.DEFAULT_PERIOD_TYPE' | translate }}
            <select
              data-testid="dhis2-config-period-type"
              [(ngModel)]="configForm.defaultPeriodType"
              name="defaultPeriodType"
            >
              <option value="MONTHLY">MONTHLY</option>
              <option value="WEEKLY">WEEKLY</option>
              <option value="YEARLY">YEARLY</option>
            </select>
          </label>
          <label>
            {{ 'DHIS2.CONFIG.DEFAULT_DATASET_UID' | translate }}
            <input
              type="text"
              data-testid="dhis2-config-dataset-uid"
              [(ngModel)]="configForm.defaultDatasetUid"
              name="defaultDatasetUid"
              maxlength="11"
              [pattern]="uidPattern"
            />
          </label>
          <label class="dhis2-admin__inline-toggle">
            <input
              type="checkbox"
              data-testid="dhis2-config-active"
              [(ngModel)]="configForm.active"
              name="active"
            />
            {{ 'DHIS2.CONFIG.ACTIVE' | translate }}
          </label>
          <button
            type="submit"
            class="btn-primary"
            data-testid="dhis2-config-save"
            [disabled]="!canSaveConfig() || saving()"
          >
            {{ 'COMMON.SAVE' | translate }}
          </button>

          <p
            *ngIf="loadedConfig() as cfg"
            class="dhis2-admin__last-export"
            data-testid="dhis2-config-last-export"
          >
            {{ 'DHIS2.CONFIG.LAST_EXPORT' | translate }}:
            {{ cfg.lastExportAt ? (cfg.lastExportAt | date: 'short') : '—' }}
            ·
            {{ 'DHIS2.CONFIG.SECRET_CONFIGURED' | translate }}:
            {{ cfg.authSecretConfigured ? '✓' : '✗' }}
          </p>
        </form>
      </section>

      <section
        role="tabpanel"
        id="dhis2-panel-mappings"
        aria-labelledby="dhis2-tab-mappings"
        [hidden]="activeTab() !== 'mappings'"
      >
        <app-dhis2-mapping-editor *ngIf="activeTab() === 'mappings'" />
      </section>

      <section
        role="tabpanel"
        id="dhis2-panel-exports"
        aria-labelledby="dhis2-tab-exports"
        [hidden]="activeTab() !== 'exports'"
      >
        <app-dhis2-export-panel *ngIf="activeTab() === 'exports'" />
      </section>
    </section>
  `,
  styles: [
    `
      .dhis2-admin {
        padding: 1rem;
      }
      .dhis2-admin__tabs {
        display: flex;
        gap: 0.5rem;
        border-bottom: 1px solid var(--border, #ccc);
      }
      .dhis2-admin__tabs button {
        background: none;
        border: 0;
        padding: 0.5rem 0.75rem;
        cursor: pointer;
      }
      .dhis2-admin__tabs button[aria-selected='true'] {
        font-weight: 600;
        border-bottom: 2px solid var(--primary, #1a73e8);
      }
      .dhis2-admin__config-form {
        display: flex;
        flex-wrap: wrap;
        gap: 1rem;
        align-items: flex-end;
        padding: 1.5rem;
      }
      .dhis2-admin__config-form label {
        display: flex;
        flex-direction: column;
        font-size: 0.875rem;
      }
      .dhis2-admin__inline-toggle {
        flex-direction: row !important;
        align-items: center;
        gap: 0.5rem;
      }
      .dhis2-admin__last-export {
        flex-basis: 100%;
        font-size: 0.875rem;
        color: var(--text-secondary, #5f6368);
      }
    `,
  ],
})
export class Dhis2AdminPageComponent implements OnInit {
  protected readonly activeTab = signal<Tab>('config');
  protected readonly loadedConfig = signal<Dhis2FacilityConfig | null>(null);
  protected readonly saving = signal(false);

  protected configForm: Dhis2FacilityConfigRequest = {
    baseUrl: '',
    authMode: 'PAT' as Dhis2AuthMode,
    authSecretEnvVar: '',
    defaultPeriodType: 'MONTHLY' as Dhis2PeriodType,
    defaultDatasetUid: null,
    active: true,
  };

  protected readonly uidPattern = DHIS2_UID_REGEX.source;
  protected readonly envVarPattern = ENV_VAR_REGEX.source;

  private readonly dhis2 = inject(Dhis2Service);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);

  ngOnInit(): void {
    const hospitalId = this.roleContext.activeHospitalId;
    if (!hospitalId) return;
    this.dhis2.getFacilityConfig(hospitalId).subscribe({
      next: (cfg) => {
        this.loadedConfig.set(cfg);
        this.configForm = {
          baseUrl: cfg.baseUrl,
          authMode: cfg.authMode,
          authSecretEnvVar: cfg.authSecretEnvVar,
          defaultPeriodType: cfg.defaultPeriodType,
          defaultDatasetUid: cfg.defaultDatasetUid,
          active: cfg.active,
        };
      },
      // 404 just means "not configured yet" — leave the empty form.
      error: () => this.loadedConfig.set(null),
    });
  }

  protected canSaveConfig(): boolean {
    return (
      !!this.configForm.baseUrl &&
      ENV_VAR_REGEX.test(this.configForm.authSecretEnvVar) &&
      this.roleContext.activeHospitalId !== null
    );
  }

  protected onSaveConfig(): void {
    const hospitalId = this.roleContext.activeHospitalId;
    if (!hospitalId || !this.canSaveConfig() || this.saving()) return;
    this.saving.set(true);
    this.dhis2.upsertFacilityConfig(hospitalId, this.configForm).subscribe({
      next: (cfg) => {
        this.loadedConfig.set(cfg);
        this.saving.set(false);
        this.toast.success('DHIS2 facility config saved');
      },
      error: () => {
        this.saving.set(false);
        this.toast.error('Could not save DHIS2 facility config');
      },
    });
  }
}
