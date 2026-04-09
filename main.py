"""
Home Drive – Personal LAN Media Server
=======================================
A self-hosted, offline-first media server for photos and videos.
Designed for both web (web/index.html) and a future native Android client.

Dependencies:
    pip install fastapi uvicorn pillow aiofiles python-multipart zeroconf pillow-heif

Optional:
    - ffmpeg on PATH  → enables video thumbnail generation
    - MEDIA_API_KEY env var → enables simple API-key protection

Run (development):
    python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload

Run (production):
    uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4
"""

# ──────────────────────────────────────────────
# Standard library
# ──────────────────────────────────────────────
import asyncio
import hashlib
import html
import json
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import tempfile
import time
import uuid
from contextlib import asynccontextmanager
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
from PIL import Image, ImageFile, ImageOps
from PIL.ExifTags import TAGS
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool
from zeroconf import ServiceInfo, Zeroconf

# Let Pillow decode truncated / partially-transferred images instead of crashing.
# This alone fixes the majority of phone-transfer JPEG issues.
ImageFile.LOAD_TRUNCATED_IMAGES = True

# Allow large images but keep decompression-bomb protection enabled by default.
# Set MAX_IMAGE_PIXELS=0 to disable entirely, or provide a custom integer.
_max_pixels_env = os.environ.get("MAX_IMAGE_PIXELS")
if _max_pixels_env is None:
    Image.MAX_IMAGE_PIXELS = 200_000_000  # ~200 MP default limit
elif _max_pixels_env.strip() == "0":
    Image.MAX_IMAGE_PIXELS = None
else:
    try:
        Image.MAX_IMAGE_PIXELS = int(_max_pixels_env)
    except ValueError:
        Image.MAX_IMAGE_PIXELS = 200_000_000

# HEIC/HEIF support – must be registered before any Image.open() call.
# Install with:  pip install pillow-heif
try:
    from pillow_heif import register_heif_opener
    register_heif_opener()
except ImportError:
    pass   # HEIC uploads will still be accepted; thumbnails will gracefully fall back

# ──────────────────────────────────────────────────────────────────────────────
# Config
# ──────────────────────────────────────────────────────────────────────────────

_default_media_dir = Path(__file__).resolve().parent / "media"
BASE_DIR   = Path(os.environ.get("MEDIA_DIR", str(_default_media_dir))).resolve()
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


def _generate_identity_thumb(src: Path, out: Path) -> None:
    """Generate a unique, file-specific placeholder thumbnail when real
    decoding fails completely.

    Each file gets a deterministic colour derived from its name hash, so
    every broken image looks different in the grid — never a sea of identical
    grey tiles.  The file extension and a truncated filename are drawn on top
    so the user can still identify what the file is.

    This is Stage 3 after Pillow and ffmpeg both fail.  It uses only basic
    Pillow drawing — no decoders, no external tools — so it cannot fail.
    """
    from PIL import ImageDraw

    # Derive a stable hue from the filename (0–360°)
    name_hash  = int(hashlib.sha1(src.name.encode()).hexdigest(), 16)
    hue        = (name_hash % 360) / 360.0
    # Dark, desaturated background so it reads clearly as "not a real photo"
    import colorsys
    r, g, b    = colorsys.hsv_to_rgb(hue, 0.35, 0.28)
    bg_colour  = (int(r * 255), int(g * 255), int(b * 255))
    # Lighter accent for text and icon
    ra, ga, ba = colorsys.hsv_to_rgb(hue, 0.25, 0.75)
    fg_colour  = (int(ra * 255), int(ga * 255), int(ba * 255))

    w, h = THUMB_SIZE
    img  = Image.new("RGB", (w, h), bg_colour)
    draw = ImageDraw.Draw(img)

    # Draw a simple broken-image icon (two overlapping rectangles + diagonal)
    cx, cy = w // 2, h // 2
    bw, bh = w // 3, h // 4
    draw.rectangle([cx - bw, cy - bh, cx + bw, cy + bh],
                   outline=fg_colour, width=3)
    draw.line([cx - bw, cy - bh, cx + bw, cy + bh], fill=fg_colour, width=2)

    # Extension badge in the top-left corner
    ext = src.suffix.upper().lstrip(".")[:5] or "???"
    draw.rectangle([8, 8, 8 + len(ext) * 9 + 10, 30], fill=fg_colour)
    draw.text((13, 11), ext, fill=bg_colour)

    # Truncated filename at the bottom
    name = src.stem
    if len(name) > 22:
        name = name[:19] + "…"
    draw.text((w // 2 - len(name) * 4, h - 28), name, fill=fg_colour)

    out.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_str = tempfile.mkstemp(suffix=".webp", dir=out.parent)
    os.close(fd)
    tmp = Path(tmp_str)
    try:
        img.save(tmp, format="WEBP", quality=80)
        tmp.replace(out)
    except Exception:
        tmp.unlink(missing_ok=True)
        raise

# Allowed MIME prefixes (checked server-side via Pillow / ffprobe)
ALLOWED_IMAGE_MIMES = {"image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif"}
ALLOWED_VIDEO_MIMES = {"video/mp4", "video/quicktime", "video/x-matroska", "video/webm", "video/avi", "video/x-msvideo"}


# ──────────────────────────────────────────────────────────────────────────────
# mDNS / Auto-Discovery Config
# ──────────────────────────────────────────────────────────────────────────────

SERVER_PORT      = int(os.environ.get("PORT", 8000))
SERVER_NAME      = os.environ.get("SERVER_NAME", "HomeDrive")
SERVER_VERSION   = "2.0.0"
MDNS_SERVICE_TYPE = "_http._tcp.local."
SERVER_ID_FILE   = Path("server_id.txt")


def _load_or_create_server_id() -> str:
    """Return a stable UUID for this server instance, persisted to disk."""
    if SERVER_ID_FILE.exists():
        sid = SERVER_ID_FILE.read_text().strip()
        if sid:
            return sid
    sid = str(uuid.uuid4())
    SERVER_ID_FILE.write_text(sid)
    return sid


def _get_lan_ip() -> str:
    """Return the primary LAN IP (not 127.x, not 0.0.0.0).

    Technique: open a UDP socket toward a public address (no packet sent)
    and read back which local interface the OS would use.  Works on Linux,
    macOS, and Windows without requiring root or listing all interfaces.
    """
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
        if not ip.startswith("127."):
            return ip
    except Exception:
        pass
    # Fallback: hostname resolution
    try:
        ip = socket.gethostbyname(socket.gethostname())
        if not ip.startswith("127."):
            return ip
    except Exception:
        pass
    return "127.0.0.1"


SERVER_ID: str = _load_or_create_server_id()

# Module-level zeroconf state (populated during startup, cleared on shutdown)
_zeroconf: Optional[Zeroconf] = None
_zc_service_info: Optional[ServiceInfo] = None


def _register_mdns() -> None:
    """Register the HomeDrive mDNS/Bonjour service on the LAN."""
    global _zeroconf, _zc_service_info

    lan_ip = _get_lan_ip()
    service_name = f"{SERVER_NAME}.{MDNS_SERVICE_TYPE}"

    _zc_service_info = ServiceInfo(
        type_=MDNS_SERVICE_TYPE,
        name=service_name,
        addresses=[socket.inet_aton(lan_ip)],
        port=SERVER_PORT,
        properties={
            b"name":       SERVER_NAME.encode(),
            b"version":    SERVER_VERSION.encode(),
            b"api":        b"v1",
            b"server_id":  SERVER_ID.encode(),
        },
        server=f"{SERVER_NAME}.local.",
    )

    _zeroconf = Zeroconf()
    _zeroconf.register_service(_zc_service_info)
    print(f"[mDNS] Registered '{service_name}' → {lan_ip}:{SERVER_PORT}")


def _unregister_mdns() -> None:
    """Unregister the mDNS service so stale entries don't linger on the LAN."""
    global _zeroconf, _zc_service_info
    if _zeroconf and _zc_service_info:
        try:
            _zeroconf.unregister_service(_zc_service_info)
            _zeroconf.close()
            print("[mDNS] Service unregistered.")
        except Exception as exc:
            print(f"[mDNS] Unregister warning: {exc}")
        finally:
            _zeroconf = None
            _zc_service_info = None


# ──────────────────────────────────────────────────────────────────────────────
# App
# ──────────────────────────────────────────────────────────────────────────────

# ──────────────────────────────────────────────────────────────────────────────
# Startup / Shutdown  (FastAPI lifespan)
# ──────────────────────────────────────────────────────────────────────────────
# NOTE: _rebuild_index, _search_index, and _register_mdns/_unregister_mdns are
# all defined later in this module.  Python only executes function bodies at
# call time, so forward references here are perfectly safe.

def _clear_identity_placeholder_thumbs() -> int:
    """Delete cached thumbnails that are identity placeholders (tiny solid-colour
    WebP files generated when all real decode attempts failed). They will be
    re-generated on next request — ideally now succeeding with the ffmpeg fallback.

    Heuristic: real photo thumbnails are almost always >4 KB. The identity
    placeholder is a simple flat-colour rectangle with two lines; it compresses
    to well under 1 KB. We use 2 KB as a conservative threshold.
    """
    PLACEHOLDER_MAX_BYTES = 2048
    removed = 0
    for thumb in THUMB_DIR.glob("*.webp"):
        try:
            if thumb.stat().st_size < PLACEHOLDER_MAX_BYTES:
                thumb.unlink()
                removed += 1
        except Exception:
            pass
    return removed


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # ── Startup ──────────────────────────────────────────────────────────────
    removed = _clear_identity_placeholder_thumbs()
    if removed:
        print(f"[Home Drive] Cleared {removed} stale placeholder thumbnail(s) — will regenerate on next request.")

    _rebuild_index()
    print(f"[Home Drive] Index built – {len(_search_index)} files indexed.")
    if API_KEY:
        print("[Home Drive] API key protection is ENABLED.")
    else:
        print("[Home Drive] API key protection is DISABLED (set MEDIA_API_KEY to enable).")

    # mDNS registration runs in a thread (zeroconf does blocking I/O)
    await run_in_threadpool(_register_mdns)

    yield  # ← server is live and handling requests

    # ── Shutdown ─────────────────────────────────────────────────────────────
    await run_in_threadpool(_unregister_mdns)
    print("[Home Drive] Shutdown complete.")


app = FastAPI(
    title="Home Drive",
    description="Personal LAN media server – photos & videos, no cloud.",
    version="2.0.0",
    lifespan=lifespan,
)

_cors_env = os.environ.get("CORS_ALLOW_ORIGINS")
_cors_origins = []
if _cors_env:
    _cors_origins = [o.strip() for o in _cors_env.split(",") if o.strip()]

app.add_middleware(
    CORSMiddleware,
    # Set CORS_ALLOW_ORIGINS="http://192.168.1.10:8000,https://example.com"
    # to explicitly allow cross-origin access. Default: no cross-origin access.
    allow_origins=_cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Serve the web UI if the folder exists
_WEB_DIR = Path("web")
if _WEB_DIR.exists():
    app.mount("/web", StaticFiles(directory=str(_WEB_DIR), html=True), name="web")


# ──────────────────────────────────────────────────────────────────────────────
# Health / Discovery endpoint
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/health", tags=["discovery"])
def health_check():
    """Lightweight endpoint used by Android after mDNS resolution to confirm
    it has found a genuine HomeDrive server (not just any HTTP service)."""
    return {
        "status":     "ok",
        "name":       SERVER_NAME,
        "version":    SERVER_VERSION,
        "server_id":  SERVER_ID,
        "lan_ip":     _get_lan_ip(),
    }


# ──────────────────────────────────────────────────────────────────────────────
# In-memory state
# ──────────────────────────────────────────────────────────────────────────────

# Thumbnail generation locks: { thumb_cache_key: asyncio.Lock }
# Prevents concurrent requests for the same image from generating the thumbnail
# twice simultaneously and racing on the output file write.
# Using per-key locks (not one global lock) so requests for *different* images
# never block each other.
_thumb_locks: Dict[str, asyncio.Lock] = {}

# Global ffmpeg concurrency limit.
# Caps simultaneous video-thumbnail processes so a burst of requests can't
# saturate CPU on weak hardware.  Value of 2 is conservative and safe for
# any LAN server; raise it if your machine has spare cores to spare.
FFMPEG_SEMAPHORE = asyncio.Semaphore(2)

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


def _build_metadata_cache_sync(folder: Path) -> Dict[str, str]:
    """
    Synchronous core – always runs in a threadpool, never on the event loop.

    Strategy:
      1. Load existing .metadata.json (already-extracted EXIF).
      2. Prune entries whose files were deleted.
      3. Extract EXIF only for NEW files not yet in the cache.
      4. Persist the updated cache back to disk (only when dirty).

    This means the *first* request for a large folder pays the full cost once;
    every subsequent request (or request after a single upload) is O(new files).
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


async def build_metadata_cache(folder: Path) -> Dict[str, str]:
    """Async wrapper – offloads all disk I/O to a threadpool."""
    return await run_in_threadpool(_build_metadata_cache_sync, folder)


def _update_metadata_cache_sync(folder: Path, filename: str) -> None:
    """
    Called immediately after a successful photo upload.
    Adds just the one new file to .metadata.json without scanning the whole folder.
    This warms the cache so the next list request is instant.
    """
    p = folder / filename
    if p.suffix.lower() not in {".jpg", ".jpeg", ".webp"}:
        return
    val = _extract_exif_datetime_sync(p)
    if not val:
        return
    metadata_file = folder / ".metadata.json"
    metadata: Dict[str, str] = {}
    if metadata_file.exists():
        try:
            with open(metadata_file, "r", encoding="utf-8") as f:
                metadata = json.load(f)
        except Exception:
            metadata = {}
    metadata[filename] = val
    try:
        with open(metadata_file, "w", encoding="utf-8") as f:
            json.dump(metadata, f)
    except Exception:
        pass


# ──────────────────────────────────────────────────────────────────────────────
# Search index  (incremental)
# ──────────────────────────────────────────────────────────────────────────────
#
# Architecture:
#   _search_index  – the live dict, always up-to-date after mutations
#   _index_built_at – timestamp of the last full rebuild
#   INDEX_TTL       – full rebuild period for drift correction (e.g. manual
#                     filesystem changes that bypass the API)
#
# Mutations (upload / delete / rename) call the surgical helpers below
# instead of wiping and re-scanning everything.  Full rglob only runs:
#   • once on startup
#   • after INDEX_TTL seconds (safety net for out-of-band changes)

def _make_index_entry(p: Path) -> dict:
    rel = str(p.relative_to(BASE_DIR))
    return {
        "name":       p.name,
        "relpath":    rel,
        "size_bytes": p.stat().st_size,
        "size_human": human_size(p.stat().st_size),
        "mtime":      iso_ts(p),
        "mime":       mime_type_for(p),
    }


def _rebuild_index() -> None:
    """Full scan – O(n files). Called at startup and periodically for drift."""
    global _index_built_at
    new_index: Dict[str, dict] = {}
    base_abs = BASE_DIR.resolve()
    for p in BASE_DIR.rglob("*"):
        if not p.is_file():
            continue
        if p.name.startswith("."):
            continue
        try:
            rel_path = p.resolve().relative_to(base_abs)
        except Exception:
            continue
        # Skip any hidden directory segments (e.g., ".thumbs")
        if any(part.startswith(".") for part in rel_path.parts):
            continue
        rel = str(rel_path)
        new_index[rel] = _make_index_entry(p)
    _search_index.clear()
    _search_index.update(new_index)
    _index_built_at = time.monotonic()


def _index_add(path: Path) -> None:
    """Add or update a single file – O(1)."""
    if path.exists() and path.is_file() and not path.name.startswith("."):
        rel = str(path.relative_to(BASE_DIR))
        _search_index[rel] = _make_index_entry(path)


def _index_remove(rel: str) -> None:
    """Remove a single file by its relative path string – O(1)."""
    _search_index.pop(rel, None)


def _index_remove_folder(folder_rel_prefix: str) -> None:
    """
    Remove all entries under a folder – O(entries in folder).
    prefix should be like 'photos/Vacation' (no trailing slash).
    """
    prefix = folder_rel_prefix.rstrip("/") + "/"
    stale  = [k for k in _search_index if k.startswith(prefix)]
    for k in stale:
        del _search_index[k]


def _index_rename_folder(old_rel: str, new_rel: str) -> None:
    """
    Re-key all entries under old_rel to new_rel – O(entries in folder).
    Avoids a full rglob on rename.
    """
    old_prefix = old_rel.rstrip("/") + "/"
    new_prefix = new_rel.rstrip("/") + "/"
    renames = {k: v for k, v in _search_index.items() if k.startswith(old_prefix)}
    for old_key, entry in renames.items():
        del _search_index[old_key]
        new_key            = new_prefix + old_key[len(old_prefix):]
        entry["relpath"]   = new_key
        _search_index[new_key] = entry


def get_index() -> Dict[str, dict]:
    """Return the live index, triggering a full rebuild only on TTL expiry."""
    if time.monotonic() - _index_built_at > INDEX_TTL:
        _rebuild_index()
    return _search_index


# ──────────────────────────────────────────────────────────────────────────────
# Thumbnail helpers
# ──────────────────────────────────────────────────────────────────────────────

def get_thumb_path(relpath: str) -> Path:
    return THUMB_DIR / f"{hash_for(relpath)}.webp"


def _safe_file_response(path: Path, media_type: str) -> FileResponse:
    """Serve *path* as a FileResponse.  If it vanished between the exists()
    check and now (race condition), raises 404."""
    if path.exists():
        return FileResponse(str(path), media_type=media_type)
    raise HTTPException(status_code=404, detail="File not found.")


def _normalise_image_mode(im: Image.Image) -> Image.Image:
    """Convert any Pillow mode to RGB or RGBA, which WebP can encode natively.

    Handles every exotic mode that arrives from real-world phone photos:
      P  (palette / indexed)  → RGBA first to preserve transparency, then RGB
      PA (palette + alpha)    → RGBA → RGB
      CMYK                    → RGB  (Pillow handles the inversion correctly)
      I / I;16 (16-bit grey)  → L (8-bit) → RGB
      F  (32-bit float grey)  → L → RGB
      L  (8-bit grey)         → RGB
      RGBA                    → keep as-is (WebP supports it natively)
      RGB                     → keep as-is
    """
    if im.mode in ("P", "PA"):
        im = im.convert("RGBA")
    if im.mode in ("I", "I;16", "F"):
        # Normalise 16-bit / float to 8-bit grey before colour conversion
        im = im.point(lambda px: px * (1 / 256)).convert("L")
    if im.mode not in ("RGB", "RGBA"):
        im = im.convert("RGB")
    return im



def _write_webp(img: Image.Image, out: Path, quality: int = 85) -> None:
    """Normalise *img* and atomically write it as WebP to *out*."""
    img = _normalise_image_mode(img)
    img.info.pop("exif", None)
    img.info.pop("icc_profile", None)
    out.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_str = tempfile.mkstemp(suffix=".webp", dir=out.parent)
    os.close(fd)
    tmp = Path(tmp_str)
    try:
        img.save(tmp, format="WEBP", quality=quality)
        tmp.replace(out)
    except Exception:
        tmp.unlink(missing_ok=True)
        raise


def _validate_webp(path: Path) -> bool:
    """Return True if *path* is a valid decodable WebP file we can serve.

    Criteria:
      - file exists and non-zero size
      - file has a RIFF...WEBP container signature (quick check)
      - Pillow can open and fully load the image and reports format 'WEBP'
    Any failure returns False (the caller should evict the cached thumb).
    """
    try:
        if not path.exists():
            return False
        if path.stat().st_size == 0:
            return False

        # Quick container-signature check (RIFF xxxx WEBP)
        try:
            raw12 = path.read_bytes()[:12]
            if not (len(raw12) >= 12 and raw12[:4] == b"RIFF" and raw12[8:12] == b"WEBP"):
                # not necessarily fatal — some files could be valid but unusual;
                # continue to a Pillow-based validation as a fallback.
                pass
        except Exception:
            # If reading header fails for some reason, continue to Pillow check.
            pass

        # Pillow full-open + load validation
        with Image.open(path) as im:
            im.load()  # ensure full decode — will raise if corrupted
            # Ensure Pillow thinks it's WebP (prevents e.g. JPEG-with-.webp-ext)
            if im.format != "WEBP":
                return False

        return True
    except Exception:
        return False


def _generate_image_thumb_sync(src: Path, out: Path) -> None:
    """Generate a WebP thumbnail from *src*, writing to *out*.

    Attempt 1 — Full quality Pillow decode at THUMB_SIZE.
    Attempt 2 — Same decode but smaller (half resolution) with lower quality,
                more tolerant of memory and encoder edge-cases.
    Attempt 3 — Tiny 128×128 draft-mode decode: asks the JPEG decoder for the
                lowest-resolution scanline pass it can produce. Works on files
                with truncated entropy data that a full decode would reject.
    Attempt 4 — Raw byte rescue: scan the file for the first valid JPEG SOI
                marker (FF D8 FF), skip any corrupt prefix, and decode from
                there at draft resolution.

    Every attempt writes a smaller copy of the *actual image pixels* — no
    placeholder, no solid colour, no text overlay.  The identity placeholder
    is only used if the file contains no decodable pixels whatsoever.
    """
    if src.stat().st_size < 16:
        print(f"[Thumbnail] {src.name}: file too small — identity placeholder")
        _generate_identity_thumb(src, out)
        return

    # ── Attempt 1: standard full decode ──────────────────────────────────────
    try:
        with Image.open(src) as img:
            try:
                img.seek(0)
            except (AttributeError, EOFError):
                pass
            try:
                img = ImageOps.exif_transpose(img)
            except Exception:
                pass
            img = _normalise_image_mode(img)
            img.thumbnail(THUMB_SIZE, Image.LANCZOS)
            _write_webp(img, out, quality=85)

        with Image.open(out) as chk:
            chk.verify()
        return  # ✓

    except Exception as exc:
        print(f"[Thumbnail] {src.name}: attempt 1 failed ({exc})")
        out.unlink(missing_ok=True)

    # ── Attempt 2: smaller resize, lower quality ──────────────────────────────
    small_size = (THUMB_SIZE[0] // 2, THUMB_SIZE[1] // 2)
    try:
        with Image.open(src) as img:
            try:
                img.seek(0)
            except (AttributeError, EOFError):
                pass
            try:
                img = ImageOps.exif_transpose(img)
            except Exception:
                pass
            img = _normalise_image_mode(img)
            img.thumbnail(small_size, Image.LANCZOS)
            _write_webp(img, out, quality=60)

        with Image.open(out) as chk:
            chk.verify()
        print(f"[Thumbnail] {src.name}: recovered at half resolution")
        return  # ✓

    except Exception as exc:
        print(f"[Thumbnail] {src.name}: attempt 2 failed ({exc})")
        out.unlink(missing_ok=True)

    # ── Attempt 3: draft-mode decode (JPEG low-res scanline pass) ────────────
    try:
        with Image.open(src) as img:
            img.draft("RGB", (128, 128))   # request smallest available decode
            img.load()
            img = _normalise_image_mode(img)
            img.thumbnail((128, 128), Image.LANCZOS)
            _write_webp(img, out, quality=50)

        with Image.open(out) as chk:
            chk.verify()
        print(f"[Thumbnail] {src.name}: recovered via draft-mode decode")
        return  # ✓

    except Exception as exc:
        print(f"[Thumbnail] {src.name}: attempt 3 (draft) failed ({exc})")
        out.unlink(missing_ok=True)

    # ── Attempt 4: byte-scan SOI rescue (skip corrupt file prefix) ───────────
    try:
        import io
        raw  = src.read_bytes()
        soi  = raw.find(b"\xff\xd8\xff")
        if soi > 0:
            buf = io.BytesIO(raw[soi:])
            with Image.open(buf) as img:
                img.draft("RGB", (128, 128))
                img.load()
                img = _normalise_image_mode(img)
                img.thumbnail((128, 128), Image.LANCZOS)
                _write_webp(img, out, quality=50)

            with Image.open(out) as chk:
                chk.verify()
            print(f"[Thumbnail] {src.name}: recovered via SOI byte-scan (skipped {soi} B)")
            return  # ✓
    except Exception as exc:
        print(f"[Thumbnail] {src.name}: attempt 4 (SOI rescue) failed ({exc})")
        out.unlink(missing_ok=True)

    # ── Attempt 5: ffmpeg image decode (handles HEIC, AVIF, RAW, and other
    #    formats that Pillow cannot decode on its own) ─────────────────────────
    if shutil.which("ffmpeg"):
        tmp_ff_path: Optional[Path] = None
        try:
            fd, tmp_ff_str = tempfile.mkstemp(suffix=".jpg", dir=out.parent)
            os.close(fd)
            tmp_ff_path = Path(tmp_ff_str)
            result = subprocess.run(
                [
                    "ffmpeg", "-y", "-i", str(src),
                    "-vf", (
                        f"scale={THUMB_SIZE[0]}:{THUMB_SIZE[1]}"
                        ":force_original_aspect_ratio=decrease"
                    ),
                    "-frames:v", "1",
                    "-q:v", "3",
                    str(tmp_ff_path),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=30,
            )
            if result.returncode == 0 and tmp_ff_path.exists() and tmp_ff_path.stat().st_size > 0:
                with Image.open(tmp_ff_path) as img:
                    img = _normalise_image_mode(img)
                    img.thumbnail(THUMB_SIZE, Image.LANCZOS)
                    _write_webp(img, out, quality=75)
                tmp_ff_path.unlink(missing_ok=True)
                with Image.open(out) as chk:
                    chk.verify()
                print(f"[Thumbnail] {src.name}: recovered via ffmpeg image decode")
                return  # ✓
        except Exception as exc:
            print(f"[Thumbnail] {src.name}: attempt 5 (ffmpeg) failed ({exc})")
            out.unlink(missing_ok=True)
        finally:
            if tmp_ff_path is not None:
                tmp_ff_path.unlink(missing_ok=True)

    # ── Last resort: identity placeholder ────────────────────────────────────
    print(f"[Thumbnail] {src.name}: no pixels recoverable — identity placeholder")
    _generate_identity_thumb(src, out)



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
# Routes – thumbnail debug  (diagnose the 5 broken images)
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/debug/thumbnail/{file_path:path}", tags=["debug"])
async def debug_thumbnail(file_path: str, _auth=Depends(require_api_key)):
    """Inspect the cached thumbnail for any file and return a full diagnosis.

    Tells you exactly why a thumbnail looks broken in the browser:
      • Is the thumb file there at all?
      • What size is it?
      • What format does Pillow think it is?
      • Does a full decode (im.load()) succeed?
      • What error does it raise if not?

    Use this to diagnose the 5 failing images:
      GET /debug/thumbnail/photos/MyAlbum/IMG_1234.HEIC
    """
    target = ensure_in_base(file_path)
    thumb  = get_thumb_path(file_path)

    info: dict = {
        "file_path":    file_path,
        "source_exists": target.exists(),
        "source_size":  target.stat().st_size if target.exists() else None,
        "thumb_path":   str(thumb),
        "thumb_exists": thumb.exists(),
        "thumb_size":   thumb.stat().st_size if thumb.exists() else None,
        "thumb_valid":  False,
        "thumb_format": None,
        "thumb_mode":   None,
        "thumb_dimensions": None,
        "error":        None,
        "verdict":      None,
    }

    if not thumb.exists():
        info["verdict"] = "THUMB_MISSING – thumbnail was never generated or was deleted"
        return info

    if thumb.stat().st_size == 0:
        info["verdict"] = "THUMB_EMPTY – file exists but is zero bytes"
        return info

    try:
        with Image.open(thumb) as im:
            info["thumb_format"]     = im.format
            info["thumb_mode"]       = im.mode
            info["thumb_dimensions"] = list(im.size)
            im.load()   # full decode – same as what the browser does
            info["thumb_valid"] = True
            if im.format != "WEBP":
                info["verdict"] = (
                    f"WRONG_FORMAT – file has extension .webp but Pillow reads it as "
                    f"{im.format}. Browser receives wrong Content-Type."
                )
            else:
                info["verdict"] = "OK – thumbnail is a valid, decodable WebP"
    except Exception as exc:
        info["error"]   = str(exc)
        info["verdict"] = f"DECODE_FAILED – file exists and is non-empty but Pillow cannot decode it: {exc}"

    # Also read the first 12 bytes to check the file signature
    try:
        header = thumb.read_bytes()[:12].hex(" ")
        info["file_header_hex"] = header
        raw12 = thumb.read_bytes()[:12]
        if raw12[:4] == b"RIFF" and raw12[8:12] == b"WEBP":
            info["signature"] = "RIFF....WEBP ✓ (valid WebP container)"
        elif raw12[:2] == b"\xff\xd8":
            info["signature"] = "FFD8 (JPEG) – file is JPEG disguised as .webp"
        elif raw12[:8] == b"\x89PNG\r\n\x1a\n":
            info["signature"] = "PNG signature – file is PNG disguised as .webp"
        elif raw12[:4] == b"GIF8":
            info["signature"] = "GIF signature – file is GIF disguised as .webp"
        else:
            info["signature"] = f"Unknown signature: {raw12[:8].hex()}"
    except Exception:
        pass

    # Bonus: wipe the cached thumb so the next request regenerates it fresh
    # Pass ?reset=1 to force regeneration after viewing the diagnosis
    return info


@app.delete("/debug/thumbnail/{file_path:path}", tags=["debug"])
def debug_thumbnail_reset(file_path: str, _auth=Depends(require_api_key)):
    """Delete the cached thumbnail for a file so it will be regenerated fresh."""
    thumb = get_thumb_path(file_path)
    existed = thumb.exists()
    thumb.unlink(missing_ok=True)
    return {
        "file_path": file_path,
        "thumb_deleted": existed,
        "message": "Thumbnail cache cleared. Next GET /thumbnail/… will regenerate it.",
    }


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


# NOTE: /health is defined earlier in the "discovery" section and returns full
#       server identity info used by the Android auto-discovery flow.


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
    # Incremental index update: re-key every affected entry in O(folder size)
    # instead of a full rglob rebuild.
    _index_rename_folder(
        f"photos/{old_name}",
        f"photos/{payload.new_name}",
    )
    return {"message": "Folder renamed.", "old": old_name, "new": payload.new_name}


@app.delete("/folders/photos/{folder_name}", tags=["folders"])
def delete_folder(folder_name: str, _auth=Depends(require_api_key)):
    folder = safe_join(PHOTOS_DIR, folder_name)
    if not folder.exists() or not folder.is_dir():
        raise HTTPException(status_code=404, detail="Folder not found.")
    shutil.rmtree(folder)
    _index_remove_folder(f"photos/{folder_name}")
    return {"message": "Folder deleted.", "name": folder_name}


# ──────────────────────────────────────────────────────────────────────────────
# Routes – hierarchical browser
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/folders/browse", tags=["folders"])
def browse(path: str = "", request: Request = None, _auth=Depends(require_api_key)):
    """
    Hierarchical browser: returns both subfolders and files for a given relative path.
    Path is relative to the project BASE_DIR (e.g. "photos/2026").

    Uses fully resolved absolute paths throughout to avoid ValueError when
    BASE_DIR is a relative Path and items resolve to absolute paths.
    """
    try:
        rate_limit(request)

        # Resolve both to absolute paths so relative_to() comparisons never fail
        base_abs = BASE_DIR.resolve()
        target   = safe_join(BASE_DIR, path).resolve()

        if not target.exists() or not target.is_dir():
            return {"folders": [], "files": []}

        folders = []
        files   = []

        for item in sorted(target.iterdir()):
            if item.name.startswith("."):
                continue

            # Resolve item before calling relative_to to prevent ValueError
            try:
                item_abs = item.resolve()
                rel      = str(item_abs.relative_to(base_abs)).replace("\\", "/")
            except ValueError:
                continue  # Skip anything that somehow falls outside BASE_DIR

            if item.is_dir():
                try:
                    inner = list(item.iterdir())
                    count = len([f for f in inner if f.is_file() and not f.name.startswith(".")])

                    preview = None
                    imgs = sorted(
                        [f for f in inner if f.is_file() and mime_type_for(f).startswith("image/")],
                        key=lambda x: x.stat().st_mtime,
                        reverse=True,
                    )
                    if imgs:
                        preview = str(imgs[0].resolve().relative_to(base_abs)).replace("\\", "/")

                    folders.append({
                        "name":       rel,
                        "file_count": count,
                        "preview":    preview,
                    })
                except PermissionError:
                    continue  # One restricted folder should not crash the whole list
            else:
                files.append({
                    "name":       item.name,
                    "relpath":    rel,
                    "size_bytes": item.stat().st_size,
                    "size_human": human_size(item.stat().st_size),
                    "mtime":      iso_ts(item),
                    "mime":       mime_type_for(item),
                })

        return {"folders": folders, "files": files}

    except Exception as exc:
        import traceback
        print(f"[Browse] ERROR on path '{path}': {exc}")
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(exc))


# ──────────────────────────────────────────────────────────────────────────────
# Routes – file listing (with pagination)
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/files/photos/{folder_name}", tags=["files"])
async def list_photos_in_folder(
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

    exif_cache = await build_metadata_cache(folder)
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
def search(request: Request, q: Optional[str] = "", skip: int = 0, limit: int = 100, _auth=Depends(require_api_key)):
    rate_limit(request)
    skip  = max(skip, 0)
    limit = max(1, min(limit, 200))
    pattern = (q or "").strip().lower()
    index   = get_index()
    if not pattern:
        hits = list(index.values())
    else:
        hits = [v for v in index.values() if pattern in v["name"].lower()]
    hits.sort(key=lambda x: x["mtime"], reverse=True)

    if not pattern:
        files = hits[skip:]
        return {"total": len(hits), "skip": skip, "limit": len(files), "files": files}

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

    thumb     = get_thumb_path(file_path)
    src_mtime = target.stat().st_mtime

    # Fast path: cached thumbnail exists and is newer than the source.
    # Validate it fully (im.load(), not just exists()) — the 5 broken images
    # are likely already cached as invalid files that pass exists() but fail
    # in the browser.  An invalid cache entry is evicted and regenerated.
    if thumb.exists() and thumb.stat().st_mtime >= src_mtime:
        if _validate_webp(thumb):
            return _safe_file_response(thumb, "image/webp")
        else:
            print(f"[Thumbnail] evicting invalid cached thumb for {target.name}")
            thumb.unlink(missing_ok=True)

    # Slow path: acquire a per-thumbnail asyncio.Lock so that if multiple
    # requests arrive simultaneously for the same image, only ONE actually
    # runs the generator; the others wait and then hit the fast path above.
    cache_key = hash_for(file_path)
    if cache_key not in _thumb_locks:
        _thumb_locks[cache_key] = asyncio.Lock()
    lock = _thumb_locks[cache_key]

    async with lock:
        # Re-check inside the lock – a previous waiter may have just written it.
        if thumb.exists() and thumb.stat().st_mtime >= src_mtime:
            return _safe_file_response(thumb, "image/webp")
        try:
            await run_in_threadpool(_generate_image_thumb_sync, target, thumb)
        except Exception as exc:
            # _generate_image_thumb_sync already tries Pillow → ffmpeg → identity
            # placeholder.  If it still raises, generate the identity thumb directly.
            thumb.unlink(missing_ok=True)
            print(f"[Thumbnail] unexpected error for {target.name}: {exc} – using identity thumb")
            try:
                await run_in_threadpool(_generate_identity_thumb, target, thumb)
            except Exception:
                pass  # identity generation is pure Pillow drawing, should never fail
        finally:
            _thumb_locks.pop(cache_key, None)

    return _safe_file_response(thumb, "image/webp")


@app.get("/thumbnail/video/{file_path:path}", tags=["thumbnails"])
async def thumbnail_video(
    file_path: str,
    request: Request,
    _auth=Depends(require_api_key),
):
    rate_limit(request)
    # Normalize file_path to use forward slashes for compatibility
    normalized_path = file_path.replace("\\", "/")
    src = ensure_in_base(normalized_path)
    if not src.exists() or not src.is_file():
        raise HTTPException(status_code=404, detail="File not found.")
    if not shutil.which("ffmpeg"):
        raise HTTPException(status_code=501, detail="ffmpeg is not installed – video thumbnails unavailable.")

    thumb     = get_thumb_path(normalized_path)
    src_mtime = src.stat().st_mtime

    # Fast path: validate fully before trusting the cache.
    if thumb.exists() and thumb.stat().st_mtime >= src_mtime:
        if _validate_webp(thumb):
            return _safe_file_response(thumb, "image/webp")
        else:
            print(f"[Thumbnail/video] evicting invalid cached thumb for {src.name}")
            thumb.unlink(missing_ok=True)

    # Slow path: serialise concurrent requests for the same video thumbnail.
    cache_key = hash_for(normalized_path)
    if cache_key not in _thumb_locks:
        _thumb_locks[cache_key] = asyncio.Lock()
    lock = _thumb_locks[cache_key]

    async with lock:
        # Re-check: a previous waiter may have already generated it.
        if thumb.exists() and thumb.stat().st_mtime >= src_mtime:
            return _safe_file_response(thumb, "image/webp")

        thumb.parent.mkdir(parents=True, exist_ok=True)

        scale_filter = (
            f"scale='min({THUMB_SIZE[0]},iw)':'min({THUMB_SIZE[1]},ih)'"
            f":force_original_aspect_ratio=decrease"
        )

        # Four progressively more compatible video thumbnail strategies.
        # ffmpeg NEVER writes to the final thumb path directly — always to a
        # temp file, then Pillow converts to WebP, then atomic rename to thumb.
        # This prevents partial/corrupt cached files on any interruption.
        #
        #  A) seek 0.5 s + autorotate + PNG→Pillow  — ideal path
        #  B) no seek   + autorotate + PNG→Pillow   — fixes short clips < 0.5 s
        #  C) no seek   + no autorotate + PNG→Pillow — fixes ffmpeg < 4.0
        #  D) no seek   + no autorotate + MJPEG→Pillow — last resort

        async def _try_video_strategy(seek: Optional[str], vf: str,
                                      vcodec: str, suffix: str) -> bool:
            """ffmpeg → properly-typed temp → Pillow → WebP temp → atomic rename."""
            # Step 1: ffmpeg writes its native format to a real temp file
            try:
                fd, img_tmp_str = tempfile.mkstemp(suffix=suffix, dir=THUMB_DIR)
                os.close(fd)
                img_tmp = Path(img_tmp_str)
            except OSError as exc:
                print(f"[Thumbnail/video] temp file creation failed: {exc}")
                return False

            cmd = ["ffmpeg", "-y"]
            if seek:
                cmd += ["-ss", seek]
            cmd += ["-i", str(src), "-vframes", "1", "-vf", vf,
                    "-vcodec", vcodec, str(img_tmp)]
            try:
                async with FFMPEG_SEMAPHORE:
                    await run_in_threadpool(
                        subprocess.check_output, cmd,
                        stderr=subprocess.STDOUT, timeout=10,
                    )
                if not img_tmp.exists() or img_tmp.stat().st_size == 0:
                    print(f"[Thumbnail/video] zero-byte output for {src.name} ({vcodec})")
                    return False
            except subprocess.CalledProcessError as exc:
                output = exc.output.decode(errors="replace").strip()
                print(f"[Thumbnail/video] {vcodec} failed for {src.name}:\n  {output}")
                return False
            except (subprocess.TimeoutExpired, OSError) as exc:
                print(f"[Thumbnail/video] error for {src.name}: {exc}")
                return False
            finally:
                # img_tmp cleanup deferred to step 2's finally block below
                pass

            # Step 2: Pillow reads the native-format temp, saves WebP to a
            # second temp, then atomically renames to thumb.
            # img_tmp (the ffmpeg output) is always cleaned up here.
            try:
                fd, webp_tmp_str = tempfile.mkstemp(suffix=".webp", dir=THUMB_DIR)
                os.close(fd)
                webp_tmp = Path(webp_tmp_str)
            except OSError as exc:
                print(f"[Thumbnail/video] WebP temp creation failed: {exc}")
                img_tmp.unlink(missing_ok=True)
                return False

            try:
                def _convert():
                    with Image.open(img_tmp) as im:
                        im.load()
                        im = _normalise_image_mode(im)
                        im.save(webp_tmp, format="WEBP", quality=85, method=6)
                    # Atomic rename: thumb is always a complete WebP or absent
                    webp_tmp.replace(thumb)

                await run_in_threadpool(_convert)
                return True
            except Exception as exc:
                print(f"[Thumbnail/video] Pillow→WebP failed for {src.name}: {exc}")
                webp_tmp.unlink(missing_ok=True)
                return False
            finally:
                img_tmp.unlink(missing_ok=True)

        vf_rotate    = f"{scale_filter},autorotate"
        vf_no_rotate = scale_filter
        success = False

        # Strategy A – seek + autorotate + PNG→Pillow
        if not success:
            success = await _try_video_strategy("00:00:00.5", vf_rotate, "png", ".png")
        # Strategy B – no seek + autorotate + PNG→Pillow (short clips)
        if not success:
            success = await _try_video_strategy(None, vf_rotate, "png", ".png")
        # Strategy C – no seek + no autorotate + PNG→Pillow (old ffmpeg)
        if not success:
            success = await _try_video_strategy(None, vf_no_rotate, "png", ".png")
        # Strategy D – no seek + no autorotate + MJPEG→Pillow (last resort)
        if not success:
            success = await _try_video_strategy(None, vf_no_rotate, "mjpeg", ".jpg")

        if not success:
            thumb.unlink(missing_ok=True)
            print(f"[Thumbnail/video] all strategies exhausted for {src.name} – using identity thumb")
            try:
                await run_in_threadpool(_generate_identity_thumb, src, thumb)
            except Exception:
                pass
            _thumb_locks.pop(cache_key, None)
        else:
            _thumb_locks.pop(cache_key, None)  # success path cleanup

    return _safe_file_response(thumb, "image/webp")


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
    overwrite: bool = False,
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
    if overwrite:
        dest = target_dir / filename
        if dest.exists() and dest.is_dir():
            raise HTTPException(status_code=400, detail="Invalid filename (conflicts with a folder).")
    else:
        dest = unique_path(target_dir, filename)

    await _stream_upload(dest, file, MAX_PHOTO_BYTES)

    # Server-side validation with Pillow
    verify_image_file(dest)

    # If overwriting, invalidate any cached thumbnail for this path.
    if overwrite:
        get_thumb_path(f"photos/{folder_name}/{dest.name}").unlink(missing_ok=True)

    # Incremental index: add just this one file – O(1), no full rglob.
    _index_add(dest)

    # Warm the EXIF cache for this file in the background so the next
    # list request doesn't have to do it under user-visible latency.
    await run_in_threadpool(_update_metadata_cache_sync, target_dir, dest.name)

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
    overwrite: bool = False,
    _auth=Depends(require_api_key),
):
    declared_mime = (file.content_type or "").split(";")[0].strip().lower()
    if declared_mime not in ALLOWED_VIDEO_MIMES and not declared_mime.startswith("video/"):
        raise HTTPException(status_code=400, detail="Only video files are accepted.")

    target_dir = VIDEOS_DIR
    target_dir.mkdir(parents=True, exist_ok=True)

    filename = sanitize_filename(file.filename)
    if overwrite:
        dest = target_dir / filename
        if dest.exists() and dest.is_dir():
            raise HTTPException(status_code=400, detail="Invalid filename (conflicts with a folder).")
    else:
        dest = unique_path(target_dir, filename)

    await _stream_upload(dest, file, MAX_VIDEO_BYTES)

    # Server-side validation with ffprobe (no-op if ffprobe is absent)
    verify_video_file(dest)

    # If overwriting, invalidate any cached thumbnail for this path.
    if overwrite:
        get_thumb_path(f"videos/{dest.name}").unlink(missing_ok=True)

    # Incremental index: add just this one file – O(1).
    _index_add(dest)

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

    # Incremental index: remove just this one entry – O(1).
    _index_remove(file_path)

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
    print(f"[ERROR] {exc}")
    return JSONResponse(status_code=500, content={"error": "server error"})
