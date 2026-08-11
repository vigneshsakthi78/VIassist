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
        'Hi, I am Vicky Assist — enterprise productivity + MACK DMS coach with screenshot how-tos.\n\n' +
        'Ask e.g. "Show DMS home and how to open Image Manager".\n\n' +
        '## Try next\n' +
        '- Show me the Video Manager with screenshot\n' +
        '- How do I edit a DMS page?\n' +
        '- How do I search documents and attachments?',
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
    this.ask(message);
  }

  askSuggestion(prompt: string): void {
    if (this.loading) {
      return;
    }
    this.ask(prompt);
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

  /** Body text without the Try next section (chips render separately). */
  displayText(message: ChatMessage): string {
    return this.splitTryNext(message.text).body;
  }

  followUps(message: ChatMessage): string[] {
    if (message.role !== 'assistant') {
      return [];
    }
    const parsed = this.splitTryNext(message.text).suggestions;
    if (parsed.length) {
      return parsed.slice(0, 3);
    }
    return this.heuristicFollowUps(message.text).slice(0, 3);
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

  private ask(message: string): void {
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

  private splitTryNext(text: string): { body: string; suggestions: string[] } {
    const match = text.match(/\n##\s*Try next\s*\n([\s\S]*)$/i);
    if (!match) {
      return { body: text, suggestions: [] };
    }
    const body = text.slice(0, match.index).trimEnd();
    const suggestions = match[1]
      .split(/\r?\n/)
      .map((line) => line.replace(/^\s*[-*]\s+/, '').trim())
      .filter((line) => line.length > 0 && !line.startsWith('#'));
    return { body, suggestions };
  }

  private heuristicFollowUps(text: string): string[] {
    const lower = text.toLowerCase();
    const tips: string[] = [];
    if (lower.includes('image manager')) {
      tips.push('Show Video Manager with screenshot');
    }
    if (lower.includes('video manager') || lower.includes('edit page')) {
      tips.push('How do I open Image Manager?');
    }
    if (lower.includes('home') || lower.includes('welcome') || lower.includes('landing')) {
      tips.push('How do I search documents and attachments?');
    }
    if (lower.includes('publish') || lower.includes('workflow')) {
      tips.push('How do I republish a page after edits?');
    }
    if (!tips.length && (lower.includes('dms') || lower.includes('screenshot') || lower.includes('how-to'))) {
      tips.push('Show DMS home screenshot');
      tips.push('How do I open Image Manager?');
    }
    return tips;
  }

  private toSafeHtml(raw: string): SafeHtml {
    let html = this.escapeHtml(raw);

    html = html.replace(/^###\s+(.+)$/gm, '<strong class="md-h">$1</strong>');
    html = html.replace(/^##\s+(.+)$/gm, '<strong class="md-h">$1</strong>');
    html = html.replace(/^#\s+(.+)$/gm, '<strong class="md-h">$1</strong>');

    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/(^|[\s(])\*(?!\s)(.+?)(?!\s)\*(?=[\s).,!?:;]|$)/g, '$1<em>$2</em>');

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
