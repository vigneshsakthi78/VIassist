import { Component, ElementRef, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { ChatService } from './chat.service';
import { environment } from '../environments/environment';

interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
}

type MessagePart =
  | { type: 'text'; html: SafeHtml }
  | { type: 'image'; src: string; alt: string };

@Component({
  selector: 'app-root',
  imports: [FormsModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly chatService = inject(ChatService);
  private readonly sanitizer = inject(DomSanitizer);

  @ViewChild('thread') threadRef?: ElementRef<HTMLElement>;

  messages: ChatMessage[] = [
    {
      role: 'assistant',
      text:
        'Hi, I am Vicky Assist — enterprise productivity + MACK DMS coach. Ask for DMS how-tos with screenshots, e.g. "Show me the DMS home screen and how to open Image Manager".',
    },
  ];
  draft = '';
  loading = false;
  error = '';
  copiedIndex: number | null = null;
  private copyResetHandle: ReturnType<typeof setTimeout> | null = null;

  send(): void {
    const message = this.draft.trim();
    if (!message || this.loading) {
      return;
    }

    this.error = '';
    this.messages = [...this.messages, { role: 'user', text: message }];
    this.draft = '';
    this.loading = true;
    this.scrollToBottom();

    this.chatService.chat(message).subscribe({
      next: (response) => {
        this.messages = [
          ...this.messages,
          { role: 'assistant', text: response.answer },
        ];
        this.loading = false;
        this.scrollToBottom();
      },
      error: (err) => {
        this.loading = false;
        this.error =
          err?.error?.answer ||
          err?.message ||
          'Could not reach the RAG API. Is the Spring Boot backend running on port 8080?';
        this.scrollToBottom();
      },
    });
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  async copyMessage(message: ChatMessage, index: number): Promise<void> {
    try {
      await navigator.clipboard.writeText(message.text);
      this.copiedIndex = index;
      if (this.copyResetHandle) {
        clearTimeout(this.copyResetHandle);
      }
      this.copyResetHandle = setTimeout(() => {
        this.copiedIndex = null;
        this.copyResetHandle = null;
      }, 1600);
    } catch {
      this.error = 'Could not copy to clipboard.';
    }
  }

  /** Split images out, then render lightweight markdown (**bold**, *italic*, headings). */
  parts(text: string): MessagePart[] {
    const pattern = /!\[([^\]]*)\]\(([^)]+)\)/g;
    const parts: MessagePart[] = [];
    let last = 0;
    let match: RegExpExecArray | null;
    while ((match = pattern.exec(text)) !== null) {
      if (match.index > last) {
        parts.push({ type: 'text', html: this.toSafeHtml(text.slice(last, match.index)) });
      }
      parts.push({
        type: 'image',
        alt: match[1] || 'Screenshot',
        src: this.resolveImageUrl(match[2]),
      });
      last = match.index + match[0].length;
    }
    if (last < text.length) {
      parts.push({ type: 'text', html: this.toSafeHtml(text.slice(last)) });
    }
    return parts.length ? parts : [{ type: 'text', html: this.toSafeHtml(text) }];
  }

  private toSafeHtml(raw: string): SafeHtml {
    let html = this.escapeHtml(raw);

    // Headings: ### / ## / #
    html = html.replace(/^###\s+(.+)$/gm, '<strong class="md-h">$1</strong>');
    html = html.replace(/^##\s+(.+)$/gm, '<strong class="md-h">$1</strong>');
    html = html.replace(/^#\s+(.+)$/gm, '<strong class="md-h">$1</strong>');

    // Bold then italic (** before *)
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/(^|[\s(])\*(?!\s)(.+?)(?!\s)\*(?=[\s).,!?:;]|$)/g, '$1<em>$2</em>');

    // Inline code
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

    return this.sanitizer.bypassSecurityTrustHtml(html);
  }

  private escapeHtml(value: string): string {
    return value
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  private resolveImageUrl(url: string): string {
    const trimmed = url.trim();
    if (/^https?:\/\//i.test(trimmed)) {
      return trimmed;
    }
    const base = (environment.apiBaseUrl || '').replace(/\/$/, '');
    if (trimmed.startsWith('/')) {
      return `${base}${trimmed}`;
    }
    return `${base}/${trimmed}`;
  }

  private scrollToBottom(): void {
    queueMicrotask(() => {
      const el = this.threadRef?.nativeElement;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }
}
