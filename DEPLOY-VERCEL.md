# Deploy Vicky Assist to Vercel

## Important

**Vercel hosts the Angular frontend only.**  
Spring Boot (Java + RAG + Gemini) **cannot** run on Vercel. Deploy the backend to one of:
- [Render](https://render.com) (Web Service)
- [Railway](https://railway.app)
- [Fly.io](https://fly.io)
- Google Cloud Run

Then point the Vercel frontend to that backend URL.

```text
Browser (Vercel Angular)
    -> https://your-api.onrender.com/api/chat
        -> Spring Boot + Gemini + RAG docs
```

---

## 1) Deploy backend first (example: Render)

1. Push this repo to GitHub.
2. Create a **Web Service** from `backend/`.
3. Build command:
   ```bash
   ./mvnw -DskipTests package
   ```
   (or install Maven in the build image and run `mvn -DskipTests package`)
4. Start command:
   ```bash
   java -jar target/langchain4j-rag-backend-1.0.0-SNAPSHOT.jar
   ```
5. Set env vars:
   - `GEMINI_API_KEY` = your Gemini key
   - `GEMINI_MODEL` = `gemini-2.0-flash-lite` (optional)
   - `RAG_CORS_ORIGINS` = `https://YOUR-VERCEL-APP.vercel.app,http://localhost:4200`
6. Copy the public backend URL, e.g. `https://vicky-assist-api.onrender.com`

> Tip: first create the Vercel project to know the exact frontend URL, then set `RAG_CORS_ORIGINS`, or temporarily allow both preview + production Vercel URLs.

---

## 2) Deploy frontend to Vercel

### Option A — Vercel Dashboard

1. Import the GitHub repo in Vercel.
2. Configure project:
   - **Root Directory:** leave blank (uses root `vercel.json`)  
     OR set Root Directory to `frontend` (uses `frontend/vercel.json`)
   - **Build Command:** already in `vercel.json` (`npm run vercel-build`)
   - **Output Directory:** already in `vercel.json`
3. Environment variable:
   - Name: `API_BASE_URL`
   - Value: `https://your-api.onrender.com`  *(no trailing slash)*
4. Deploy.

### Option B — Vercel CLI

```powershell
cd C:\Users\vignesh.sakthirajan\Documents\langchain4j-rag-spring-angular\frontend
npm i -g vercel
$env:API_BASE_URL = "https://your-api.onrender.com"
vercel login
vercel --prod
```

Set `API_BASE_URL` in the Vercel project settings for Production/Preview as well.

---

## 3) Verify

1. Open your Vercel URL.
2. Browser DevTools → Network → `POST .../api/chat` should hit your backend host (not Vercel).
3. If CORS error: add the exact Vercel origin to backend `RAG_CORS_ORIGINS` and restart backend.

---

## Local vs Production API URL

| Mode | API calls |
|------|-----------|
| Local `ng serve` | `/api/chat` via proxy → `localhost:8080` |
| Vercel production | `${API_BASE_URL}/api/chat` |

---

## Common issues

- **Blank/failing chat on Vercel:** `API_BASE_URL` missing at build time. Redeploy after setting it.
- **CORS blocked:** backend `RAG_CORS_ORIGINS` must include `https://your-app.vercel.app`.
- **Trying to put Spring Boot on Vercel:** not supported. Use Render/Railway/Fly/Cloud Run.
