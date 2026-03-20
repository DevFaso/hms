import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

export interface ChatMessage {
  id: string;
  senderId: string;
  senderName: string;
  recipientId: string;
  recipientName: string;
  content: string;
  timestamp: string;
  read: boolean;
  // Optional attachment fields
  attachmentUrl?: string;
  attachmentName?: string;
  attachmentContentType?: string;
  attachmentSizeBytes?: number;
}

export interface ChatConversation {
  conversationUserId: string;
  conversationUserName: string;
  lastMessageContent: string;
  lastMessageTimestamp: string;
  lastMessageRead: boolean;
  unreadCount: number;
}

export interface ChatSendRequest {
  senderId: string;
  recipientId: string;
  content: string;
  // Optional attachment (obtained from POST /files/chat-attachments)
  attachmentUrl?: string;
  attachmentName?: string;
  attachmentContentType?: string;
  attachmentSizeBytes?: number;
}

export interface CareTeamContact {
  userId: string;
  displayName: string;
  roleLabel: string;
}

export interface ChatAttachmentUploadResponse {
  url: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
}

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);

  getConversations(userId: string, page = 0, size = 20): Observable<ChatConversation[]> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<ChatConversation[]>(`/chat/conversations/${userId}`, { params });
  }

  getHistory(user1Id: string, user2Id: string, page = 0, size = 50): Observable<ChatMessage[]> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<ChatMessage[]>(`/chat/history/${user1Id}/${user2Id}`, { params });
  }

  sendMessage(req: ChatSendRequest): Observable<ChatMessage> {
    return this.http.post<ChatMessage>('/chat/send', req);
  }

  markRead(senderId: string, recipientId: string): Observable<void> {
    return this.http.put<void>(`/chat/mark-read/${senderId}/${recipientId}`, {});
  }

  uploadChatAttachment(file: File): Observable<ChatAttachmentUploadResponse> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<ChatAttachmentUploadResponse>('/files/chat-attachments', form);
  }

  getMessageableCareTeam(): Observable<CareTeamContact[]> {
    return this.http
      .get<{ data: CareTeamContact[]; success: boolean }>('/api/me/patient/care-team/messageable')
      .pipe(
        map((r) => r.data ?? []),
        catchError(() => of([])),
      );
  }
}
