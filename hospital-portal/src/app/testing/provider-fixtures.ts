import { ProviderResponse, ProviderSettings } from '../services/provider.model';

/** A submitted pharmacy, as GET /super-admin/providers/{id} returns it. Test data only. */
export function providerFixture(overrides: Partial<ProviderResponse> = {}): ProviderResponse {
  return {
    id: 'p-1',
    facilityType: 'PHARMACY',
    code: 'PH-1',
    name: 'Pharmacie du Marché',
    email: null,
    active: false,
    lifecycleState: 'SUSPENDED',
    verificationId: 'v-1',
    verificationStatus: 'SUBMITTED',
    business: {
      legalName: 'Pharmacie du Marché SARL',
      tradeName: null,
      legalStructure: 'SARL',
      rccmNumber: 'BF-OUA-1',
      ifuNumber: 'IFU-1',
      cnssNumber: 'CNSS-1',
      address: {
        secteur: '12',
        section: null,
        lot: null,
        parcelle: null,
        city: 'Ouagadougou',
        region: 'Centre',
      },
      companyPhone: '+22625000000',
      managerName: 'Awa',
      managerTitle: 'Gérante',
      startedOn: '2019-03-01',
    },
    professional: {
      licenceNumber: 'LIC-77',
      licenceAuthority: 'DGPML',
      licenceIssuedOn: null,
      licenceExpiresOn: null,
      responsibleName: 'Dr Issa',
      responsibleOrdreNumber: 'ONP-1',
    },
    ifuMatchesRccm: false,
    cnssMatchesRccm: false,
    evidenceNote: null,
    decidedByUserId: null,
    decidedAt: null,
    decisionReason: null,
    submittedAt: '2026-10-08T09:00:00',
    ...overrides,
  };
}

/** The shell settings of a pharmacy's PROVIDER_ADMIN. Test data only. */
export function settingsFixture(overrides: Partial<ProviderSettings> = {}): ProviderSettings {
  return {
    facilityId: 'f-1',
    facilityType: 'PHARMACY',
    providerAdmin: true,
    organisationsEnabled: false,
    ...overrides,
  };
}
