import {
  Component,
  inject,
  OnInit,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AuditLogService,
  AuditEventLog,
  AuditEventTypeStatus,
} from '../services/audit-log.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { RoleLabelPipe } from '../shared/pipes/role-label.pipe';
import { EnumLabelService } from '../core/enum-label.service';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { bareRole } from '../core/role-token';

/** QA D330: the write audit's "METHOD /route" description, read as an action. */
const WRITE_DESCRIPTION = /^(POST|PUT|PATCH|DELETE)\s+\/\S*$/;
const WRITE_ACTION_KEYS: Readonly<Record<string, string>> = {
  POST: 'AUDIT.WRITE_POST',
  PUT: 'AUDIT.WRITE_PUT',
  PATCH: 'AUDIT.WRITE_PATCH',
  DELETE: 'AUDIT.WRITE_DELETE',
};

/**
 * QA D774: audit descriptions are stored as the emitter wrote them, mostly in
 * English with raw codes. The common ones are read in the reader's language;
 * the parameters (a role code, a person, a test code) are kept.
 */
interface DescriptionPattern {
  readonly re: RegExp;
  readonly key: string;
  readonly params?: (m: RegExpExecArray) => Record<string, string>;
}
const DESCRIPTION_PATTERNS: readonly DescriptionPattern[] = [
  { re: /^Patient record viewed$/, key: 'AUDIT.DESC.PATIENT_RECORD_VIEWED' },
  { re: /^User profile updated$/, key: 'AUDIT.DESC.USER_PROFILE_UPDATED' },
  { re: /^New user account created$/, key: 'AUDIT.DESC.USER_CREATED' },
  { re: /^User account soft-deleted$/, key: 'AUDIT.DESC.USER_DELETED' },
  { re: /^User account restored$/, key: 'AUDIT.DESC.USER_RESTORED' },
  { re: /^Doctor record view$/, key: 'AUDIT.DESC.DOCTOR_RECORD_VIEW' },
  { re: /^Doctor timeline view$/, key: 'AUDIT.DESC.DOCTOR_TIMELINE_VIEW' },
  { re: /^MFA challenge issued$/, key: 'AUDIT.DESC.MFA_CHALLENGE_ISSUED' },
  { re: /^TOTP enrollment verified$/, key: 'AUDIT.DESC.TOTP_ENROLLMENT_VERIFIED' },
  { re: /^First sign-in setup completed$/, key: 'AUDIT.DESC.FIRST_SIGN_IN_COMPLETED' },
  {
    re: /^Role assignment refused: the caller may not grant this role there$/,
    key: 'AUDIT.DESC.ROLE_ASSIGNMENT_REFUSED',
  },
  {
    re: /^Assigned role '([^']+)' to (.+)$/,
    key: 'AUDIT.DESC.ROLE_ASSIGNED',
    params: (m) => ({ role: m[1], name: m[2] }),
  },
  {
    re: /^Confirmed assignment '([^']+)' for (.+)$/,
    key: 'AUDIT.DESC.ASSIGNMENT_CONFIRMED',
    params: (m) => ({ role: m[1], name: m[2] }),
  },
  {
    re: /^Lab test definition (\S+): approval step (\S+), new status (.+?)\.$/,
    key: 'AUDIT.DESC.LAB_DEFINITION_STEP',
    params: (m) => ({ code: m[1], step: m[2], status: m[3] }),
  },
];
/** A raw enum token in free text: ROLE_PATIENT, SUBMIT_FOR_QA, PENDING_QA_REVIEW. */
const RAW_TOKEN = /\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g;

@Component({
  selector: 'app-audit-logs',
  standalone: true,
  imports: [
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    RoleLabelPipe,
  ],
  templateUrl: './audit-logs.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './audit-logs.scss',
})
export class AuditLogsComponent implements OnInit {
  private readonly auditService = inject(AuditLogService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly enumLabels = inject(EnumLabelService);

  readonly pageSize = 20;

  /**
   * QA D330: the entity in the reader's language. Rows carry it as an enum
   * (LAB_RESULT) or a class name (LabTestDefinition); both read the same.
   */
  entityLabel(entityType: string | null | undefined): string {
    if (!entityType) return '--';
    const code = entityType.replaceAll(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
    return this.enumLabels.transform(code, 'auditEntityType');
  }

  /**
   * QA D330: a generic write row is described as "POST /api/..."; it reads
   * as the action and the entity, and the route stays in the tooltip.
   */
  describe(log: AuditEventLog): string {
    const description = (log.eventDescription ?? '').trim();
    const match = WRITE_DESCRIPTION.exec(description);
    if (match) {
      const entity = log.entityType
        ? this.entityLabel(log.entityType)
        : this.translate.instant('AUDIT.WRITE_ENTITY_UNKNOWN');
      return this.translate.instant(WRITE_ACTION_KEYS[match[1]], { entity });
    }
    if (!description) return '--';
    for (const p of DESCRIPTION_PATTERNS) {
      const m = p.re.exec(description);
      if (m) {
        const params = p.params ? p.params(m) : {};
        const localised: Record<string, string> = {};
        for (const [k, v] of Object.entries(params)) localised[k] = this.label(v);
        return this.translate.instant(p.key, localised);
      }
    }
    return description.replaceAll(RAW_TOKEN, (token) => this.label(token));
  }

  /** QA D774: a role or enum token in the reader's language; other text as it is. */
  private label(value: string): string {
    if (!/^[A-Z][A-Z0-9_]*$/.test(value)) return value;
    if (value.startsWith('ROLE_')) {
      return this.enumLabels.transform(bareRole(value), 'role') || value;
    }
    return this.enumLabels.transform(value) || value;
  }

  logs = signal<AuditEventLog[]>([]);
  eventTypes = signal<AuditEventTypeStatus[]>([]);
  loading = signal(true);
  currentPage = signal(0);
  totalElements = signal(0);
  totalPages = signal(0);

  searchTerm = '';
  selectedEventType = '';
  fromDate = '';
  toDate = '';

  filtered = computed(() => {
    let list = this.logs();
    if (this.selectedEventType) {
      list = list.filter((l) => l.eventType === this.selectedEventType);
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (l) =>
          (l.userName ?? '').toLowerCase().includes(term) ||
          (l.eventDescription ?? '').toLowerCase().includes(term) ||
          (l.entityType ?? '').toLowerCase().includes(term) ||
          (l.resourceName ?? '').toLowerCase().includes(term),
      );
    }
    return list;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.auditService
      .list({
        page: this.currentPage(),
        size: this.pageSize,
        fromDate: this.fromDate || undefined,
        toDate: this.toDate || undefined,
      })
      .subscribe({
        next: (res) => {
          this.logs.set(res?.content ?? []);
          this.totalElements.set(res?.totalElements ?? 0);
          this.totalPages.set(res?.totalPages ?? 0);
          this.loading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('AUDIT.LOAD_FAILED'));
          this.loading.set(false);
        },
      });
    this.auditService.getEventTypeStatus().subscribe({
      next: (types) => this.eventTypes.set(Array.isArray(types) ? types : []),
      error: () => {
        /* event types optional */
      },
    });
  }

  applyDateFilter(): void {
    this.currentPage.set(0);
    this.load();
  }

  clearDateFilter(): void {
    this.fromDate = '';
    this.toDate = '';
    this.currentPage.set(0);
    this.load();
  }

  onEventTypeChange(): void {
    // event type filter stays client-side within the current page
  }

  prevPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((p) => p - 1);
      this.load();
    }
  }

  nextPage(): void {
    if (this.currentPage() < this.totalPages() - 1) {
      this.currentPage.update((p) => p + 1);
      this.load();
    }
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'SUCCESS':
        return 'status-success';
      case 'FAILURE':
      case 'ERROR':
        return 'status-failure';
      default:
        return 'status-info';
    }
  }
}
