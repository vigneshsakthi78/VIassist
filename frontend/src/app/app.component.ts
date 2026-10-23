import { Component, ElementRef, OnDestroy, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeHtml, SafeUrl } from '@angular/platform-browser';
import { ChatService } from './chat.service';
import { DocumentLookupService } from './document-lookup.service';
import { environment } from '../environments/environment';

interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  imageUrl?: string;
  responseMs?: number;
  lookup?: boolean;
}

interface PendingImage {
  mimeType: string;
  base64: string;
  previewUrl: string;
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
export class AppComponent implements OnDestroy {
  private readonly chatService = inject(ChatService);
  private readonly documentLookup = inject(DocumentLookupService);
  private readonly sanitizer = inject(DomSanitizer);

  @ViewChild('thread') threadRef?: ElementRef<HTMLElement>;
  @ViewChild('fileInput') fileInputRef?: ElementRef<HTMLInputElement>;

  messages: ChatMessage[] = [
    {
      role: 'assistant',
      text:
        'Hi, I am Vicky Assist — enterprise productivity + MACK DMS coach with screenshot how-tos.\n\n' +
        'Ask e.g. "Show DMS home and how to open Image Manager", or attach a screenshot and I will read it.\n\n' +
        '## Try next\n' +
        '- Show me the Video Manager with screenshot\n' +
        '- How do I edit a DMS page?\n' +
        '- How do I search documents and attachments?',
    },
  ];
  draft = '';
  pendingImage: PendingImage | null = null;
  loading = false;
  error = '';
  errorResponseMs: number | null = null;
  elapsedMs = 0;
  copiedIndex: number | null = null;
  private copyResetHandle: ReturnType<typeof setTimeout> | null = null;
  private responseStartedAt = 0;
  private elapsedTimer: ReturnType<typeof setInterval> | null = null;

  ngOnDestroy(): void {
    this.stopResponseTimer();
    if (this.copyResetHandle) {
      clearTimeout(this.copyResetHandle);
    }
  }

  formatResponseTime(ms: number): string {
    if (ms < 1000) {
      return `${Math.max(0, Math.round(ms))} ms`;
    }
    if (ms < 60_000) {
      return `${(ms / 1000).toFixed(1)} s`;
    }
    const totalSeconds = Math.round(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}m ${seconds.toString().padStart(2, '0')}s`;
  }

  send(): void {
    const message = this.draft.trim();
    if (this.loading || (!message && !this.pendingImage)) {
      return;
    }
    this.ask(message);
  }

  get canSend(): boolean {
    return !this.loading && (!!this.draft.trim() || !!this.pendingImage);
  }

  openFilePicker(): void {
    this.fileInputRef?.nativeElement.click();
  }

  clearPendingImage(): void {
    this.pendingImage = null;
    if (this.fileInputRef?.nativeElement) {
      this.fileInputRef.nativeElement.value = '';
    }
  }

  async onFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      await this.attachFile(file);
    }
    input.value = '';
  }

  async onPaste(event: ClipboardEvent): Promise<void> {
    const item = [...(event.clipboardData?.items || [])].find((entry) =>
      entry.type.startsWith('image/'),
    );
    const file = item?.getAsFile();
    if (!file) {
      return;
    }
    event.preventDefault();
    await this.attachFile(file);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  async onDrop(event: DragEvent): Promise<void> {
    event.preventDefault();
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      await this.attachFile(file);
    }
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
    if (message.role !== 'assistant' || message.lookup) {
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
    const image = this.pendingImage;
    this.error = '';
    this.messages = [
      ...this.messages,
      {
        role: 'user',
        text: message || 'What is on this screenshot?',
        imageUrl: image?.previewUrl,
      },
    ];
    this.draft = '';
    this.pendingImage = null;
    this.loading = true;
    this.startResponseTimer();
    this.scrollToBottom();

    if (!image && this.documentLookup.isDocIdPrompt(message)) {
      void this.answerFromIndex(message);
      return;
    }

    this.chatService
      .chat(message, image ? { base64: image.base64, mimeType: image.mimeType } : undefined)
      .subscribe({
        next: (response) => {
          const responseMs = this.stopResponseTimer();
          this.messages = [
            ...this.messages,
            { role: 'assistant', text: response.answer, responseMs },
          ];
          this.loading = false;
          this.scrollToBottom();
        },
        error: (err) => {
          this.errorResponseMs = this.stopResponseTimer();
          this.loading = false;
          this.error =
            err?.error?.answer ||
            err?.message ||
            'Could not reach the RAG API. Is the Spring Boot backend running on port 8080?';
          this.scrollToBottom();
        },
      });
  }

  private async answerFromIndex(message: string): Promise<void> {
    try {
      const answer = await this.documentLookup.answer(message);
      const responseMs = this.stopResponseTimer();
      if (!answer) {
        this.errorResponseMs = responseMs;
        this.loading = false;
        this.error = 'That document id is not in the day-by-day extract.';
        this.scrollToBottom();
        return;
      }
      this.messages = [...this.messages, { role: 'assistant', text: answer, responseMs, lookup: true }];
      this.loading = false;
      this.scrollToBottom();
    } catch {
      const responseMs = this.stopResponseTimer();
      this.errorResponseMs = responseMs;
      this.loading = false;
      this.error = 'Could not read the day-by-day document index.';
      this.scrollToBottom();
    }
  }

  private async attachFile(file: File): Promise<void> {
    try {
      this.error = '';
      this.pendingImage = await this.readImage(file);
    } catch (err) {
      this.pendingImage = null;
      this.error = err instanceof Error ? err.message : 'Could not attach that image.';
    }
  }

  private async readImage(file: File): Promise<PendingImage> {
    const allowed = ['image/png', 'image/jpeg', 'image/jpg', 'image/webp', 'image/gif'];
    if (!allowed.includes(file.type)) {
      throw new Error('Attach a PNG, JPEG, WebP, or GIF screenshot.');
    }

    let blob: Blob = file;
    let mimeType = file.type === 'image/jpg' ? 'image/jpeg' : file.type;
    if (file.size > 900_000) {
      blob = await this.resizeToJpeg(file);
      mimeType = 'image/jpeg';
    }
    if (blob.size > 3.5 * 1024 * 1024) {
      throw new Error('Image is too large. Try a cropped screenshot under 4 MB.');
    }

    const previewUrl = await this.blobToDataUrl(blob);
    const base64 = previewUrl.replace(/^data:[^;]+;base64,/, '');
    return { mimeType, base64, previewUrl };
  }

  private async resizeToJpeg(file: File): Promise<Blob> {
    try {
      const bitmap = await createImageBitmap(file);
      const maxEdge = 1600;
      const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
      const width = Math.max(1, Math.round(bitmap.width * scale));
      const height = Math.max(1, Math.round(bitmap.height * scale));
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        bitmap.close();
        return file;
      }
      ctx.drawImage(bitmap, 0, 0, width, height);
      bitmap.close();
      return await new Promise((resolve) => {
        canvas.toBlob((blob) => resolve(blob || file), 'image/jpeg', 0.82);
      });
    } catch {
      return file;
    }
  }

  private blobToDataUrl(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result || ''));
      reader.onerror = () => reject(new Error('Could not read the image.'));
      reader.readAsDataURL(blob);
    });
  }

  safeImageUrl(url: string): SafeUrl {
    return this.sanitizer.bypassSecurityTrustUrl(url);
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

  private startResponseTimer(): void {
    this.stopResponseTimer();
    this.errorResponseMs = null;
    this.responseStartedAt = performance.now();
    this.elapsedMs = 0;
    this.elapsedTimer = setInterval(() => {
      this.elapsedMs = performance.now() - this.responseStartedAt;
    }, 100);
  }

  private stopResponseTimer(): number {
    const elapsed = this.responseStartedAt
      ? Math.round(performance.now() - this.responseStartedAt)
      : 0;
    if (this.elapsedTimer) {
      clearInterval(this.elapsedTimer);
      this.elapsedTimer = null;
    }
    this.elapsedMs = elapsed;
    this.responseStartedAt = 0;
    return elapsed;
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
