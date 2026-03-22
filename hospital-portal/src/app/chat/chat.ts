import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ChatService,
  ChatConversation,
  ChatMessage,
  ChatSendRequest,
  ChatAttachmentUploadResponse,
  CareTeamContact,
} from '../services/chat.service';
import { UserService, UserSummary } from '../services/user.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';

/** Maps each role to the set of roles it is allowed to message. */
const ALLOWED_MESSAGE_TARGETS: Record<string, Set<string>> = {
  ROLE_SUPER_ADMIN: new Set([
    'ROLE_SUPER_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
    'ROLE_PATIENT',
  ]),
  ROLE_HOSPITAL_ADMIN: new Set([
    'ROLE_SUPER_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
    'ROLE_PATIENT',
  ]),
  ROLE_DOCTOR: new Set([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
    'ROLE_PATIENT',
  ]),
  ROLE_NURSE: new Set([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
    'ROLE_PATIENT',
  ]),
  ROLE_MIDWIFE: new Set([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
    'ROLE_PATIENT',
  ]),
  ROLE_RECEPTIONIST: new Set([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
  ]),
  ROLE_LAB_SCIENTIST: new Set([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
  ]),
  ROLE_LAB_MANAGER: new Set([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
  ]),
  ROLE_STAFF: new Set([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
  ]),
  ROLE_PATIENT: new Set(['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE']),
};

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

  /* ── Care team contacts ── */
  careTeamContacts = signal<CareTeamContact[]>([]);
  loadingCareTeam = signal(false);
  newConvTab = signal<'care-team' | 'all'>('care-team');

  /* ── Sidebar search ── */
  convSearchTerm = signal('');

  /* ── File attachment ── */
  pendingAttachment = signal<ChatAttachmentUploadResponse | null>(null);
  uploadingAttachment = signal(false);

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
        this.chatService
          .markRead(conv.conversationUserId, this.currentUserId)
          .subscribe({ error: (_e) => _e });
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
    const attachment = this.pendingAttachment();
    if (!conv || (!this.messageText.trim() && !attachment)) return;

    this.sendingMessage.set(true);
    const req: ChatSendRequest = {
      senderId: this.currentUserId,
      recipientId: conv.conversationUserId,
      content: this.messageText.trim() || (attachment ? attachment.fileName : ''),
      ...(attachment && {
        attachmentUrl: attachment.url,
        attachmentName: attachment.fileName,
        attachmentContentType: attachment.contentType,
        attachmentSizeBytes: attachment.sizeBytes,
      }),
    };

    this.chatService.sendMessage(req).subscribe({
      next: (msg) => {
        this.messages.update((list) => [...list, msg]);
        this.messageText = '';
        this.pendingAttachment.set(null);
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

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    input.value = '';

    this.uploadingAttachment.set(true);
    this.chatService.uploadChatAttachment(file).subscribe({
      next: (result) => {
        this.pendingAttachment.set(result);
        this.uploadingAttachment.set(false);
      },
      error: () => {
        this.toast.error('Failed to upload attachment');
        this.uploadingAttachment.set(false);
      },
    });
  }

  removeAttachment(): void {
    this.pendingAttachment.set(null);
  }

  isImageAttachment(contentType: string | undefined): boolean {
    return !!contentType && contentType.startsWith('image/');
  }

  /* ── New Conversation actions ── */
  openNewConversation(): void {
    this.showNewConversation.set(true);
    this.userSearchTerm.set('');
    this.availableUsers.set([]);
    this.loadUsers();
    if (this.isPatient()) {
      this.newConvTab.set('care-team');
      this.loadCareTeam();
    } else {
      this.newConvTab.set('all');
    }
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

        // Determine which roles the current user may message
        const myRoles = this.auth.getRoles();
        const allowedTargets = new Set<string>();
        for (const role of myRoles) {
          const targets = ALLOWED_MESSAGE_TARGETS[role];
          if (targets) {
            targets.forEach((t) => allowedTargets.add(t));
          }
        }

        const filtered = (page.content ?? []).filter((u) => {
          if (u.id === this.currentUserId || existingIds.has(u.id)) return false;
          // Normalise the user's roleName for comparison (may or may not have ROLE_ prefix)
          const userRole = u.roleName?.startsWith('ROLE_') ? u.roleName : 'ROLE_' + u.roleName;
          return allowedTargets.has(userRole);
        });
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

  startConversationWithContact(contact: CareTeamContact): void {
    const conv: ChatConversation = {
      conversationUserId: contact.userId,
      conversationUserName: contact.displayName,
      lastMessageContent: '',
      lastMessageTimestamp: '',
      lastMessageRead: true,
      unreadCount: 0,
    };
    const exists = this.conversations().find((c) => c.conversationUserId === contact.userId);
    if (!exists) {
      this.conversations.update((list) => [conv, ...list]);
    }
    this.selectConversation(conv);
  }

  loadCareTeam(): void {
    this.loadingCareTeam.set(true);
    this.chatService.getMessageableCareTeam().subscribe({
      next: (contacts) => {
        this.careTeamContacts.set(contacts);
        this.loadingCareTeam.set(false);
      },
      error: () => {
        this.loadingCareTeam.set(false);
      },
    });
  }

  isPatient(): boolean {
    return this.auth.getRoles().includes('ROLE_PATIENT');
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
