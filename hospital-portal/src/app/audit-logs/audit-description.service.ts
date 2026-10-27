import { Injectable, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { EnumLabelService } from '../core/enum-label.service';
import { bareRole } from '../core/role-token';
import {
  AUDIT_DESCRIPTION_PATTERNS,
  AuditDescriptionPattern,
  AuditParamDomain,
} from './audit-description-patterns';

/** QA D330: the write audit's "METHOD /route" description, read as an action. */
const WRITE_DESCRIPTION = /^(POST|PUT|PATCH|DELETE)\s+\/\S*$/;
const WRITE_ACTION_KEYS: Readonly<Record<string, string>> = {
  POST: 'AUDIT.WRITE_POST',
  PUT: 'AUDIT.WRITE_PUT',
  PATCH: 'AUDIT.WRITE_PATCH',
  DELETE: 'AUDIT.WRITE_DELETE',
};
/** A raw enum token in free text: ROLE_PATIENT, SUBMIT_FOR_QA, PENDING_QA_REVIEW. */
const RAW_TOKEN = /\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g;
const CODE = /^[A-Z][A-Z0-9_]*$/;

/**
 * QA D905: before #968 a lab approval row was written in the approver's
 * language with the status as a label, not a code ("…nouveau statut Actif."),
 * so ES and EN readers saw the French word. These are the server's labels
 * (lab.definition.status.* in the three bundles), read back as their code.
 */
const LEGACY_LAB_APPROVAL_STATUS: Readonly<Record<string, string>> = {
  draft: 'DRAFT',
  brouillon: 'DRAFT',
  borrador: 'DRAFT',
  'awaiting qa review': 'PENDING_QA_REVIEW',
  'en attente de revue qualité': 'PENDING_QA_REVIEW',
  'pendiente de revisión de calidad': 'PENDING_QA_REVIEW',
  'awaiting director approval': 'PENDING_DIRECTOR_APPROVAL',
  "en attente d'approbation du directeur": 'PENDING_DIRECTOR_APPROVAL',
  'pendiente de aprobación del director': 'PENDING_DIRECTOR_APPROVAL',
  approved: 'APPROVED',
  approuvé: 'APPROVED',
  aprobado: 'APPROVED',
  active: 'ACTIVE',
  actif: 'ACTIVE',
  activo: 'ACTIVE',
  rejected: 'REJECTED',
  rejeté: 'REJECTED',
  rechazado: 'REJECTED',
  retired: 'RETIRED',
  retiré: 'RETIRED',
  retirado: 'RETIRED',
};

/** A stored lab approval status, label or code, as its code. */
function labApprovalCode(value: string): string {
  const key = value.trim().toLowerCase().replaceAll('’', "'");
  return LEGACY_LAB_APPROVAL_STATUS[key] ?? value;
}

/** The parts of an audit row its description is read from. */
export interface DescribableAuditRow {
  eventDescription?: string | null;
  entityType?: string | null;
}

/**
 * QA D774 / D894: an audit description in the reader's language, for every
 * screen that shows one (the audit log, the profile's activity). The server
 * writes them in English; {@link AUDIT_DESCRIPTION_PATTERNS} reads each shape.
 */
@Injectable({ providedIn: 'root' })
export class AuditDescriptionService {
  private readonly translate = inject(TranslateService);
  private readonly enumLabels = inject(EnumLabelService);

  /**
   * QA D330: the entity in the reader's language. Rows carry it as an enum
   * (LAB_RESULT) or a class name (LabTestDefinition); both read the same.
   */
  entityLabel(entityType: string | null | undefined): string {
    if (!entityType) return '--';
    const code = entityType.replaceAll(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
    return this.enumLabels.transform(code, 'auditEntityType');
  }

  describe(row: DescribableAuditRow): string {
    const description = (row.eventDescription ?? '').trim();
    // QA D330: a generic write row reads as the action and the entity; the
    // route stays in the tooltip.
    const write = WRITE_DESCRIPTION.exec(description);
    if (write) {
      const entity = row.entityType
        ? this.entityLabel(row.entityType)
        : this.translate.instant('AUDIT.WRITE_ENTITY_UNKNOWN');
      return this.translate.instant(WRITE_ACTION_KEYS[write[1]], { entity });
    }
    if (!description) return '--';
    for (const pattern of AUDIT_DESCRIPTION_PATTERNS) {
      const match = pattern.re.exec(description);
      if (match) return this.render(pattern, match);
    }
    return description.replaceAll(RAW_TOKEN, (token) => this.label(token));
  }

  private render(pattern: AuditDescriptionPattern, match: RegExpExecArray): string {
    const values: Record<string, string> = {};
    (pattern.params ?? []).forEach((name, i) => {
      const raw = match[i + 1] ?? '';
      const domain = pattern.localise?.[name];
      values[name] = domain ? this.localise(raw, domain) : raw;
    });
    const text: unknown = this.translate.instant(pattern.key, values);
    return typeof text === 'string' ? text.replaceAll(/\s{2,}/g, ' ').trim() : match[0];
  }

  private localise(value: string, domain: AuditParamDomain): string {
    if (domain === 'labApprovalStatus') {
      return this.lookup(labApprovalCode(value), 'LAB.APPROVAL_STATUS', true);
    }
    // QA D869: an unknown refusal reason stays verbatim.
    if (domain === 'scopeRefusal') return this.lookup(value, 'AUDIT.SCOPE_REFUSAL', false);
    if (domain === 'enum') {
      // A code with no portal-wide label (CHW, PRIOR_AUTH…) is read under AUDIT.CODE.
      const label = this.label(value);
      return label === value ? this.lookup(value, 'AUDIT.CODE', true) : label;
    }
    if (domain.startsWith('phrase:')) return this.phrase(value, domain.slice('phrase:'.length));
    return this.label(value, domain);
  }

  /** A code under `group`; an unknown code (or, when `codesOnly`, anything not a code) stays verbatim. */
  private lookup(value: string, group: string, codesOnly: boolean): string {
    if (codesOnly && !CODE.test(value)) return value;
    const key = `${group}.${value}`;
    const label: unknown = this.translate.instant(key);
    return typeof label === 'string' && label !== key ? label : value;
  }

  /**
   * QA D894: a fixed English phrase the server writes inside a description
   * ("emergency broadcast", "lab result") under AUDIT.<group>.<UPPER_SNAKE>.
   * An empty value reads as nothing; an unknown phrase stays verbatim.
   */
  private phrase(value: string, group: string): string {
    const slug = value
      .toUpperCase()
      .replaceAll(/[^A-Z0-9]+/g, ' ')
      .trim()
      .replaceAll(' ', '_');
    if (!slug) return '';
    const key = `AUDIT.${group}.${slug}`;
    const label: unknown = this.translate.instant(key);
    return typeof label === 'string' && label !== key ? label : value;
  }

  /**
   * QA D774: a role or enum token in the reader's language. Only a real label
   * replaces it: when the lookup would merely prettify (GLU -> "Glu",
   * HB_A1C -> "Hb A1c"), the token is an identifier and is kept verbatim.
   */
  private label(value: string, domain?: string): string {
    if (!CODE.test(value)) return value;
    const role = value.startsWith('ROLE_');
    const code = (role ? bareRole(value) : value) ?? value;
    const group = role ? 'role' : domain;
    const label = this.enumLabels.transform(code, group);
    const prettified = code
      .split('_')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
      .join(' ');
    return label && label !== prettified ? label : value;
  }
}
