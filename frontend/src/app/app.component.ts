import { Component, ElementRef, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ChatService } from './chat.service';

interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
}

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
      text: 'Hi, I am Vicky Assist. I will teach coding patterns and guide your practice on Take U Forward A2Z. Try: "Teach sliding window and give TUF tasks".',
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

  private scrollToBottom(): void {
    queueMicrotask(() => {
      const el = this.threadRef?.nativeElement;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }
}
