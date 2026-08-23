import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ChatAttachmentKind = 'PHOTO' | 'AUDIO';

export interface ChatAttachment {
  id?: string;
  storageKey: string;
  publicUrl?: string;
  displayName?: string;
  contentType?: string;
  sizeBytes?: number;
  sha256?: string;
  kind: ChatAttachmentKind;
  durationSeconds?: number | null;
}

export interface ChatMessage {
  id: string;
  senderId: string;
  senderName: string;
  recipientId: string;
  recipientName: string;
  content: string;
  timestamp: string;
  read: boolean;
  attachments?: ChatAttachment[];
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
  recipientId: string;
  content: string;
  attachments?: ChatAttachment[];
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

  /**
   * Fetch an attachment's bytes through the authenticated,
   * participant-checked endpoint. Attachment files have no public static
   * mapping (the permitAll /uploads/** hole was closed), and a bare
   * <img src>/<audio src> carries no bearer token — render via
   * URL.createObjectURL on the returned blob instead.
   */
  getAttachmentBlob(attachmentId: string): Observable<Blob> {
    return this.http.get(`/chat/attachments/${attachmentId}/download`, {
      responseType: 'blob',
    });
  }

  /**
   * Upload a single chat attachment (telehealth low-bandwidth).
   * Returns the storage descriptor; the caller links it on the next sendMessage().
   */
  uploadChatAttachment(
    file: Blob,
    kind: ChatAttachmentKind,
    fileName: string,
    durationSeconds?: number,
  ): Observable<ChatAttachment> {
    const form = new FormData();
    form.append('file', file, fileName);
    form.append('kind', kind);
    if (kind === 'AUDIO' && durationSeconds != null) {
      form.append('durationSeconds', String(Math.round(durationSeconds)));
    }
    return this.http.post<ChatAttachment>('/files/chat-attachments', form);
  }
}
