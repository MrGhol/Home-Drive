# Home Drive

Personal LAN media server for photos and videos. Runs entirely on your local network, serves a fast web gallery, and includes a native Android client that can auto-discover the server via mDNS.

## What This Project Does
- Hosts a private photo and video library on your LAN
- Serves a responsive web gallery with search, folders, upload, and a viewer
- Provides a REST API for uploads, browsing, thumbnails, and streaming
- Auto-advertises on the network so the Android app can find it

## Stack
- Backend: FastAPI + Uvicorn
- Media: Pillow (images), ffmpeg (optional video thumbnails), aiofiles
- Discovery: Zeroconf (mDNS)
- Frontend: Plain HTML/CSS/JS in `web/index.html`
- Android: Java + Retrofit + Glide + ExoPlayer

## Quick Start (Server)
### 1) Install dependencies
```bash
pip install fastapi uvicorn pillow aiofiles python-multipart zeroconf pillow-heif
```

Optional (video thumbnails):
```bash
# install ffmpeg and ensure it is on PATH
```

### 2) Run (development)
```bash
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

### 3) Run (production)
```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4
```

### 4) Open the web UI
- Browse to `http://<server-ip>:8000/web/`
- The app will use the current origin as the API base URL

## Configuration
Environment variables:
- `MEDIA_API_KEY`: Enables simple API key protection
- `PORT`: Overrides the default port (8000)
- `SERVER_NAME`: Sets the advertised server name for mDNS

If `MEDIA_API_KEY` is set, all API calls require `X-API-KEY` header (or `?api_key=`).

## Media Storage Layout
```
media/
  photos/
    <folder name>/
      image1.jpg
      image2.webp
  videos/
    clip1.mp4
  .thumbs/
    <hash>.webp
```

## Key Features
- Folder management (create, rename, delete)
- Paginated listing and filename search
- Image and video thumbnail generation with cache validation
- Range-based streaming for efficient video playback
- EXIF date extraction and per-folder metadata cache
- Simple rate limiting and path traversal protection

## API Overview
Primary endpoints (see FastAPI docs at `/docs` when the server is running):
- `GET /health`
- `GET /folders/photos`
- `GET /folders/browse?path=...`
- `GET /files/photos/{folder}`
- `GET /files/videos`
- `GET /search?q=...`
- `GET /thumbnail/{file_path}`
- `GET /media/{file_path}`
- `POST /upload/photos/{folder}`
- `POST /upload/videos`
- `DELETE /files/{file_path}`

## Android Client
The Android app lives in `Android/` and includes:
- mDNS discovery + manual IP configuration
- Photo and video browsing with a full-screen viewer
- Uploads with conflict handling
- Background upload and auto-backup workers

Notes:
- The app expects the server on the same LAN.
- The API key, if configured, is passed automatically.
- Some caching scaffolding exists (Room) but is not fully wired into the UI.

## Project Structure
- `main.py`: FastAPI server and media logic
- `web/index.html`: Web gallery UI
- `Android/`: Native Android client
- `media/`: Local media storage and thumbnails
- `server_id.txt`: Persisted server UUID for discovery and reconnection

## Security Notes
This project is designed for trusted local networks. If you expose it outside your LAN, consider:
- Enabling `MEDIA_API_KEY`
- Restricting CORS origins
- Putting it behind a reverse proxy with TLS and auth
