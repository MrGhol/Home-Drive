"""
Home Drive – Personal LAN Media Server
=======================================
A self-hosted, offline-first media server for photos and videos.
Designed for both web (web/index.html) and a future native Android client.

Dependencies:
    pip install fastapi uvicorn pillow aiofiles python-multipart

Optional:
    - ffmpeg on PATH  → enables video thumbnail generation
    - MEDIA_API_KEY env var → enables simple API-key protection

Run (development):
    uvicorn main:app --host 0.0.0.0 --port 8000 --reload

Run (production):
    uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4
"""

# ──────────────────────────────────────────────
# Standard library
# ──────────────────────────────────────────────
import hashlib
import html
import json
import mimetypes
import os
import re
import shutil
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# ──────────────────────────────────────────────
# Third-party
# ──────────────────────────────────────────────
import aiofiles
from fastapi import (
    BackgroundTasks,
    Depends,
    FastAPI,
    File,
    HTTPException,
    Request,
    Response,
    UploadFile,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageOps
from PIL.ExifTags import TAGS
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

# ──────────────────────────────────────────────────────────────────────────────
# Config
# ──────────────────────────────────────────────────────────────────────────────

BASE_DIR   = Path("media")
PHOTOS_DIR = BASE_DIR / "photos"
VIDEOS_DIR = BASE_DIR / "videos"
THUMB_DIR  = BASE_DIR / ".thumbs"

for _p in (BASE_DIR, PHOTOS_DIR, VIDEOS_DIR, THUMB_DIR):
    _p.mkdir(parents=True, exist_ok=True)

# Optional API key (set MEDIA_API_KEY env var to enable)
API_KEY: Optional[str] = os.environ.get("MEDIA_API_KEY")

# Rate limiting  (per IP)
RATE_LIMIT  = 2000   # max requests
RATE_WINDOW = 30     # seconds

# Upload limits
MAX_PHOTO_BYTES = 200 * 1024 * 1024   # 200 MB
MAX_VIDEO_BYTES = 500 * 1024 * 1024   # 500 MB

# Thumbnails
THUMB_SIZE  = (320, 320)
CHUNK_SIZE  = 1024 * 1024   # 1 MiB streaming chunks

# Allowed MIME prefixes (checked server-side via Pillow / ffprobe)
ALLOWED_IMAGE_MIMES = {"image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif"}
ALLOWED_VIDEO_MIMES = {"video/mp4", "video/quicktime", "video/x-matroska", "video/webm", "video/avi", "video/x-msvideo"}


# ──────────────────────────────────────────────────────────────────────────────
# App
# ──────────────────────────────────────────────────────────────────────────────

app = FastAPI(
    title="Home Drive",
    description="Personal LAN media server – photos & videos, no cloud.",
    version="2.0.0",
)

app.add_middleware(
    CORSMiddleware,
    # "allow_origins=['*']" is fine for a private LAN.
    # To lock down: replace with your LAN subnet, e.g.
    #   allow_origins=["http://192.168.1.0/24"]
    # or list explicit origins like ["http://192.168.1.100:8000"]
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Serve the web UI if the folder exists
_WEB_DIR = Path("web")
if _WEB_DIR.exists():
    app.mount("/web", StaticFiles(directory=str(_WEB_DIR), html=True), name="web")


# ──────────────────────────────────────────────────────────────────────────────
# In-memory state
# ──────────────────────────────────────────────────────────────────────────────

# Rate limiter: { ip: [timestamp, ...] }
_ip_counters: Dict[str, List[float]] = {}

# Simple file-name index for fast search  { relative_path_str: { name, size, mtime, mime } }
_search_index: Dict[str, dict] = {}
_index_built_at: float = 0.0
INDEX_TTL = 60.0   # seconds before a full rebuild


# ──────────────────────────────────────────────────────────────────────────────
# Utility helpers
# ──────────────────────────────────────────────────────────────────────────────

def require_api_key(request: Request) -> bool:
    """FastAPI dependency – validates X-Api-Key header or ?api_key= param."""
    if not API_KEY:
        return True
    key = request.headers.get("x-api-key") or request.query_params.get("api_key")
    if key != API_KEY:
        raise HTTPException(status_code=401, detail="Missing or invalid API key")
    return True


def rate_limit(request: Request) -> None:
    """Simple sliding-window rate limiter stored in process memory.

    On every call:
    1. Trim timestamps outside the current window (keeps memory bounded per IP).
    2. Reject if the bucket is already full.
    3. Periodically sweep the whole dict to remove buckets that went empty
       after trimming (prevents unbounded growth from many distinct IPs).
    """
    ip     = request.client.host
    now    = time.monotonic()
    cutoff = now - RATE_WINDOW

    bucket = _ip_counters.get(ip)
    if bucket is None:
        _ip_counters[ip] = [now]
        return

    # Trim stale entries in-place on every single call
    bucket[:] = [t for t in bucket if t >= cutoff]

    # If the bucket drained to empty (IP was idle), evict it right now.
    if not bucket:
        del _ip_counters[ip]
        _ip_counters[ip] = [now]
        return

    if len(bucket) >= RATE_LIMIT:
        raise HTTPException(status_code=429, detail="Too many requests – slow down.")
    bucket.append(now)

    # Sweep dead IPs periodically (every ~200 requests across all IPs)
    # Using modulo on total dict size is cheap and avoids a counter variable.
    if len(_ip_counters) % 200 == 0:
        dead = [k for k, v in _ip_counters.items() if not v]
        for k in dead:
            del _ip_counters[k]


def iso_ts(path: Path) -> str:
    return datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc).isoformat()


def human_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} PB"


def mime_type_for(path: Path) -> str:
    guess, _ = mimetypes.guess_type(str(path))
    return guess or "application/octet-stream"


def safe_join(base: Path, *parts: str) -> Path:
    """Resolve path and reject any traversal outside base.

    Uses Path.is_relative_to() (Python 3.9+) which is the canonical,
    symlink-aware way to assert filesystem ancestry.
    Fallback for 3.8: `base_abs not in final.parents and final != base_abs`
    """
    try:
        final    = base.joinpath(*parts).resolve()
        base_abs = base.resolve()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid path.")
    # is_relative_to covers both "final == base" and "base is a parent of final"
    if not final.is_relative_to(base_abs):
        raise HTTPException(status_code=400, detail="Path traversal rejected.")
    return final


def ensure_in_base(rel: str) -> Path:
    return safe_join(BASE_DIR, rel)


def hash_for(s: str) -> str:
    return hashlib.sha1(s.encode()).hexdigest()


def sanitize_filename(name: Optional[str]) -> str:
    if not name:
        raise HTTPException(status_code=400, detail="Filename is missing.")
    name = os.path.basename(name)
    name = re.sub(r"[^A-Za-z0-9.\-_ ]", "_", name).strip()
    if not name or name.startswith("."):
        raise HTTPException(status_code=400, detail="Invalid filename.")
    return name


def unique_path(target_dir: Path, filename: str) -> Path:
    candidate = target_dir / filename
    if not candidate.exists():
        return candidate
    stem, ext = os.path.splitext(filename)
    for i in range(1, 10_000):
        candidate = target_dir / f"{stem}_{i}{ext}"
        if not candidate.exists():
            return candidate
    raise HTTPException(status_code=500, detail="Could not find a unique filename.")


def verify_image_file(path: Path) -> None:
    """Open with Pillow to confirm the file is actually an image."""
    try:
        with Image.open(path) as im:
            im.verify()
    except Exception:
        path.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail="File is not a valid image.")


def verify_video_file(path: Path) -> None:
    """Use ffprobe (if available) to confirm the file is a valid video container."""
    if not shutil.which("ffprobe"):
        return   # skip validation when ffprobe is absent
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=15,
        )
        if result.returncode != 0:
            raise ValueError("ffprobe rejected the file")
    except Exception:
        path.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail="File is not a valid video.")


# ──────────────────────────────────────────────────────────────────────────────
# EXIF helpers + per-folder metadata cache
# ──────────────────────────────────────────────────────────────────────────────

def _extract_exif_datetime_sync(image_path: Path) -> Optional[str]:
    try:
        with Image.open(image_path) as img:
            exif = getattr(img, "_getexif", lambda: None)()
            if not exif:
                return None
            for tag_id, value in exif.items():
                if TAGS.get(tag_id) == "DateTimeOriginal":
                    return str(value)
    except Exception:
        pass
    return None


async def extract_exif_datetime(image_path: Path) -> Optional[str]:
    return await run_in_threadpool(_extract_exif_datetime_sync, image_path)


def build_metadata_cache(folder: Path) -> Dict[str, str]:
    """
    Reads/writes .metadata.json inside the folder.
    Returns {filename: exif_date_string}.
    """
    metadata_file = folder / ".metadata.json"
    metadata: Dict[str, str] = {}

    if metadata_file.exists():
        try:
            with open(metadata_file, "r", encoding="utf-8") as f:
                metadata = json.load(f)
        except Exception:
            metadata = {}

    # Prune entries for deleted files
    current = {p.name for p in folder.glob("*.*") if not p.name.startswith(".")}
    metadata = {k: v for k, v in metadata.items() if k in current}

    dirty = False
    for p in folder.glob("*.*"):
        if p.suffix.lower() in {".jpg", ".jpeg", ".webp"} and not p.name.startswith("."):
            if p.name not in metadata:
                val = _extract_exif_datetime_sync(p)
                if val:
                    metadata[p.name] = val
                    dirty = True

    if dirty:
        try:
            with open(metadata_file, "w", encoding="utf-8") as f:
                json.dump(metadata, f)
        except Exception:
            pass

    return metadata


# ──────────────────────────────────────────────────────────────────────────────
# Search index
# ──────────────────────────────────────────────────────────────────────────────

def _rebuild_index() -> None:
    global _index_built_at
    new_index: Dict[str, dict] = {}
    for p in BASE_DIR.rglob("*"):
        if p.is_file() and not p.name.startswith("."):
            rel = str(p.relative_to(BASE_DIR))
            new_index[rel] = {
                "name":       p.name,
                "relpath":    rel,
                "size_bytes": p.stat().st_size,
                "size_human": human_size(p.stat().st_size),
                "mtime":      iso_ts(p),
                "mime":       mime_type_for(p),
            }
    _search_index.clear()
    _search_index.update(new_index)
    _index_built_at = time.monotonic()


def get_index() -> Dict[str, dict]:
    if time.monotonic() - _index_built_at > INDEX_TTL:
        _rebuild_index()
    return _search_index


# ──────────────────────────────────────────────────────────────────────────────
# Thumbnail helpers
# ──────────────────────────────────────────────────────────────────────────────

def get_thumb_path(relpath: str) -> Path:
    return THUMB_DIR / f"{hash_for(relpath)}.webp"


def _generate_image_thumb_sync(src: Path, out: Path) -> None:
    with Image.open(src) as im:
        im.load()
        im = ImageOps.exif_transpose(im)
        if im.mode not in ("RGB", "L"):
            im = im.convert("RGB")
        im.thumbnail(THUMB_SIZE, Image.LANCZOS)
        out.parent.mkdir(parents=True, exist_ok=True)
        im.save(out, format="WEBP", quality=85, method=6)


# ──────────────────────────────────────────────────────────────────────────────
# Range parsing
# ──────────────────────────────────────────────────────────────────────────────

def parse_range(header: Optional[str], size: int) -> Optional[Tuple[int, int]]:
    if not header:
        return None
    m = re.fullmatch(r"bytes=(\d*)-(\d*)", header.strip())
    if not m:
        return None
    start_s, end_s = m.groups()
    try:
        if not start_s and not end_s:
            return None
        if not start_s:               # suffix form: bytes=-N
            length = int(end_s)
            if length <= 0:
                return None
            start, end = max(0, size - length), size - 1
        else:
            start = int(start_s)
            end   = int(end_s) if end_s else size - 1
        if start < 0 or end < start:
            return None
        end = min(end, size - 1)
        return start, end
    except Exception:
        return None


# ──────────────────────────────────────────────────────────────────────────────
# Pydantic models
# ──────────────────────────────────────────────────────────────────────────────

class RenameRequest(BaseModel):
    new_name: str


# ──────────────────────────────────────────────────────────────────────────────
# Routes – housekeeping
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/", tags=["meta"])
def home():
    return {
        "name":     "Home Drive",
        "version":  "2.0.0",
        "status":   "running",
        "features": [
            "folder-management",
            "photo-upload",
            "video-upload",
            "streaming-with-range",
            "thumbnails",
            "search",
            "pagination",
            "exif-metadata",
        ],
    }


@app.get("/health", tags=["meta"])
def health():
    return {"ok": True, "time": datetime.now(timezone.utc).isoformat()}


# ──────────────────────────────────────────────────────────────────────────────
# Routes – folder management (photos)
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/folders/photos", tags=["folders"])
def list_photo_folders(request: Request, _auth=Depends(require_api_key)):
    rate_limit(request)
    if not PHOTOS_DIR.exists():
        return []

    folders = []
    for folder in sorted(PHOTOS_DIR.iterdir()):
        if not folder.is_dir():
            continue
        files = sorted(
            (f for f in folder.iterdir() if f.is_file() and not f.name.startswith(".")),
            key=lambda f: f.stat().st_mtime,
            reverse=True,
        )
        total_size = sum(f.stat().st_size for f in files)

        # Preview = most-recently-modified image
        preview = None
        for f in files:
            if mime_type_for(f).startswith("image/"):
                preview = f"photos/{folder.name}/{f.name}"
                break

        folders.append({
            "name":       folder.name,
            "file_count": len(files),
            "size_bytes": total_size,
            "size_human": human_size(total_size),
            "preview":    preview,
        })
    return folders


@app.post("/folders/photos/{folder_name}", tags=["folders"], status_code=201)
def create_folder(folder_name: str, _auth=Depends(require_api_key)):
    if not re.match(r"^[a-zA-Z0-9_\- ]+$", folder_name):
        raise HTTPException(status_code=400, detail="Folder name contains invalid characters.")
    folder = safe_join(PHOTOS_DIR, folder_name)
    if folder.exists():
        raise HTTPException(status_code=409, detail="Folder already exists.")
    folder.mkdir(parents=True)
    return {"message": "Folder created.", "name": folder_name}


@app.put("/folders/photos/{old_name}", tags=["folders"])
def rename_folder(old_name: str, payload: RenameRequest, _auth=Depends(require_api_key)):
    if not re.match(r"^[a-zA-Z0-9_\- ]+$", payload.new_name):
        raise HTTPException(status_code=400, detail="Invalid folder name.")
    source = safe_join(PHOTOS_DIR, old_name)
    target = safe_join(PHOTOS_DIR, payload.new_name)
    if not source.exists() or not source.is_dir():
        raise HTTPException(status_code=404, detail="Source folder not found.")
    if target.exists():
        raise HTTPException(status_code=409, detail="Target folder already exists.")
    os.rename(source, target)
    # Invalidate search index (was a comparison == before – now correctly assigns)
    global _index_built_at
    _index_built_at = 0.0
    return {"message": "Folder renamed.", "old": old_name, "new": payload.new_name}


@app.delete("/folders/photos/{folder_name}", tags=["folders"])
def delete_folder(folder_name: str, _auth=Depends(require_api_key)):
    folder = safe_join(PHOTOS_DIR, folder_name)
    if not folder.exists() or not folder.is_dir():
        raise HTTPException(status_code=404, detail="Folder not found.")
    shutil.rmtree(folder)
    return {"message": "Folder deleted.", "name": folder_name}


# ──────────────────────────────────────────────────────────────────────────────
# Routes – file listing (with pagination)
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/files/photos/{folder_name}", tags=["files"])
def list_photos_in_folder(
    folder_name: str,
    request: Request,
    skip:  int = 0,
    limit: int = 100,
    _auth=Depends(require_api_key),
):
    rate_limit(request)
    skip  = max(skip, 0)
    limit = max(1, min(limit, 200))
    folder = safe_join(PHOTOS_DIR, folder_name)
    if not folder.exists() or not folder.is_dir():
        raise HTTPException(status_code=404, detail="Folder not found.")

    exif_cache = build_metadata_cache(folder)
    all_files  = sorted(
        (p for p in folder.iterdir() if p.is_file() and not p.name.startswith(".")),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    total = len(all_files)
    page  = all_files[skip: skip + limit]

    return {
        "total": total,
        "skip":  skip,
        "limit": limit,
        "files": [
            {
                "name":       p.name,
                "relpath":    f"photos/{folder_name}/{p.name}",
                "size_bytes": p.stat().st_size,
                "size_human": human_size(p.stat().st_size),
                "mtime":      iso_ts(p),
                "exif_date":  exif_cache.get(p.name),
                "mime":       mime_type_for(p),
            }
            for p in page
        ],
    }


@app.get("/files/videos", tags=["files"])
def list_videos(
    request: Request,
    skip:  int = 0,
    limit: int = 100,
    _auth=Depends(require_api_key),
):
    rate_limit(request)
    skip  = max(skip, 0)
    limit = max(1, min(limit, 200))
    if not VIDEOS_DIR.exists():
        return {"total": 0, "skip": skip, "limit": limit, "files": []}

    all_videos = sorted(
        (p for p in VIDEOS_DIR.rglob("*") if p.is_file() and not p.name.startswith(".")),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    total = len(all_videos)
    page  = all_videos[skip: skip + limit]

    return {
        "total": total,
        "skip":  skip,
        "limit": limit,
        "files": [
            {
                "name":       p.name,
                "relpath":    str(p.relative_to(BASE_DIR)),
                "size_bytes": p.stat().st_size,
                "size_human": human_size(p.stat().st_size),
                "mtime":      iso_ts(p),
                "mime":       mime_type_for(p),
            }
            for p in page
        ],
    }


# ──────────────────────────────────────────────────────────────────────────────
# Routes – search
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/search", tags=["search"])
def search(q: str, request: Request, skip: int = 0, limit: int = 100, _auth=Depends(require_api_key)):
    rate_limit(request)
    skip  = max(skip, 0)
    limit = max(1, min(limit, 200))
    if not q or len(q) < 1:
        raise HTTPException(status_code=400, detail="Query must not be empty.")
    pattern = q.lower()
    index   = get_index()
    hits    = [v for v in index.values() if pattern in v["name"].lower()]
    hits.sort(key=lambda x: x["mtime"], reverse=True)
    return {"total": len(hits), "skip": skip, "limit": limit, "files": hits[skip: skip + limit]}


# ──────────────────────────────────────────────────────────────────────────────
# Routes – thumbnails
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/thumbnail/{file_path:path}", tags=["thumbnails"])
async def thumbnail_image(
    file_path: str,
    request: Request,
    _auth=Depends(require_api_key),
):
    rate_limit(request)
    target = ensure_in_base(file_path)
    if not target.exists() or not target.is_file():
        raise HTTPException(status_code=404, detail="File not found.")
    if not mime_type_for(target).startswith("image/"):
        raise HTTPException(status_code=400, detail="Thumbnails are only generated for images.")

    thumb = get_thumb_path(file_path)
    src_mtime = target.stat().st_mtime

    if thumb.exists() and thumb.stat().st_mtime >= src_mtime:
        return FileResponse(thumb, media_type="image/webp")

    try:
        await run_in_threadpool(_generate_image_thumb_sync, target, thumb)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Thumbnail generation failed: {exc}")

    return FileResponse(thumb, media_type="image/webp")


@app.get("/thumbnail/video/{file_path:path}", tags=["thumbnails"])
def thumbnail_video(
    file_path: str,
    request: Request,
    _auth=Depends(require_api_key),
):
    rate_limit(request)
    src = ensure_in_base(file_path)
    if not src.exists() or not src.is_file():
        raise HTTPException(status_code=404, detail="File not found.")
    if not shutil.which("ffmpeg"):
        raise HTTPException(status_code=501, detail="ffmpeg is not installed – video thumbnails unavailable.")

    thumb = get_thumb_path(file_path)
    if thumb.exists() and thumb.stat().st_mtime >= src.stat().st_mtime:
        return FileResponse(thumb, media_type="image/webp")

    thumb.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg", "-y", "-i", str(src),
        "-vframes", "1",
        "-vf", f"scale='min({THUMB_SIZE[0]},iw)':'min({THUMB_SIZE[1]},ih)':force_original_aspect_ratio=decrease",
        "-f", "image2", str(thumb),
    ]
    try:
        subprocess.check_output(cmd, stderr=subprocess.STDOUT, timeout=30)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise HTTPException(status_code=500, detail="ffmpeg failed to generate a thumbnail.")

    return FileResponse(thumb, media_type="image/webp")


# ──────────────────────────────────────────────────────────────────────────────
# Routes – media streaming (range-aware)
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/media/{file_path:path}", tags=["media"])
async def serve_media(file_path: str, request: Request, _auth=Depends(require_api_key)):
    rate_limit(request)
    fpath = ensure_in_base(file_path)
    if not fpath.exists() or not fpath.is_file():
        raise HTTPException(status_code=404, detail="File not found.")

    file_size    = fpath.stat().st_size
    mime         = mime_type_for(fpath)
    range_header = request.headers.get("range")
    cache_headers = {"Cache-Control": "public, max-age=86400", "Accept-Ranges": "bytes"}

    if range_header:
        rng = parse_range(range_header, file_size)
        if rng is None:
            raise HTTPException(status_code=416, detail="Range not satisfiable.")
        start, end = rng
        length = end - start + 1

        async def _range_stream():
            async with aiofiles.open(fpath, "rb") as f:
                await f.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = await f.read(min(CHUNK_SIZE, remaining))
                    if not chunk:
                        break
                    remaining -= len(chunk)
                    yield chunk

        return StreamingResponse(
            _range_stream(),
            status_code=206,
            media_type=mime,
            headers={
                **cache_headers,
                "Content-Range":  f"bytes {start}-{end}/{file_size}",
                "Content-Length": str(length),
            },
        )

    async def _full_stream():
        async with aiofiles.open(fpath, "rb") as f:
            while True:
                chunk = await f.read(CHUNK_SIZE)
                if not chunk:
                    break
                yield chunk

    return StreamingResponse(
        _full_stream(),
        media_type=mime,
        headers={**cache_headers, "Content-Length": str(file_size)},
    )


# ──────────────────────────────────────────────────────────────────────────────
# Routes – upload
# ──────────────────────────────────────────────────────────────────────────────

async def _stream_upload(dest: Path, upload: UploadFile, max_bytes: int) -> int:
    """Stream an upload to disk, enforcing size limit. Returns bytes written."""
    written = 0
    try:
        async with aiofiles.open(dest, "wb") as out:
            while True:
                chunk = await upload.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > max_bytes:
                    await out.close()
                    dest.unlink(missing_ok=True)
                    raise HTTPException(
                        status_code=413,
                        detail=f"File exceeds the {human_size(max_bytes)} limit."
                    )
                await out.write(chunk)
    except HTTPException:
        raise
    except Exception:
        dest.unlink(missing_ok=True)
        raise HTTPException(status_code=500, detail="Upload failed – server error.")
    return written


@app.post("/upload/photos/{folder_name}", tags=["upload"], status_code=201)
async def upload_photo(
    folder_name: str,
    file: UploadFile = File(...),
    _auth=Depends(require_api_key),
):
    # Validate declared MIME (fast, client-supplied)
    declared_mime = (file.content_type or "").split(";")[0].strip().lower()
    if declared_mime not in ALLOWED_IMAGE_MIMES and not declared_mime.startswith("image/"):
        raise HTTPException(status_code=400, detail="Only image files are accepted.")

    if not re.match(r"^[a-zA-Z0-9_\- ]+$", folder_name):
        raise HTTPException(status_code=400, detail="Invalid folder name.")

    target_dir = safe_join(PHOTOS_DIR, folder_name)
    target_dir.mkdir(parents=True, exist_ok=True)

    filename = sanitize_filename(file.filename)
    dest     = unique_path(target_dir, filename)

    await _stream_upload(dest, file, MAX_PHOTO_BYTES)

    # Server-side validation with Pillow
    verify_image_file(dest)

    # Invalidate search index
    global _index_built_at
    _index_built_at = 0.0

    print(f"[UPLOAD] photo → {dest}  ({human_size(dest.stat().st_size)})")

    return {
        "status":   "success",
        "filename": dest.name,
        "folder":   folder_name,
        "relpath":  f"photos/{folder_name}/{dest.name}",
        "size":     human_size(dest.stat().st_size),
    }


@app.post("/upload/videos", tags=["upload"], status_code=201)
async def upload_video(
    file: UploadFile = File(...),
    _auth=Depends(require_api_key),
):
    declared_mime = (file.content_type or "").split(";")[0].strip().lower()
    if declared_mime not in ALLOWED_VIDEO_MIMES and not declared_mime.startswith("video/"):
        raise HTTPException(status_code=400, detail="Only video files are accepted.")

    target_dir = VIDEOS_DIR
    target_dir.mkdir(parents=True, exist_ok=True)

    filename = sanitize_filename(file.filename)
    dest     = unique_path(target_dir, filename)

    await _stream_upload(dest, file, MAX_VIDEO_BYTES)

    # Server-side validation with ffprobe (no-op if ffprobe is absent)
    verify_video_file(dest)

    global _index_built_at
    _index_built_at = 0.0

    print(f"[UPLOAD] video → {dest}  ({human_size(dest.stat().st_size)})")

    return {
        "status":   "success",
        "filename": dest.name,
        "relpath":  f"videos/{dest.name}",
        "size":     human_size(dest.stat().st_size),
    }


# ──────────────────────────────────────────────────────────────────────────────
# Routes – single-file delete
# ──────────────────────────────────────────────────────────────────────────────

@app.delete("/files/{file_path:path}", tags=["files"])
def delete_file(file_path: str, _auth=Depends(require_api_key)):
    target = ensure_in_base(file_path)
    if not target.exists() or not target.is_file():
        raise HTTPException(status_code=404, detail="File not found.")

    # Remove cached thumbnail if it exists
    thumb = get_thumb_path(file_path)
    thumb.unlink(missing_ok=True)

    target.unlink()

    global _index_built_at
    _index_built_at = 0.0

    return {"message": "File deleted.", "path": file_path}


# ──────────────────────────────────────────────────────────────────────────────
# Routes – quick browser preview (web UI helper)
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/open/{rel:path}", tags=["web"])
def open_in_browser(rel: str, request: Request, _auth=Depends(require_api_key)):
    rate_limit(request)
    p = ensure_in_base(rel)
    if not p.exists() or not p.is_file():
        raise HTTPException(status_code=404, detail="File not found.")

    mime      = mime_type_for(p)
    safe_rel  = html.escape(rel)          # prevent XSS
    media_url = f"/media/{safe_rel}"

    if mime.startswith("image/"):
        body = f'<img src="{media_url}" style="max-width:100%;height:auto" alt="{html.escape(p.name)}" />'
    elif mime.startswith("video/"):
        body = (
            f'<video controls src="{media_url}" style="max-width:100%" preload="metadata">'
            f'Your browser does not support HTML5 video.</video>'
        )
    else:
        return FileResponse(p)

    return Response(
        content=f'<!DOCTYPE html><html><head><meta charset="utf-8"><title>{html.escape(p.name)}</title>'
                f'<style>body{{margin:0;background:#111;display:flex;justify-content:center;align-items:center;min-height:100vh}}</style>'
                f'</head><body>{body}</body></html>',
        media_type="text/html",
    )


# ──────────────────────────────────────────────────────────────────────────────
# Error handlers
# ──────────────────────────────────────────────────────────────────────────────

@app.exception_handler(404)
def not_found(_req, _exc):
    return JSONResponse(status_code=404, content={"error": "not found"})


@app.exception_handler(500)
def server_error(_req, exc):
    return JSONResponse(status_code=500, content={"error": "server error", "detail": str(exc)})


# ──────────────────────────────────────────────────────────────────────────────
# Startup
# ──────────────────────────────────────────────────────────────────────────────

@app.on_event("startup")
def on_startup():
    """Pre-build the search index on startup so the first search is instant."""
    _rebuild_index()
    print(f"[Home Drive] Index built – {len(_search_index)} files indexed.")
    if API_KEY:
        print("[Home Drive] API key protection is ENABLED.")
    else:
        print("[Home Drive] API key protection is DISABLED (set MEDIA_API_KEY to enable).")