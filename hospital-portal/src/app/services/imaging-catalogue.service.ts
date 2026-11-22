import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ImagingModality } from './imaging.service';

/**
 * QA D3450 / D3254 (r70): one exam of the acting hospital's imaging catalogue (an active IMAGING
 * line of its price list), as `ImagingCatalogueController` returns it. No price: who orders an
 * exam does not need it.
 */
export interface ImagingCatalogueExam {
  id: string;
  code: string;
  label: string;
  /** From the exam's price-list link; null when the exam names no modality. */
  modality: ImagingModality | null;
  bodyRegion: string | null;
  /** What the hospital decided: true (never shown to a family proxy), false (cleared), null (nobody decided). */
  mayRevealPregnancy: boolean | null;
  /** What an undecided exam is taken for: its name or region names an obstetric, pelvic or gynaecological study. */
  namedAsPregnancyStudy: boolean;
  pregnancyFlagDecidedBy: string | null;
  pregnancyFlagDecidedAt: string | null;
  /** The caller is a hospital admin or a radiologist of the acting hospital. */
  canEdit: boolean;
}

export interface ImagingCatalogueExamUpdate {
  mayRevealPregnancy: boolean | null;
  bodyRegion: string | null;
}

/**
 * QA D3450 / D3254 (r70): the imaging catalogue an order's exam is picked from. A service of its
 * own, so the order form reads the catalogue without widening `ImagingService`.
 */
@Injectable({ providedIn: 'root' })
export class ImagingCatalogueService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/imaging/catalogue';

  /** The acting hospital's exams, by name. */
  list(): Observable<ImagingCatalogueExam[]> {
    return this.http.get<ImagingCatalogueExam[]>(this.baseUrl);
  }

  /** Sets an exam's pregnancy flag and body region (a hospital admin or a radiologist of the hospital). */
  update(itemId: string, body: ImagingCatalogueExamUpdate): Observable<ImagingCatalogueExam> {
    return this.http.put<ImagingCatalogueExam>(`${this.baseUrl}/${itemId}`, body);
  }
}
