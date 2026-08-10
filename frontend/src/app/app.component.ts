import { Component, ElementRef, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ChatService } from './chat.service';
import { environment } from '../environments/environment';

interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
}

type MessagePart =
  | { type: 'text'; text: string }
  | { type: 'image'; src: string; alt: string };

@Component({
  selector: 'app-root',
  imports: [FormsModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly chatService = inject(ChatService);

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

  /** Parse markdown images so assistant answers can show DMS screenshots. */
  parts(text: string): MessagePart[] {
    const pattern = /!\[([^\]]*)\]\(([^)]+)\)/g;
    const parts: MessagePart[] = [];
    let last = 0;
    let match: RegExpExecArray | null;
    while ((match = pattern.exec(text)) !== null) {
      if (match.index > last) {
        parts.push({ type: 'text', text: text.slice(last, match.index) });
      }
      parts.push({
        type: 'image',
        alt: match[1] || 'Screenshot',
        src: this.resolveImageUrl(match[2]),
      });
      last = match.index + match[0].length;
    }
    if (last < text.length) {
      parts.push({ type: 'text', text: text.slice(last) });
    }
    return parts.length ? parts : [{ type: 'text', text }];
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
