import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ChatService,
  ChatConversation,
  ChatMessage,
  ChatSendRequest,
} from '../services/chat.service';
import { UserService, UserSummary } from '../services/user.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat.html',
  styleUrl: './chat.scss',
})
export class ChatComponent implements OnInit {
  private readonly chatService = inject(ChatService);
  private readonly userService = inject(UserService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);

  conversations = signal<ChatConversation[]>([]);
  messages = signal<ChatMessage[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);

  activeConversation = signal<ChatConversation | null>(null);
  messageText = '';
  sendingMessage = signal(false);

  currentUserId = '';

  /* ── New Conversation panel ── */
  showNewConversation = signal(false);
  userSearchTerm = signal('');
  availableUsers = signal<UserSummary[]>([]);
  loadingUsers = signal(false);

  /* ── Sidebar search ── */
  convSearchTerm = signal('');

  filteredConversations = computed(() => {
    const term = this.convSearchTerm().toLowerCase().trim();
    const all = this.conversations();
    if (!term) return all;
    return all.filter(
      (c) =>
        c.conversationUserName.toLowerCase().includes(term) ||
        c.lastMessageContent?.toLowerCase().includes(term),
    );
  });

  ngOnInit(): void {
    this.currentUserId = this.auth.getUserId() ?? this.auth.getUserProfile()?.id ?? '';

    if (this.currentUserId) {
      this.loadConversations();
    } else {
      this.loading.set(false);
      this.error.set('Unable to identify current user. Please log out and log back in.');
    }
  }

  loadConversations(): void {
    this.loading.set(true);
    this.error.set(null);
    this.chatService.getConversations(this.currentUserId).subscribe({
      next: (list) => {
        this.conversations.set(list ?? []);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        const status = err?.status;
        if (status === 403) {
          this.error.set('You do not have permission to access chat.');
        } else {
          this.error.set('Failed to load conversations. Please try again.');
        }
      },
    });
  }

  selectConversation(conv: ChatConversation): void {
    this.activeConversation.set(conv);
    this.showNewConversation.set(false);
    this.messages.set([]);
    this.chatService.getHistory(this.currentUserId, conv.conversationUserId).subscribe({
      next: (msgs) => {
        this.messages.set(msgs ?? []);
        this.chatService.markRead(conv.conversationUserId, this.currentUserId).subscribe();
        // Update unread count in sidebar
        this.conversations.update((list) =>
          list.map((c) =>
            c.conversationUserId === conv.conversationUserId ? { ...c, unreadCount: 0 } : c,
          ),
        );
      },
    });
  }

  sendMessage(): void {
    const conv = this.activeConversation();
    if (!conv || !this.messageText.trim()) return;

    this.sendingMessage.set(true);
    const req: ChatSendRequest = {
      senderId: this.currentUserId,
      recipientId: conv.conversationUserId,
      content: this.messageText.trim(),
    };

    this.chatService.sendMessage(req).subscribe({
      next: (msg) => {
        this.messages.update((list) => [...list, msg]);
        this.messageText = '';
        this.sendingMessage.set(false);
        // Update the conversation preview in sidebar
        this.conversations.update((list) =>
          list.map((c) =>
            c.conversationUserId === conv.conversationUserId
              ? { ...c, lastMessageContent: msg.content, lastMessageTimestamp: msg.timestamp }
              : c,
          ),
        );
      },
      error: () => {
        this.toast.error('Failed to send message');
        this.sendingMessage.set(false);
      },
    });
  }

  /* ── New Conversation actions ── */
  openNewConversation(): void {
    this.showNewConversation.set(true);
    this.userSearchTerm.set('');
    this.availableUsers.set([]);
    this.loadUsers();
  }

  closeNewConversation(): void {
    this.showNewConversation.set(false);
  }

  loadUsers(): void {
    this.loadingUsers.set(true);
    this.userService.list(0, 100).subscribe({
      next: (page) => {
        // Filter out current user and already-conversing users
        const existingIds = new Set(this.conversations().map((c) => c.conversationUserId));
        const filtered = (page.content ?? []).filter(
          (u) => u.id !== this.currentUserId && !existingIds.has(u.id),
        );
        this.availableUsers.set(filtered);
        this.loadingUsers.set(false);
      },
      error: () => {
        this.toast.error('Failed to load users');
        this.loadingUsers.set(false);
      },
    });
  }

  filteredUsers(): UserSummary[] {
    const term = this.userSearchTerm().toLowerCase().trim();
    const users = this.availableUsers();
    if (!term) return users;
    return users.filter(
      (u) =>
        u.firstName?.toLowerCase().includes(term) ||
        u.lastName?.toLowerCase().includes(term) ||
        u.username?.toLowerCase().includes(term) ||
        u.email?.toLowerCase().includes(term),
    );
  }

  startConversationWith(user: UserSummary): void {
    // Create a synthetic conversation and select it
    const conv: ChatConversation = {
      conversationUserId: user.id,
      conversationUserName: `${user.firstName} ${user.lastName}`.trim() || user.username,
      lastMessageContent: '',
      lastMessageTimestamp: '',
      lastMessageRead: true,
      unreadCount: 0,
    };
    // Add to conversations if not already there
    const exists = this.conversations().find((c) => c.conversationUserId === user.id);
    if (!exists) {
      this.conversations.update((list) => [conv, ...list]);
    }
    this.selectConversation(conv);
  }

  /* ── Message header actions ── */
  refreshMessages(): void {
    const conv = this.activeConversation();
    if (!conv) return;
    this.chatService.getHistory(this.currentUserId, conv.conversationUserId).subscribe({
      next: (msgs) => {
        this.messages.set(msgs ?? []);
        this.toast.success('Messages refreshed');
      },
    });
  }

  /* ── Helpers ── */
  getInitials(name: string): string {
    if (!name) return '?';
    const parts = name.split(' ');
    return parts
      .map((p) => p.charAt(0))
      .join('')
      .toUpperCase()
      .substring(0, 2);
  }

  isOwnMessage(msg: ChatMessage): boolean {
    return msg.senderId === this.currentUserId;
  }

  formatTime(timestamp: string): string {
    if (!timestamp) return '';
    const d = new Date(timestamp);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
}
