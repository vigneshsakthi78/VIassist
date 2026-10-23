import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

export interface ChatRequest {
  message: string;
  imageBase64?: string;
  imageMimeType?: string;
}

export interface ChatResponse {
  answer: string;
}

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);

  chat(message: string, image?: { base64: string; mimeType: string }): Observable<ChatResponse> {
    const base = (environment.apiBaseUrl || '').replace(/\/$/, '');
    const apiUrl = `${base}/api/chat`;
    const body: ChatRequest = { message };
    if (image?.base64) {
      body.imageBase64 = image.base64;
      body.imageMimeType = image.mimeType;
    }
    return this.http.post<ChatResponse>(apiUrl, body);
  }
}
