/**
 * External provider facilities: private pharmacies and laboratories
 * (provider plan D5, P1). Mirrors the hospital-core DTOs under
 * `payload/dto/provider/`.
 */
import { NotificationDeliveryStatus } from '../shared/delivery-warnings';

export type FacilityType = 'HOSPITAL' | 'PHARMACY' | 'LABORATORY';

/** The two types a provider facility may have; HOSPITAL is refused by the backend. */
export type ProviderFacilityType = 'PHARMACY' | 'LABORATORY';

export const PROVIDER_FACILITY_TYPES: ProviderFacilityType[] = ['PHARMACY', 'LABORATORY'];

/** `HospitalLifecycleState` as the backend sends it (its scheduled-purge value is PENDING_PURGE). */
export type ProviderLifecycleState =
  'ACTIVE' | 'SUSPENDED' | 'ARCHIVED' | 'PENDING_PURGE' | 'PURGED';

export type ProviderVerificationStatus = 'SUBMITTED' | 'VERIFIED' | 'REJECTED' | 'REVOKED';

export const PROVIDER_VERIFICATION_STATUSES: ProviderVerificationStatus[] = [
  'SUBMITTED',
  'VERIFIED',
  'REJECTED',
  'REVOKED',
];

export interface ProviderAddress {
  secteur?: string | null;
  section?: string | null;
  lot?: string | null;
  parcelle?: string | null;
  city: string;
  region: string;
}

/** Legal identity of the business: the RCCM extract is authoritative, IFU and CNSS must agree. */
export interface ProviderBusinessIdentity {
  legalName: string;
  tradeName?: string | null;
  legalStructure: string;
  rccmNumber: string;
  ifuNumber: string;
  cnssNumber: string;
  address: ProviderAddress;
  companyPhone: string;
  managerName: string;
  managerTitle: string;
  /** ISO date (yyyy-MM-dd). */
  startedOn: string;
}

/** The licence or ministry authorisation, and the responsible professional. No expiry is ever required. */
export interface ProviderProfessional {
  licenceNumber: string;
  licenceAuthority: string;
  licenceIssuedOn?: string | null;
  licenceExpiresOn?: string | null;
  responsibleName: string;
  responsibleOrdreNumber: string;
}

/** Both evidence layers, as the create, resubmit and correction forms edit them. */
export interface ProviderEvidence {
  business: ProviderBusinessIdentity;
  professional: ProviderProfessional;
}

export interface ProviderCreateRequest extends ProviderEvidence {
  facilityType: ProviderFacilityType;
  code: string;
  email?: string | null;
}

export type ProviderResubmitRequest = ProviderEvidence;

export interface ProviderVerifyRequest {
  ifuMatchesRccm: boolean;
  cnssMatchesRccm: boolean;
  corrections?: ProviderEvidence | null;
  evidenceNote?: string | null;
}

export interface ProviderDecisionRequest {
  reason: string;
}

export interface ProviderResponse {
  id: string;
  facilityType: FacilityType;
  code: string;
  name: string | null;
  email: string | null;
  active: boolean;
  lifecycleState: ProviderLifecycleState | null;
  verificationId: string | null;
  verificationStatus: ProviderVerificationStatus | null;
  business: ProviderBusinessIdentity | null;
  professional: ProviderProfessional | null;
  ifuMatchesRccm: boolean;
  cnssMatchesRccm: boolean;
  evidenceNote: string | null;
  decidedByUserId: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
  submittedAt: string | null;
}

/** Spring Data page of providers. */
export interface ProviderPage {
  content: ProviderResponse[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface ProviderVerificationHistoryEntry {
  verificationId: string;
  status: ProviderVerificationStatus;
  submittedAt: string | null;
  decidedAt: string | null;
  decidedByUserId: string | null;
  decisionReason: string | null;
  evidenceNote: string | null;
  legalName: string | null;
  licenceNumber: string | null;
  licenceAuthority: string | null;
}

/** GET /provider/settings: what the provider shell needs. */
export interface ProviderSettings {
  facilityId: string;
  facilityType: FacilityType;
  providerAdmin: boolean;
  organisationsEnabled: boolean;
}

/** GET /provider/profile: operational contact (editable by the admin) and verified identity (read-only). */
export interface ProviderProfile {
  id: string;
  facilityType: FacilityType;
  code: string;
  phoneNumber: string | null;
  email: string | null;
  website: string | null;
  name: string | null;
  address: string | null;
  city: string | null;
  region: string | null;
  legalName: string | null;
  tradeName: string | null;
  licenceNumber: string | null;
  licenceAuthority: string | null;
  companyPhone: string | null;
  verifiedAt: string | null;
  verificationStatus: ProviderVerificationStatus | null;
  editable: boolean;
}

export interface ProviderProfileUpdate {
  phoneNumber: string;
  email?: string | null;
  website?: string | null;
}

export interface ProviderStaffMember {
  userId: string;
  username: string;
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  roles: string[];
  active: boolean;
  invitationPending: boolean;
  providerAdmin: boolean;
  self: boolean;
  activationDelivery?: NotificationDeliveryStatus[] | null;
}

export interface ProviderAuditEntry {
  id: string;
  eventTimestamp: string;
  eventType: string;
  status: string | null;
  actorUserId: string | null;
  actorName: string | null;
  roleName: string | null;
  entityType: string | null;
  resourceId: string | null;
}

export interface ProviderAuditPage {
  entries: ProviderAuditEntry[];
  page: number;
  size: number;
  totalElements: number;
  hasMore: boolean;
}

/**
 * The staff roles a provider of each type may hold (plan §3.2), bare, without
 * PROVIDER_ADMIN (only the platform grants it). Mirrors
 * `RoleFacilityCompatibility` on the backend; the portal uses it for the role
 * picker only, the backend enforces it.
 */
export const PROVIDER_STAFF_ROLES: Record<ProviderFacilityType, string[]> = {
  PHARMACY: ['PHARMACIST'],
  LABORATORY: ['LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'LAB_DIRECTOR'],
};

/** Every role a provider user may hold, as token roles: the shell loads the provider settings only for them. */
export const PROVIDER_CAPABLE_ROLES: string[] = [
  'ROLE_PROVIDER_ADMIN',
  'ROLE_PHARMACIST',
  'ROLE_LAB_TECHNICIAN',
  'ROLE_LAB_SCIENTIST',
  'ROLE_LAB_MANAGER',
  'ROLE_LAB_DIRECTOR',
];

export function isProviderFacilityType(
  type: FacilityType | null | undefined,
): type is ProviderFacilityType {
  return type === 'PHARMACY' || type === 'LABORATORY';
}

/** An empty evidence pair for the create form. */
export function emptyEvidence(): ProviderEvidence {
  return {
    business: {
      legalName: '',
      tradeName: '',
      legalStructure: '',
      rccmNumber: '',
      ifuNumber: '',
      cnssNumber: '',
      address: { secteur: '', section: '', lot: '', parcelle: '', city: '', region: '' },
      companyPhone: '',
      managerName: '',
      managerTitle: '',
      startedOn: '',
    },
    professional: {
      licenceNumber: '',
      licenceAuthority: '',
      licenceIssuedOn: '',
      licenceExpiresOn: '',
      responsibleName: '',
      responsibleOrdreNumber: '',
    },
  };
}

/** A copy of a provider's current evidence, for the resubmit and correction forms. */
export function evidenceOf(provider: ProviderResponse): ProviderEvidence {
  const blank = emptyEvidence();
  const b = provider.business;
  const p = provider.professional;
  return {
    business: b
      ? {
          ...blank.business,
          ...b,
          tradeName: b.tradeName ?? '',
          address: {
            ...blank.business.address,
            ...b.address,
            secteur: b.address?.secteur ?? '',
            section: b.address?.section ?? '',
            lot: b.address?.lot ?? '',
            parcelle: b.address?.parcelle ?? '',
          },
        }
      : blank.business,
    professional: p
      ? {
          ...blank.professional,
          ...p,
          licenceIssuedOn: p.licenceIssuedOn ?? '',
          licenceExpiresOn: p.licenceExpiresOn ?? '',
        }
      : blank.professional,
  };
}

function blankToNull(value: string | null | undefined): string | null {
  const trimmed = (value ?? '').trim();
  return trimmed === '' ? null : trimmed;
}

function trimmed(value: string | null | undefined): string {
  return (value ?? '').trim();
}

/** The evidence as the API takes it: trimmed, optional blanks sent as null. */
export function normaliseEvidence(evidence: ProviderEvidence): ProviderEvidence {
  const b = evidence.business;
  const p = evidence.professional;
  return {
    business: {
      legalName: trimmed(b.legalName),
      tradeName: blankToNull(b.tradeName),
      legalStructure: trimmed(b.legalStructure),
      rccmNumber: trimmed(b.rccmNumber),
      ifuNumber: trimmed(b.ifuNumber),
      cnssNumber: trimmed(b.cnssNumber),
      address: {
        secteur: blankToNull(b.address.secteur),
        section: blankToNull(b.address.section),
        lot: blankToNull(b.address.lot),
        parcelle: blankToNull(b.address.parcelle),
        city: trimmed(b.address.city),
        region: trimmed(b.address.region),
      },
      companyPhone: trimmed(b.companyPhone),
      managerName: trimmed(b.managerName),
      managerTitle: trimmed(b.managerTitle),
      startedOn: trimmed(b.startedOn),
    },
    professional: {
      licenceNumber: trimmed(p.licenceNumber),
      licenceAuthority: trimmed(p.licenceAuthority),
      licenceIssuedOn: blankToNull(p.licenceIssuedOn),
      licenceExpiresOn: blankToNull(p.licenceExpiresOn),
      responsibleName: trimmed(p.responsibleName),
      responsibleOrdreNumber: trimmed(p.responsibleOrdreNumber),
    },
  };
}

/** Every field the backend requires (`@NotBlank` / `@NotNull`) is filled; the optional ones are not checked. */
export function isEvidenceComplete(evidence: ProviderEvidence): boolean {
  const b = evidence.business;
  const p = evidence.professional;
  const required = [
    b.legalName,
    b.legalStructure,
    b.rccmNumber,
    b.ifuNumber,
    b.cnssNumber,
    b.address.city,
    b.address.region,
    b.companyPhone,
    b.managerName,
    b.managerTitle,
    b.startedOn,
    p.licenceNumber,
    p.licenceAuthority,
    p.responsibleName,
    p.responsibleOrdreNumber,
  ];
  return required.every((value) => trimmed(value) !== '');
}

/**
 * The message the server sent with a refusal, when it sent one (the backend
 * localises it from Accept-Language); `null` otherwise, so the caller falls
 * back to its own translated text.
 */
export function serverMessage(error: unknown): string | null {
  const body = (error as { error?: unknown } | null)?.error;
  if (body && typeof body === 'object') {
    const message = (body as { message?: unknown }).message;
    if (typeof message === 'string' && message.trim() !== '') {
      return message;
    }
  }
  return null;
}

/** True for the backend's "not available here" answer: a 404 identical to an unmapped path. */
export function isNotAvailable(error: unknown): boolean {
  return (error as { status?: unknown } | null)?.status === 404;
}
