import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** One note left unsigned for more than 72 hours (provisional platform rule). No clinical content. */
export interface UnsignedNoteOverdue {
  noteId: string;
  encounterId: string;
  authorName: string | null;
  patientName: string | null;
  visitDate: string | null;
  writtenAt: string | null;
  ageHours: number;
}

@Injectable({ providedIn: 'root' })
export class UnsignedNotesService {
  private readonly http = inject(HttpClient);

  /** The acting hospital's notes unsigned for more than 72 hours, oldest first. */
  getOverdue(): Observable<UnsignedNoteOverdue[]> {
    return this.http.get<UnsignedNoteOverdue[]>('/unsigned-notes/overdue');
  }
}
