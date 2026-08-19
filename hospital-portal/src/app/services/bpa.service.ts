import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { CdsCard } from '../shared/cds-card/cds-card.model';

/**
 * Wire shape of a CDS Hooks 1.0 invocation request body. Only the
 * subset HMS actually uses on the patient-view hook is modelled here.
 */
interface CdsHookRequestBody {
  hook: 'patient-view';
  hookInstance: string;
  user?: string;
  context: {
    patientId: string;
    encounterId?: string;
    userId?: string;
  };
}

interface CdsHookResponseBody {
  cards: CdsCard[];
}

/**
 * Calls the HMS Best-Practice-Advisory CDS service
 * (`hms-bpa-protocols`) on chart load. The backend runs the
 * malaria / sepsis-qSOFA / OB-hemorrhage rule engine and returns
 * advisory cards.
 *
 * <p>The interceptor in {@code auth.interceptor.ts} prepends
 * {@code /api} to every relative URL, so the resolved path is
 * {@code /api/cds-services/hms-bpa-protocols}.
 */
@Injectable({ providedIn: 'root' })
export class BpaService {
  private readonly http = inject(HttpClient);
  private readonly url = '/cds-services/hms-bpa-protocols';

  evaluate(patientId: string, encounterId?: string): Observable<CdsCard[]> {
    const body: CdsHookRequestBody = {
      hook: 'patient-view',
      hookInstance: cryptoRandomUuid(),
      context: {
        patientId,
        ...(encounterId ? { encounterId } : {}),
      },
    };
    return this.http.post<CdsHookResponseBody>(this.url, body).pipe(map((res) => res?.cards ?? []));
  }
}

function cryptoRandomUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'inst-' + Math.random().toString(36).slice(2);
}
