import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface AssignmentPublicView {
  assignmentId: string;
  assignmentCode: string;
  roleName: string;
  roleCode: string;
  roleDescription: string | null;
  hospitalName: string;
  hospitalCode: string;
  hospitalAddress: string | null;
  assigneeName: string;
  confirmationVerified: boolean;
  confirmationVerifiedAt: string | null;
  profileCompletionUrl: string | null;
  profileChecklist: string[] | null;
  /** Temporary login username — only present on first-time assignment for new users. */
  tempUsername: string | null;
  /** Temporary login password — only present on first-time assignment for new users. */
  tempPassword: string | null;
}

@Injectable({ providedIn: 'root' })
export class AssignmentPublicService {
  private readonly http = inject(HttpClient);

  /**
   * Fetch the public view of an assignment by its human-readable code.
   * No authentication required.
   */
  getPublicView(assignmentCode: string): Observable<AssignmentPublicView> {
    return this.http.get<AssignmentPublicView>(`/api/assignments/public/${assignmentCode}`);
  }

  /**
   * Self-service verification: the assignee submits their 6-digit confirmation code.
   * No authentication required.
   */
  verifyCode(assignmentCode: string, confirmationCode: string): Observable<AssignmentPublicView> {
    return this.http.post<AssignmentPublicView>(
      `/api/assignments/public/${assignmentCode}/verify`,
      { confirmationCode },
    );
  }
}
