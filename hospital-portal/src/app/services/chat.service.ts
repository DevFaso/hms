import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ChatMessage {
  id: string;
  senderId: string;
  senderName: string;
  recipientId: string;
  recipientName: string;
  content: string;
  timestamp: string;
  read: boolean;
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
}
