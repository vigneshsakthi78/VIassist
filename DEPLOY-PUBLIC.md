# Make Vicky Assist fully public

You need **two public URLs**:

| Piece | Host | Example |
|-------|------|---------|
| Frontend (Angular) | **Vercel** | `https://vicky-assist.vercel.app` or `https://chat.yourdomain.com` |
| Backend (Spring Boot) | **Render** (Docker) | `https://vicky-assist-api.onrender.com` or `https://api.yourdomain.com` |

Vercel cannot run Java Spring Boot.

---

## 0) Push code to GitHub

```powershell
cd C:\Users\vignesh.sakthirajan\Documents\langchain4j-rag-spring-angular
git init
git add .
git commit -m "Public deploy ready for Vercel + Render"
git branch -M main
git remote add origin https://github.com/<your-user>/<your-repo>.git
git push -u origin main
```

---

## 1) Deploy backend on Render (public API)

1. Go to [https://render.com](https://render.com) → **New** → **Blueprint** (uses `render.yaml`)  
   or **Web Service** → connect repo → Root Directory `backend` → Runtime **Docker**.
2. Set environment variables:
   - `GEMINI_API_KEY` = your Gemini key
   - `GEMINI_MODEL` = `gemini-2.0-flash-lite` (optional)
   - `RAG_CORS_ORIGINS` =  
     `https://vicky-assist.vercel.app,https://*.vercel.app,http://localhost:4200`  
     (update after you know the exact Vercel URL / custom domain)
3. Deploy and copy the service URL, e.g.  
   `https://vicky-assist-api.onrender.com`
4. Test:
   - `https://vicky-assist-api.onrender.com/api/health` → `{"status":"ok"}`

> Free Render services may sleep when idle (first request can be slow).

---

## 2) Deploy frontend on Vercel (public UI)

1. [https://vercel.com](https://vercel.com) → **Add New Project** → import the same GitHub repo.
2. Settings:
   - Use root `vercel.json` (or set Root Directory to `frontend`)
3. Environment variable (Production + Preview):
   - `API_BASE_URL` = `https://vicky-assist-api.onrender.com`  
     *(no trailing slash)*
4. Deploy → open `https://your-app.vercel.app`
5. Update Render `RAG_CORS_ORIGINS` to include that exact Vercel URL, then redeploy API if needed.

---

## 3) Attach your own custom domain (optional)

### Frontend domain (example `www.yourdomain.com` or `chat.yourdomain.com`)
1. Vercel project → **Settings** → **Domains** → add domain.
2. At your DNS provider, add the records Vercel shows (usually CNAME to `cname.vercel-dns.com`).
3. Wait for SSL to become **Valid**.

### Backend domain (example `api.yourdomain.com`)
1. Render service → **Settings** → **Custom Domains** → add `api.yourdomain.com`.
2. Add the DNS record Render shows.
3. Update:
   - Vercel env `API_BASE_URL=https://api.yourdomain.com`
   - Render env `RAG_CORS_ORIGINS=https://www.yourdomain.com,https://yourdomain.com,https://*.vercel.app,http://localhost:4200`
4. Redeploy **both** frontend and backend.

---

## 4) Final checklist

- [ ] `https://api.../api/health` works in browser
- [ ] Vercel site loads
- [ ] Chat works (Network tab shows POST to your API host)
- [ ] No CORS errors
- [ ] `GEMINI_API_KEY` is set only in Render (never commit it)

---

## Architecture

```text
Public users
   |
   v
https://www.yourdomain.com     (Vercel - Angular)
   |
   |  POST /api/chat
   v
https://api.yourdomain.com     (Render - Spring Boot + Gemini + RAG docs)
```
