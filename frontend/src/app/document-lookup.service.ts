import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface StoredAttachment {
  filename?: string;
  status?: string;
  size?: string;
}

interface StoredDocument {
  docid: string;
  title?: string;
  refno?: string;
  version?: string;
  status?: string;
  day?: string;
  documentFile?: string;
  contentText?: string;
  attachments?: StoredAttachment[];
}

interface IndexFile {
  documents?: StoredDocument[];
}

@Injectable({ providedIn: 'root' })
export class DocumentLookupService {
  private readonly http = inject(HttpClient);
  private indexPromise: Promise<Map<string, StoredDocument>> | null = null;

  /** Docid-only prompts are answered from the day-by-day extract, without the chat API. */
  isDocIdPrompt(message: string): boolean {
    return /^DMSDOC\d+(?:\s*[, ]\s*DMSDOC\d+)*$/i.test(message.trim());
  }

  async answer(message: string): Promise<string | null> {
    const ids = [...message.matchAll(/DMSDOC\d+/gi)].map((match) => match[0].toUpperCase());
    if (!ids.length) {
      return null;
    }
    const index = await this.load();
    const unique = [...new Set(ids)];
    if (!unique.some((id) => index.has(id))) {
      return null;
    }
    return unique.map((id) => this.format(id, index.get(id))).join('\n\n');
  }

  private load(): Promise<Map<string, StoredDocument>> {
    if (!this.indexPromise) {
      this.indexPromise = firstValueFrom(this.http.get<IndexFile>('/rag/doc-index.json'))
        .then((file) => {
          const map = new Map<string, StoredDocument>();
          for (const document of file.documents ?? []) {
            if (document.docid) {
              map.set(document.docid.toUpperCase(), document);
            }
          }
          return map;
        })
        .catch((err) => {
          this.indexPromise = null;
          throw err;
        });
    }
    return this.indexPromise;
  }

  private format(id: string, document: StoredDocument | undefined): string {
    if (!document) {
      return `${id}\nThis document is not in the day-by-day extract.`;
    }
    const content = (document.contentText || '').trim();
    const summary = content
      ? content.length > 700
        ? `${content.slice(0, 700).replace(/\s+\S*$/, '')}...`
        : content
      : 'No page content is stored for this document.';
    const attachments = document.attachments ?? [];
    const attachmentLines = attachments.length
      ? attachments
          .map((item) => `- ${item.filename || '(unnamed)'} | status ${item.status || ''} | size ${item.size || ''}`)
          .join('\n')
      : '(none)';
    return [
      `## ${document.docid}`,
      `Title: ${document.title || '(none)'}`,
      `Reference: ${document.refno || '(none)'}`,
      `Version: ${document.version || '(none)'}`,
      `Status: ${document.status || '(none)'}`,
      `Published day: ${document.day || '(none)'}`,
      '',
      'Summary:',
      summary,
      '',
      `Document file: ${document.documentFile || '(none)'}`,
      'Attachments:',
      attachmentLines,
    ].join('\n');
  }
}
