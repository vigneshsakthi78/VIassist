# LangChain4j RAG — Spring Boot + Angular (standalone)

Standalone project: **Vicky Assist** — Spring Boot REST API + Angular chat UI for **enterprise employee productivity**.

Knowledge base topics:
- Prioritization and OKR-style focus
- Deep work and time blocking
- Meeting hygiene and async communication
- Stakeholder updates and decisions
- Team / manager productivity habits
- 30-day improvement plans

```text
langchain4j-rag-spring-angular/
  backend/     Spring Boot REST (http://localhost:8080)
  frontend/    Angular UI       (http://localhost:4200)
```

## Stack

| Piece | Choice |
|-------|--------|
| API | Spring Boot 3.5 + LangChain4j `@AiService` |
| Chat model | Google Gemini (`gemini-2.0-flash`) |
| Embeddings | Easy RAG ONNX `bge-small-en-v1.5` (local) |
| Vector store | In-memory |
| UI | Angular 19 |

## Prerequisites

- JDK 17+
- Maven 3.9+
- Node.js 20+ / npm
- Optional: `OPENAI_API_KEY`

## 1. Backend

**Important:** Spring Boot 3 needs **JDK 17**. If Maven uses system Java 8 you will see  
`UnsupportedClassVersionError ... class file version 61.0`.

Easiest (from project root):

```powershell
cd C:\Users\vignesh.sakthirajan\Documents\langchain4j-rag-spring-angular
.\run-backend.ps1
```

Or manually:

```powershell
cd C:\Users\vignesh.sakthirajan\Documents\langchain4j-rag-spring-angular\backend

$env:JAVA_HOME = (Resolve-Path ..\.tools\jdk-17*).Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version   # must show 17.x

# Required Gemini API key: https://aistudio.google.com/apikey
$env:GEMINI_API_KEY = "your-gemini-api-key"
# Optional:
# $env:GEMINI_MODEL = "gemini-2.0-flash"

..\.tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

### API

- `GET  /api/health` → `{ "status": "ok" }`
- `POST /api/chat` with `{ "message": "Help me prioritize this week and cut low-value meetings" }`  
  → `{ "answer": "..." }`

Sample knowledge base: [`backend/src/main/resources/docs`](backend/src/main/resources/docs)

## 2. Frontend

```powershell
cd C:\Users\vignesh.sakthirajan\Documents\langchain4j-rag-spring-angular\frontend
npm install
npm start
```

Open [http://localhost:4200](http://localhost:4200).

## Deploy publicly (custom domain)

- Frontend → **Netlify** or **Vercel**
- Backend → **Render** (Docker) — required for Spring Boot
- Netlify guide: [`DEPLOY-NETLIFY.md`](DEPLOY-NETLIFY.md)
- Vercel guide: [`DEPLOY-PUBLIC.md`](DEPLOY-PUBLIC.md) / [`DEPLOY-VERCEL.md`](DEPLOY-VERCEL.md)

## Environment

See [`.env.example`](.env.example).

| Variable | Purpose |
|----------|---------|
| `GEMINI_API_KEY` | Google AI Studio Gemini API key (required) |
| `GEMINI_MODEL` | Optional model name (default `gemini-3.6-flash`) |
| `DOCS_PATH` | Optional external docs folder |
| `VICKY_DATA_DIR` | Durable folder for learned chats + embedding cache (default `./data`) |
| `LEARNED_CHAT_PATH` | Optional override for learned Q&A file |
| `EMBEDDING_STORE_PATH` | Optional override for embedding cache JSON |
| `RAG_CORS_ORIGINS` | Allowed browser origins (Netlify + local) |
| `API_BASE_URL` | Frontend build-time API host (Netlify) |

## Flow

```text
Angular UI → POST /api/chat → Assistant (@AiService)
                              ↓
                     ContentRetriever (in-memory embeddings)
                              ↓
                     Gemini chat model → grounded productivity answer
```
