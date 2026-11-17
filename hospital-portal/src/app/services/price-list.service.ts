import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Billing-money plan PR 2 (scorecard M1): the hospital's price list
 * ("grille tarifaire") at /billing/price-list. Bare DTOs, no wrapper. A price
 * changes only by adding a version (POST …/{id}/prices): there is no endpoint
 * that edits or deletes one. Writes are for the hospital admin and the
 * accountant only (owner decision D-1).
 */

export type PriceListCategory =
  | 'CONSULTATION'
  | 'LAB_TEST'
  | 'IMAGING'
  | 'PROCEDURE'
  | 'BED_DAY'
  | 'TRANSFUSION'
  | 'DELIVERY'
  | 'OTHER_SERVICE';

export const PRICE_LIST_CATEGORIES: PriceListCategory[] = [
  'CONSULTATION',
  'LAB_TEST',
  'IMAGING',
  'PROCEDURE',
  'BED_DAY',
  'TRANSFUSION',
  'DELIVERY',
  'OTHER_SERVICE',
];

export type PriceListLinkType =
  | 'SYSTEM'
  | 'LAB_TEST_DEFINITION'
  | 'PROCEDURE_CODE'
  | 'IMAGING_STUDY'
  | 'TRANSFUSION_PRODUCT'
  | 'TREATMENT';

export const PRICE_LIST_LINK_TYPES: PriceListLinkType[] = [
  'LAB_TEST_DEFINITION',
  'PROCEDURE_CODE',
  'IMAGING_STUDY',
  'TRANSFUSION_PRODUCT',
  'SYSTEM',
  'TREATMENT',
];

export type PriceListPriceStatus = 'SCHEDULED' | 'IN_FORCE' | 'ENDED' | 'REPLACED';

export interface PriceListPrice {
  id: string;
  unitPrice: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  status: PriceListPriceStatus;
  changeReason?: string | null;
  createdAt?: string | null;
  createdByName?: string | null;
}

export interface PriceListItem {
  id: string;
  hospitalId: string;
  code: string;
  /** As the hospital typed it: never translated. */
  label: string;
  category: PriceListCategory;
  linkType?: PriceListLinkType | null;
  linkKey?: string | null;
  linkLabel?: string | null;
  active: boolean;
  currentPrice?: PriceListPrice | null;
  nextPrice?: PriceListPrice | null;
  createdAt?: string | null;
  createdByName?: string | null;
  updatedAt?: string | null;
  updatedByName?: string | null;
  history?: PriceListPrice[] | null;
}

export interface PriceListLinkOption {
  id: string;
  code?: string | null;
  name?: string | null;
}

export interface PriceListLinkOptions {
  systemKeys: string[];
  transfusionProducts: string[];
  imagingModalities: string[];
  labTests: PriceListLinkOption[];
  treatments: PriceListLinkOption[];
}

export interface PriceListItemRequest {
  code: string;
  label: string;
  category: PriceListCategory | null;
  linkType: PriceListLinkType | null;
  linkKey: string | null;
  unitPrice: number | null;
  effectiveFrom: string | null;
  changeReason: string | null;
}

export interface PriceListItemUpdateRequest {
  label: string;
  category: PriceListCategory | null;
  linkType: PriceListLinkType | null;
  linkKey: string | null;
}

export interface PriceListPriceRequest {
  unitPrice: number | null;
  effectiveFrom: string | null;
  changeReason: string;
  replaceScheduled: boolean;
}

/** Billing-money plan PR 4: what one row of an imported file does (or would do, on a dry run). */
export type PriceListImportRowOutcome = 'NEW_ITEM' | 'NEW_PRICE' | 'UNCHANGED' | 'REFUSED';

/** What the import call did: a check, an apply, an apply refused as a whole, or a file applied before. */
export type PriceListImportResult = 'DRY_RUN' | 'APPLIED' | 'REFUSED' | 'ALREADY_IMPORTED';

export interface PriceListImportRefusal {
  /** The CSV column (code, libelle, categorie, prix, date_effet, liaison_type, liaison_cle); null for the row. */
  column: string | null;
  /** In the reader's language, from the server. */
  message: string;
}

export interface PriceListImportRow {
  /** The line in the file, the header being line 1. */
  line: number;
  code: string;
  label: string;
  outcome: PriceListImportRowOutcome;
  price?: number | null;
  effectiveDate?: string | null;
  previousPrice?: number | null;
  refusals: PriceListImportRefusal[];
}

export interface PriceListImportReport {
  importId?: string | null;
  fileName: string;
  sha256: string;
  result: PriceListImportResult;
  alreadyImported: boolean;
  alreadyImportedAt?: string | null;
  totalRows: number;
  newItems: number;
  newPrices: number;
  unchanged: number;
  refused: number;
  rows: PriceListImportRow[];
}

/** The import's header, in the order the export writes it (the downloadable template). */
export const PRICE_LIST_CSV_HEADER =
  'code;libelle;categorie;prix;date_effet;liaison_type;liaison_cle';

@Injectable({ providedIn: 'root' })
export class PriceListService {
  private readonly http = inject(HttpClient);
  private readonly base = '/billing/price-list';

  list(category: PriceListCategory | null, includeInactive: boolean): Observable<PriceListItem[]> {
    let params = new HttpParams().set('includeInactive', String(includeInactive));
    if (category) params = params.set('category', category);
    return this.http.get<PriceListItem[]>(this.base, { params });
  }

  get(id: string): Observable<PriceListItem> {
    return this.http.get<PriceListItem>(`${this.base}/${id}`);
  }

  linkOptions(): Observable<PriceListLinkOptions> {
    return this.http.get<PriceListLinkOptions>(`${this.base}/link-options`);
  }

  create(body: PriceListItemRequest): Observable<PriceListItem> {
    return this.http.post<PriceListItem>(this.base, body);
  }

  update(id: string, body: PriceListItemUpdateRequest): Observable<PriceListItem> {
    return this.http.put<PriceListItem>(`${this.base}/${id}`, body);
  }

  addPrice(id: string, body: PriceListPriceRequest): Observable<PriceListItem> {
    return this.http.post<PriceListItem>(`${this.base}/${id}/prices`, body);
  }

  /** Ends the item with the reason the reader gave (kept with the history). */
  deactivate(id: string, reason: string): Observable<PriceListItem> {
    return this.http.post<PriceListItem>(`${this.base}/${id}/deactivate`, { reason });
  }

  /**
   * PR 4: checks (dryRun) or applies a CSV file. An apply that refused a row answers 422 with the same report
   * as its body, which the caller reads from the error.
   */
  importCsv(
    file: File,
    dryRun: boolean,
    replaceScheduled: boolean,
  ): Observable<PriceListImportReport> {
    const body = new FormData();
    body.append('file', file, file.name);
    const params = new HttpParams()
      .set('dryRun', String(dryRun))
      .set('replaceScheduled', String(replaceScheduled));
    return this.http.post<PriceListImportReport>(`${this.base}/import`, body, { params });
  }

  /** PR 4: the acting hospital's grid in the import's format; the whole response keeps the server's file name. */
  exportCsv(): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/export.csv`, { responseType: 'blob', observe: 'response' });
  }
}
