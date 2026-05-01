import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ChatService,
  ChatConversation,
  ChatMessage,
  ChatSendRequest,
  ChatAttachment,
} from '../services/chat.service';
import { UserService, UserSummary } from '../services/user.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule } from '@ngx-translate/core';

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
    'ROLE_STAFF',
  ]),
  ROLE_LAB_SCIENTIST: new Set([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_STAFF',
  ]),
  ROLE_STAFF: new Set([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_STAFF',
  ]),
  ROLE_PATIENT: new Set(['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE']),
};

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
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

  /** Attachments queued by the user for the next chat send. */
  pendingAttachments = signal<ChatAttachment[]>([]);
  uploadingPhoto = signal(false);
  recordingAudio = signal(false);
  uploadingAudio = signal(false);
  recordingElapsed = signal(0);
  attachmentError = signal<string | null>(null);

  private mediaRecorder: MediaRecorder | null = null;
  private recordedChunks: Blob[] = [];
  private recordingStartedAt = 0;
  private recordingTimerId: ReturnType<typeof setInterval> | null = null;

  /** Browser's recorded MIME type for the active session (decided at start). */
  private recordedMimeType = '';

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
    const attachments = this.pendingAttachments();
    if (!conv) return;
    if (!this.messageText.trim() && attachments.length === 0) return;

    this.sendingMessage.set(true);
    const req: ChatSendRequest = {
      senderId: this.currentUserId,
      recipientId: conv.conversationUserId,
      content: this.messageText.trim() || (attachments.length > 0 ? '📎' : ''),
      attachments: attachments.length > 0 ? attachments : undefined,
    };

    this.chatService.sendMessage(req).subscribe({
      next: (msg) => {
        this.messages.update((list) => [...list, msg]);
        this.messageText = '';
        this.pendingAttachments.set([]);
        this.attachmentError.set(null);
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

  /* ── Telehealth low-bandwidth attachments ── */

  /** Maximum attachments per message — mirrors the backend cap. */
  static readonly MAX_ATTACHMENTS_PER_MESSAGE = 4;
  /** Voice memo cap (mirrors backend audio duration constraint). */
  static readonly MAX_VOICE_MEMO_SECONDS = 90;

  onPhotoSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (this.pendingAttachments().length >= ChatComponent.MAX_ATTACHMENTS_PER_MESSAGE) {
      this.attachmentError.set('You can attach at most 4 items to a single message.');
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      this.attachmentError.set('Photo must be 10 MB or smaller.');
      return;
    }
    this.uploadingPhoto.set(true);
    this.attachmentError.set(null);
    this.chatService.uploadChatAttachment(file, 'PHOTO', file.name).subscribe({
      next: (att) => {
        this.pendingAttachments.update((list) => [...list, att]);
        this.uploadingPhoto.set(false);
      },
      error: (err) => {
        this.uploadingPhoto.set(false);
        const detail = err?.error?.message ?? 'Failed to upload photo.';
        this.attachmentError.set(detail);
      },
    });
  }

  removePendingAttachment(index: number): void {
    this.pendingAttachments.update((list) => list.filter((_, i) => i !== index));
  }

  async toggleVoiceMemo(): Promise<void> {
    if (this.recordingAudio()) {
      this.stopVoiceMemo();
      return;
    }
    if (this.pendingAttachments().length >= ChatComponent.MAX_ATTACHMENTS_PER_MESSAGE) {
      this.attachmentError.set('You can attach at most 4 items to a single message.');
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      this.attachmentError.set('Voice memo recording is not supported in this browser.');
      return;
    }
    try {
      this.attachmentError.set(null);
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      this.recordedMimeType = this.pickRecorderMimeType();
      this.mediaRecorder = new MediaRecorder(
        stream,
        this.recordedMimeType ? { mimeType: this.recordedMimeType } : undefined,
      );
      this.recordedChunks.length = 0;
      this.recordingStartedAt = Date.now();
      this.recordingElapsed.set(0);
      this.mediaRecorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) this.recordedChunks.push(e.data);
      };
      this.mediaRecorder.onstop = () => {
        stream.getTracks().forEach((t) => t.stop());
        this.finalizeVoiceMemo();
      };
      this.mediaRecorder.start();
      this.recordingAudio.set(true);
      this.recordingTimerId = setInterval(() => {
        const seconds = Math.floor((Date.now() - this.recordingStartedAt) / 1000);
        this.recordingElapsed.set(seconds);
        if (seconds >= ChatComponent.MAX_VOICE_MEMO_SECONDS) {
          this.stopVoiceMemo();
        }
      }, 250);
    } catch {
      this.recordingAudio.set(false);
      this.attachmentError.set('Microphone access was denied.');
    }
  }

  stopVoiceMemo(): void {
    if (this.recordingTimerId) {
      clearInterval(this.recordingTimerId);
      this.recordingTimerId = null;
    }
    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      this.mediaRecorder.stop();
    }
    this.recordingAudio.set(false);
  }

  private pickRecorderMimeType(): string {
    const candidates = [
      'audio/webm;codecs=opus',
      'audio/webm',
      'audio/ogg;codecs=opus',
      'audio/ogg',
      'audio/mp4',
    ];
    for (const c of candidates) {
      if (typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(c)) return c;
    }
    return '';
  }

  private finalizeVoiceMemo(): void {
    const mimeType = this.recordedMimeType || 'audio/webm';
    const durationSeconds = Math.max(
      1,
      Math.min(
        ChatComponent.MAX_VOICE_MEMO_SECONDS,
        Math.round((Date.now() - this.recordingStartedAt) / 1000),
      ),
    );
    const blob = new Blob(this.recordedChunks, { type: mimeType });
    if (blob.size === 0) {
      this.attachmentError.set('Voice memo was empty — please try again.');
      return;
    }
    const ext = mimeType.includes('ogg') ? 'ogg' : mimeType.includes('mp4') ? 'm4a' : 'webm';
    const fileName = `voice_memo_${Date.now()}.${ext}`;
    this.uploadingAudio.set(true);
    this.chatService.uploadChatAttachment(blob, 'AUDIO', fileName, durationSeconds).subscribe({
      next: (att) => {
        this.pendingAttachments.update((list) => [...list, att]);
        this.uploadingAudio.set(false);
      },
      error: (err) => {
        this.uploadingAudio.set(false);
        const detail = err?.error?.message ?? 'Failed to upload voice memo.';
        this.attachmentError.set(detail);
      },
    });
  }

  formatRecordingTime(seconds: number): string {
    const m = Math.floor(seconds / 60)
      .toString()
      .padStart(1, '0');
    const s = Math.floor(seconds % 60)
      .toString()
      .padStart(2, '0');
    return `${m}:${s}`;
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
