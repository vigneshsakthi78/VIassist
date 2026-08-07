import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

export interface ChatRequest {
  message: string;
}

export interface ChatResponse {
  answer: string;
}

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);

  chat(message: string): Observable<ChatResponse> {
    const base = (environment.apiBaseUrl || '').replace(/\/$/, '');
    const apiUrl = `${base}/api/chat`;
    return this.http.post<ChatResponse>(apiUrl, { message } satisfies ChatRequest);
  }
}
