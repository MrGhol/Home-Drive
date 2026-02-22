"""
Feature-rich media server for your home gallery.

Dependencies:
    pip install fastapi uvicorn pillow aiofiles python-multipart

Optional:
    - ffmpeg on PATH (for video thumbnail generation) [not required]
    - set MEDIA_API_KEY env var to enable simple API-key protection

Run:
    python -m uvicorn main:app --host 0.0.0.0 --port 8000
"""
import os
import re
import time
import json
import mimetypes
import hashlib
import subprocess
from pathlib import Path
from datetime import datetime, timezone
from typing import List, Optional, Dict, Tuple

from fastapi import FastAPI, HTTPException, Request, Depends, BackgroundTasks, Response, UploadFile, File
from fastapi.responses import FileResponse, StreamingResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

# Imaging + async file I/O
from PIL import Image, ImageOps
from PIL.ExifTags import TAGS
import aiofiles
from starlette.concurrency import run_in_threadpool

# -----------------------
# Config
# -----------------------
BASE_DIR = Path("media")
PHOTOS_DIR = BASE_DIR / "photos"
VIDEOS_DIR = BASE_DIR / "videos"
THUMB_DIR = BASE_DIR / ".thumbs"

for p in (BASE_DIR, PHOTOS_DIR, VIDEOS_DIR, THUMB_DIR):
    try:
        p.mkdir(parents=True, exist_ok=True)
    except Exception:
        pass

# Rate limiting
RATE_LIMIT = 2000
RATE_WINDOW = 30
_ip_counters: Dict[str, List[float]] = {}

# Upload limits
MAX_UPLOAD_BYTES = 500 * 1024 * 1024  # 500 MB per file

# Optional API key protection
API_KEY = os.environ.get("MEDIA_API_KEY")

# Thumbnails
THUMB_SIZE = (320, 320)

# Chunk size
CHUNK_SIZE = 1024 * 1024  # 1 MiB

app = FastAPI(title="Home Media Server")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # tighten in production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Serve web UI if present
WEB_DIR = Path("web")
if WEB_DIR.exists():
    app.mount("/web", StaticFiles(directory=str(WEB_DIR), html=True), name="web")


# -----------------------
# Utilities
# -----------------------
def require_api_key(request: Request):
    if not API_KEY:
        return True
    key = request.headers.get("x-api-key") or request.query_params.get("api_key")
    if key != API_KEY:
        raise HTTPException(status_code=401, detail="Missing or invalid API key")
    return True


def rate_limit(request: Request):
    ip = request.client.host
    now = time.time()

    times = _ip_counters.get(ip, [])
    # keep only recent timestamps
    times = [t for t in times if t >= now - RATE_WINDOW]
    if len(times) >= RATE_LIMIT:
        raise HTTPException(status_code=429, detail="Too many requests")
    times.append(now)
    _ip_counters[ip] = times

    # cleanup empty entries occasionally (prevent memory growth)
    if len(times) == 0 and ip in _ip_counters:
        del _ip_counters[ip]


def iso_ts(path: Path) -> str:
    return datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc).isoformat()


def human_size(n: int) -> str:
    for unit in ["B", "KB", "MB", "GB", "TB"]:
        if n < 1024:
            return f"{n:.0f}{unit}"
        n = n / 1024
    return f"{n:.0f}PB"


def mime_type_for(path: Path) -> str:
    guess, _ = mimetypes.guess_type(str(path))
    return guess or "application/octet-stream"


def ensure_in_base(rel: str) -> Path:
    p = (BASE_DIR / rel).resolve()
    if not str(p).startswith(str(BASE_DIR.resolve())):
        raise HTTPException(status_code=400, detail="Invalid path")
    return p


def safe_join(base: Path, *parts: str) -> Path:
    final_path = (base.joinpath(*parts)).resolve()
    if not str(final_path).startswith(str(base.resolve())):
        raise HTTPException(status_code=400, detail="Invalid path.")
    return final_path


def hash_for(s: str) -> str:
    return hashlib.sha1(s.encode("utf-8")).hexdigest()


def sanitize_filename(name: str) -> str:
    # keep basename and replace dangerous characters
    name = os.path.basename(name)
    # allow common safe chars, replace others with underscore
    name = re.sub(r'[^A-Za-z0-9.\-_ ]', '_', name)
    name = name.strip()
    if not name:
        raise HTTPException(status_code=400, detail="Invalid filename")
    return name


def unique_path(target_dir: Path, filename: str) -> Path:
    candidate = target_dir / filename
    if not candidate.exists():
        return candidate
    name, ext = os.path.splitext(filename)
    counter = 1
    while True:
        candidate = target_dir / f"{name}_{counter}{ext}"
        if not candidate.exists():
            return candidate
        counter += 1


# -----------------------
# EXIF helpers + metadata cache
# -----------------------
def extract_exif_datetime_sync(image_path: Path) -> Optional[str]:
    try:
        with Image.open(image_path) as img:
            exif_data = getattr(img, "_getexif", lambda: None)()
            if not exif_data:
                return None
            for tag_id, value in exif_data.items():
                tag = TAGS.get(tag_id, tag_id)
                if tag == "DateTimeOriginal":
                    # value often like "YYYY:MM:DD HH:MM:SS"
                    return value
    except Exception:
        return None
    return None


async def extract_exif_datetime(image_path: Path) -> Optional[str]:
    # offload pillow to threadpool
    return await run_in_threadpool(extract_exif_datetime_sync, image_path)


def build_metadata_cache(folder: Path) -> Dict[str, str]:
    metadata_file = folder / ".metadata.json"
    metadata: Dict[str, str] = {}

    if metadata_file.exists():
        try:
            with open(metadata_file, "r", encoding="utf-8") as f:
                metadata = json.load(f)
        except Exception:
            metadata = {}

    # remove entries that no longer exist
    current_files = {f.name for f in folder.glob("*.*") if not f.name.startswith(".")}
    metadata = {k: v for k, v in metadata.items() if k in current_files}

    dirty = False
    for file in folder.glob("*.*"):
        if file.suffix.lower() in [".jpg", ".jpeg", ".webp"] and not file.name.startswith("."):
            if file.name not in metadata:
                val = extract_exif_datetime_sync(file)  # synchronous; small and cached per-folder
                if val:
                    metadata[file.name] = val
                    dirty = True

    if dirty:
        try:
            with open(metadata_file, "w", encoding="utf-8") as f:
                json.dump(metadata, f)
        except Exception:
            pass

    return metadata


# -----------------------
# Endpoints: Basic & Health
# -----------------------
@app.get("/")
def home():
    return {"status": "running", "features": ["folders", "files", "media", "thumbnails", "range-serving"]}


@app.get("/health")
def health():
    return {"ok": True, "time": datetime.now(timezone.utc).isoformat()}


# -----------------------
# Folder listing + management
# -----------------------
@app.get("/folders/photos")
def list_photo_folders(request: Request, auth=Depends(require_api_key)):
    rate_limit(request)
    if not PHOTOS_DIR.exists():
        return []
    folders = []
    for p in sorted(PHOTOS_DIR.iterdir()):
        if p.is_dir():
            files = [x for x in sorted(p.iterdir()) if x.is_file() and not x.name.startswith(".")]
            size = sum(x.stat().st_size for x in files) if files else 0
            preview = None
            for x in files:
                mime = mime_type_for(x)
                if mime and mime.startswith("image/"):
                    preview = f"photos/{p.name}/{x.name}"
                    break
            folders.append({
                "name": p.name,
                "file_count": len(files),
                "size_bytes": size,
                "size_human": human_size(size),
                "preview": preview
            })
    return folders


class RenameRequest(BaseModel):
    new_name: str


@app.put("/folders/photos/{old_name}")
async def rename_folder(old_name: str, payload: RenameRequest, auth=Depends(require_api_key)):
    if not re.match(r"^[a-zA-Z0-9_\- ]+$", payload.new_name):
        raise HTTPException(status_code=400, detail="Invalid folder name")
    source = safe_join(PHOTOS_DIR, old_name)
    target = safe_join(PHOTOS_DIR, payload.new_name)
    if not source.exists() or not source.is_dir():
        raise HTTPException(status_code=404, detail="Source folder not found")
    if target.exists():
        raise HTTPException(status_code=409, detail="Target folder already exists")
    os.rename(source, target)
    return {"message": "Folder renamed successfully"}


@app.delete("/folders/photos/{folder_name}")
async def delete_folder(folder_name: str, auth=Depends(require_api_key)):
    folder = safe_join(PHOTOS_DIR, folder_name)
    if not folder.exists() or not folder.is_dir():
        raise HTTPException(status_code=404, detail="Folder not found")
    shutil.rmtree(folder)
    return {"message": "Folder deleted successfully"}


# -----------------------
# Files listing (with pagination) + search
# -----------------------
@app.get("/files/photos/{folder_name}")
def list_files_in_folder(folder_name: str, request: Request, skip: int = 0, limit: int = 100, auth=Depends(require_api_key)):
    rate_limit(request)
    folder_path = safe_join(PHOTOS_DIR, folder_name)
    if not folder_path.exists() or not folder_path.is_dir():
        raise HTTPException(status_code=404, detail="Folder not found")

    exif_cache = build_metadata_cache(folder_path)

    all_files = [p for p in sorted(folder_path.iterdir()) if p.is_file() and not p.name.startswith(".")]
    total = len(all_files)
    paginated = all_files[skip: skip + limit]

    files = []
    for p in paginated:
        rel = f"photos/{folder_name}/{p.name}"
        exif_date = exif_cache.get(p.name)
        files.append({
            "name": p.name,
            "relpath": rel,
            "size_bytes": p.stat().st_size,
            "size_human": human_size(p.stat().st_size),
            "mtime": iso_ts(p),
            "exif_date": exif_date,
            "mime": mime_type_for(p)
        })
    return {"total": total, "skip": skip, "limit": limit, "files": files}


@app.get("/files/videos")
def list_all_videos(request: Request, skip: int = 0, limit: int = 100, auth=Depends(require_api_key)):
    rate_limit(request)
    if not VIDEOS_DIR.exists():
        return {"total": 0, "skip": skip, "limit": limit, "files": []}
    all_videos = [p for p in sorted(VIDEOS_DIR.rglob("*")) if p.is_file() and not p.name.startswith(".")]
    total = len(all_videos)
    paginated = all_videos[skip: skip + limit]
    videos = []
    for p in paginated:
        rel = str(p.relative_to(BASE_DIR))
        videos.append({
            "name": p.name,
            "relpath": rel,
            "size_bytes": p.stat().st_size,
            "mtime": iso_ts(p),
            "mime": mime_type_for(p)
        })
    return {"total": total, "skip": skip, "limit": limit, "files": videos}


@app.get("/search")
def search(q: str, request: Request, auth=Depends(require_api_key)):
    rate_limit(request)
    pattern = q.lower()
    results = []
    # TODO: replace with an indexed approach (SQLite) when library grows
    for p in BASE_DIR.rglob("*"):
        if p.is_file() and not p.name.startswith(".") and pattern in p.name.lower():
            results.append({
                "name": p.name,
                "relpath": str(p.relative_to(BASE_DIR)),
                "size_bytes": p.stat().st_size,
                "mtime": iso_ts(p),
                "mime": mime_type_for(p)
            })
    return {"total": len(results), "files": results[:100]}


# -----------------------
# Thumbnail generation (non-blocking)
# -----------------------
def get_thumb_path(relpath: str) -> Path:
    key = hash_for(relpath)
    ext = ".webp"
    return THUMB_DIR / f"{key}{ext}"


def generate_thumbnail_sync(src_path: Path, out_path: Path):
    # synchronous helper to run inside a thread
    with Image.open(src_path) as im:
        im.load()
        im = ImageOps.exif_transpose(im)
        if im.mode not in ("RGB", "L"):
            im = im.convert("RGB")
        im.thumbnail(THUMB_SIZE, Image.LANCZOS)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        im.save(out_path, format="WEBP", quality=85, method=6)


@app.get("/thumbnail/{file_path:path}")
async def thumbnail(file_path: str, background: BackgroundTasks, request: Request, auth=Depends(require_api_key)):
    rate_limit(request)
    target = ensure_in_base(file_path)
    if not target.exists() or not target.is_file():
        raise HTTPException(status_code=404, detail="File not found")
    mime = mime_type_for(target)
    if not mime.startswith("image/"):
        raise HTTPException(status_code=400, detail="Thumbnail only supported for images")
    thumb_path = get_thumb_path(file_path)
    if thumb_path.exists() and thumb_path.stat().st_mtime >= target.stat().st_mtime:
        return FileResponse(thumb_path, media_type="image/webp")
    # generate in threadpool to avoid blocking event loop
    try:
        await run_in_threadpool(generate_thumbnail_sync, target, thumb_path)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Thumbnail error: {e}")
    return FileResponse(thumb_path, media_type="image/webp")


@app.get("/thumbnail/video/{file_path:path}")
def video_thumbnail(file_path: str, request: Request, auth=Depends(require_api_key)):
    rate_limit(request)
    src = ensure_in_base(file_path)
    if not src.exists() or not src.is_file():
        raise HTTPException(status_code=404, detail="File not found")
    if not shutil.which("ffmpeg"):
        raise HTTPException(status_code=501, detail="ffmpeg not found on PATH")
    out_thumb = get_thumb_path(file_path)
    if out_thumb.exists() and out_thumb.stat().st_mtime >= src.stat().st_mtime:
        return FileResponse(out_thumb)
    cmd = [
        "ffmpeg", "-y", "-i", str(src),
        "-vframes", "1", "-vf",
        f"scale='min({THUMB_SIZE[0]},iw)':'min({THUMB_SIZE[1]},ih)':force_original_aspect_ratio=decrease",
        "-f", "image2", str(out_thumb)
    ]
    try:
        subprocess.check_output(cmd, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as exc:
        raise HTTPException(status_code=500, detail=f"ffmpeg error")
    return FileResponse(out_thumb)


# -----------------------
# Range parsing + streaming (defensive)
# -----------------------
def parse_range(range_header: Optional[str], file_size: int) -> Optional[Tuple[int, int]]:
    if not range_header:
        return None
    m = re.match(r"bytes=(\d*)-(\d*)", range_header)
    if not m:
        return None
    start_s, end_s = m.groups()
    try:
        if start_s == "" and end_s == "":
            return None
        if start_s == "":
            # suffix bytes: last N bytes
            length = int(end_s)
            if length <= 0:
                return None
            start = max(0, file_size - length)
            end = file_size - 1
        else:
            start = int(start_s)
            end = int(end_s) if end_s else file_size - 1
        if start < 0 or end < 0 or start > end:
            return None
        end = min(end, file_size - 1)
        return start, end
    except Exception:
        return None


@app.get("/media/{file_path:path}")
async def serve_media(file_path: str, request: Request, auth=Depends(require_api_key)):
    rate_limit(request)
    fpath = ensure_in_base(file_path)
    if not fpath.exists() or not fpath.is_file():
        raise HTTPException(status_code=404, detail="File not found")
    file_size = fpath.stat().st_size
    range_header = request.headers.get("range")
    mime = mime_type_for(fpath)
    if range_header:
        rng = parse_range(range_header, file_size)
        if not rng:
            raise HTTPException(status_code=416, detail="Invalid Range")
        start, end = rng
        length = end - start + 1
        async def iterfile(path=fpath, start=start, end=end):
            async with aiofiles.open(path, mode="rb") as f:
                await f.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = await f.read(min(CHUNK_SIZE, remaining))
                    if not chunk:
                        break
                    remaining -= len(chunk)
                    yield chunk
        headers = {
            "Content-Range": f"bytes {start}-{end}/{file_size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(length),
            "Cache-Control": "public, max-age=86400",
        }
        return StreamingResponse(iterfile(), status_code=206, media_type=mime, headers=headers)
    # full async streaming
    async def full_stream(path=fpath):
        async with aiofiles.open(path, mode="rb") as f:
            while True:
                chunk = await f.read(CHUNK_SIZE)
                if not chunk:
                    break
                yield chunk
    headers = {"Accept-Ranges": "bytes", "Cache-Control": "public, max-age=86400"}
    return StreamingResponse(full_stream(), media_type=mime, headers=headers)


# -----------------------
# Upload endpoints (safe)
# -----------------------
@app.post("/upload/photos/{folder_name}")
async def upload_photo(folder_name: str, file: UploadFile = File(...), auth=Depends(require_api_key)):
    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="Only image uploads allowed")
    if not re.match(r"^[a-zA-Z0-9_\- ]+$", folder_name):
        raise HTTPException(status_code=400, detail="Invalid folder name")
    target_dir = safe_join(PHOTOS_DIR, folder_name)
    target_dir.mkdir(parents=True, exist_ok=True)
    filename = sanitize_filename(file.filename)
    dest = unique_path(target_dir, filename)
    total_written = 0
    try:
        async with aiofiles.open(dest, "wb") as out:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                total_written += len(chunk)
                if total_written > MAX_UPLOAD_BYTES:
                    # cleanup partial file
                    await out.close()
                    try:
                        dest.unlink(missing_ok=True)
                    except Exception:
                        pass
                    raise HTTPException(status_code=413, detail="File too large")
                await out.write(chunk)
    except HTTPException:
        raise
    except Exception as e:
        # cleanup partial file
        try:
            dest.unlink(missing_ok=True)
        except Exception:
            pass
        raise HTTPException(status_code=500, detail="Upload failed")
    return {"status": "success", "filename": dest.name}


@app.post("/upload/videos")
async def upload_video(file: UploadFile = File(...), auth=Depends(require_api_key)):
    if not file.content_type or not file.content_type.startswith("video/"):
        raise HTTPException(status_code=400, detail="Only video uploads allowed")
    target_dir = safe_join(VIDEOS_DIR, "")
    target_dir.mkdir(parents=True, exist_ok=True)
    filename = sanitize_filename(file.filename)
    dest = unique_path(target_dir, filename)
    total_written = 0
    try:
        async with aiofiles.open(dest, "wb") as out:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                total_written += len(chunk)
                if total_written > MAX_UPLOAD_BYTES:
                    await out.close()
                    try:
                        dest.unlink(missing_ok=True)
                    except Exception:
                        pass
                    raise HTTPException(status_code=413, detail="File too large")
                await out.write(chunk)
    except HTTPException:
        raise
    except Exception:
        try:
            dest.unlink(missing_ok=True)
        except Exception:
            pass
        raise HTTPException(status_code=500, detail="Upload failed")
    return {"status": "success", "filename": dest.name}


# -----------------------
# Convenience endpoint for quick testing
# -----------------------
@app.get("/open/{rel:path}")
def open_via_browser(rel: str, request: Request, auth=Depends(require_api_key)):
    rate_limit(request)
    p = ensure_in_base(rel)
    if not p.exists() or not p.is_file():
        raise HTTPException(status_code=404, detail="File not found")
    mime = mime_type_for(p)
    url = f"/media/{rel}"
    if mime.startswith("image/"):
        return Response(content=f'<img src="{url}" style="max-width:100%"/>', media_type="text/html")
    elif mime.startswith("video/"):
        return Response(content=f'<video controls src="{url}" style="max-width:100%"></video>', media_type="text/html")
    else:
        return FileResponse(p)


# -----------------------
# Error handlers
# -----------------------
@app.exception_handler(404)
def not_found_handler(request, exc):
    return JSONResponse(status_code=404, content={"error": "not found"})


@app.exception_handler(500)
def server_error_handler(request, exc):
    return JSONResponse(status_code=500, content={"error": "server error", "detail": str(exc)})