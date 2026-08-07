# Deploy Vicky Assist publicly (Netlify UI + Render API)

## Big picture

1. Put code on **GitHub**
2. Deploy **backend** on **Render** → get API URL
3. Deploy **frontend** on **Netlify** → point it to API URL
4. (Optional) add your own domain

```text
Users -> Netlify (Angular)
              |
              v
         Render (Spring Boot + Gemini + docs)
```

---

## Step 1 — Push to GitHub

```powershell
cd C:\Users\vignesh.sakthirajan\Documents\langchain4j-rag-spring-angular
git init
git add .
git commit -m "Deploy Vicky Assist to Netlify + Render"
git branch -M main
git remote add origin https://github.com/YOUR_USER/YOUR_REPO.git
git push -u origin main
```

Replace `YOUR_USER/YOUR_REPO`.

---

## Step 2 — Deploy API on Render

1. Open https://render.com and sign in (GitHub login).
2. **New** → **Web Service** → select your repo.
3. Settings:
   - **Name:** `vicky-assist-api`
   - **Root Directory:** `backend`
   - **Runtime:** Docker
   - **Instance type:** Free (ok for demo)
4. Environment variables:
   - `GEMINI_API_KEY` = your Gemini key
   - `GEMINI_MODEL` = `gemini-2.0-flash-lite`
   - `RAG_CORS_ORIGINS` = `http://localhost:4200`  
     (we will update this after Netlify URL is known)
5. Click **Create Web Service** and wait until live.
6. Copy API URL, example:
   - `https://vicky-assist-api.onrender.com`
7. Test in browser:
   - `https://vicky-assist-api.onrender.com/api/health`
   - Should show: `{"status":"ok"}`

---

## Step 3 — Deploy UI on Netlify

1. Open https://app.netlify.com and sign in (GitHub login).
2. **Add new site** → **Import an existing project** → pick the same GitHub repo.
3. Build settings (usually auto from `netlify.toml`):
   - **Base directory:** `frontend` (or leave blank if using root `netlify.toml`)
   - **Build command:** `npm run build:prod`
   - **Publish directory:** `dist/frontend/browser` (with base `frontend`)  
     or `frontend/dist/frontend/browser` (root config)
4. **Environment variables** → Add:
   - Key: `API_BASE_URL`
   - Value: `https://vicky-assist-api.onrender.com`  
     (your Render URL, **no** trailing slash)
5. Deploy site.
6. Copy Netlify URL, example:
   - `https://random-name-123.netlify.app`

---

## Step 4 — Connect CORS (important)

In Render → your API service → Environment:

Set:

```text
RAG_CORS_ORIGINS=https://YOUR-SITE.netlify.app,https://*.netlify.app,http://localhost:4200
```

Save → Render redeploys automatically.  
(`https://*.netlify.app` covers preview/prod Netlify hosts via Spring `allowedOriginPatterns`.)

Then open your Netlify URL and chat.

---

## Step 5 — Custom domain (optional)

### Frontend domain on Netlify
1. Netlify site → **Domain management** → **Add domain**
2. Add `www.yourdomain.com` (or apex)
3. Create DNS records Netlify shows
4. Wait for HTTPS

### API domain on Render
1. Render → **Custom Domains** → `api.yourdomain.com`
2. Add DNS record Render shows
3. Update:
   - Netlify env `API_BASE_URL=https://api.yourdomain.com`
   - Render env `RAG_CORS_ORIGINS=https://www.yourdomain.com,https://yourdomain.com,http://localhost:4200`
4. Redeploy both

---

## Quick test checklist

- [ ] `...onrender.com/api/health` works
- [ ] Netlify site opens
- [ ] Browser Network tab: `POST` goes to Render/API host
- [ ] No CORS error
- [ ] Chat returns an answer

---

## Common problems

| Problem | Fix |
|---------|-----|
| Chat fails on Netlify | `API_BASE_URL` missing/wrong → set and **redeploy** Netlify |
| CORS error | Add exact Netlify URL to Render `RAG_CORS_ORIGINS` |
| API slow first time | Render free tier sleeps; wait 30–60s |
| Build fails on Netlify | Ensure Node 20 and publish dir is `dist/frontend/browser` |
