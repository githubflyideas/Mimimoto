"""FireRedTTS3 synthesis worker.

One segment in, one segment out. The Go side handles batching, ordering,
retries and the delivery deadline; this process does nothing but hold the model
on the GPU and render text.

Run:
    pip install -r requirements.txt
    python server.py --model ./pretrained_models --port 9880

Licensing note, repeated here because this is where someone will copy the code
from: the FireRedTTS3 repository is Apache-2.0, but its model card restricts
voice cloning to academic research. The two statements contradict each other.
Resolve it in writing before this runs against paying customers — see
docs/DECISIONS.md D-010.
"""

from __future__ import annotations

import argparse
import base64
import io
import logging
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse

import json

log = logging.getLogger("mimimoto.worker")

# FireRedTTS3 wants an explicit language tag; these are the primary subtags the
# Go side sends, mapped to what the model expects.
LANGUAGE_TAGS = {
    "ar": "Arabic", "cs": "Czech", "de": "German", "el": "Greek",
    "en": "English", "es": "Spanish", "fi": "Finnish", "fr": "French",
    "hi": "Hindi", "id": "Indonesian", "it": "Italian", "ja": "Japanese",
    "ko": "Korean", "nl": "Dutch", "pl": "Polish", "pt": "Portuguese",
    "ro": "Romanian", "ru": "Russian", "th": "Thai", "tr": "Turkish",
    "uk": "Ukrainian", "vi": "Vietnamese", "yue": "Cantonese", "zh": "Chinese",
}


class Engine:
    """Holds the model. Serialised with a lock: one GPU, one render at a time.

    Concurrency belongs upstream, in the job queue, where it can be ordered by
    delivery deadline. Letting requests interleave here would only trade
    throughput for unpredictable per-segment latency, and per-segment latency is
    what decides whether a child hears a gap.
    """

    def __init__(self, model_dir: str, use_llm_tn: bool = False) -> None:
        import torch  # noqa: F401  (imported for its side effects on device setup)
        from fireredtts3.core import FireRedTTS3

        log.info("loading model from %s", model_dir)
        t0 = time.monotonic()
        self._tts = FireRedTTS3(model_dir, use_wetext=True, use_llm_tn=use_llm_tn)
        self._lock = threading.Lock()
        self.version = os.environ.get("MIMIMOTO_MODEL_VERSION", "firered-tts3-base")
        log.info("model ready in %.1fs", time.monotonic() - t0)

    def synthesize(self, req: dict[str, Any]) -> dict[str, Any]:
        import torch
        import torchaudio

        text = req.get("text") or ""
        if not text.strip():
            raise ValueError("empty text")

        prompt_uri = req.get("prompt_audio_uri") or ""
        prompt_text = req.get("prompt_text") or ""
        if not prompt_uri or not prompt_text:
            # Zero-shot cloning conditions on both. Refusing here beats
            # rendering something that quietly does not sound like the parent.
            raise ValueError("prompt_audio_uri and prompt_text are both required")

        lang = LANGUAGE_TAGS.get((req.get("language") or "").lower())
        if lang is None:
            raise ValueError(f"unsupported language: {req.get('language')!r}")

        prompt_audio, prompt_sr = torchaudio.load(_local_path(prompt_uri))

        # The previous segment's tail is prepended for prosodic continuity and
        # then trimmed back off, so the contour carries across the segment
        # boundary instead of restarting on every call.
        prev_tail = (req.get("prev_tail") or "").strip()
        full_text = f"{prev_tail} {text}".strip() if prev_tail else text

        with self._lock:
            audio, sr = self._tts.generate(
                language=lang,
                prompt_text=prompt_text,
                prompt_audio=prompt_audio,
                prompt_audio_sr=prompt_sr,
                text=full_text,
                do_tn=True,
            )

        if prev_tail:
            audio = _trim_prefix(audio, sr, prev_tail, full_text)

        buf = io.BytesIO()
        torchaudio.save(buf, audio.cpu(), sr, format="wav")
        data = buf.getvalue()

        return {
            "audio_b64": base64.b64encode(data).decode("ascii"),
            "sample_rate": int(sr),
            "duration_s": float(audio.shape[-1]) / float(sr),
            "model_version": self.version,
        }


def _local_path(uri: str) -> str:
    parsed = urlparse(uri)
    if parsed.scheme in ("", "file"):
        return parsed.path or uri
    raise ValueError(f"worker only reads local prompt audio, got {uri!r}")


def _trim_prefix(audio, sr: int, prev_tail: str, full_text: str):
    """Drop the re-rendered tail from the front of the segment.

    Estimated by character share, which is crude. The right fix is forced
    alignment on the boundary; until there is real audio to tune against, an
    estimate that errs towards keeping a few extra milliseconds is safer than
    one that clips the first word of the segment.
    """
    if not full_text:
        return audio
    share = len(prev_tail) / len(full_text)
    cut = int(audio.shape[-1] * share * 0.95)
    if cut <= 0 or cut >= audio.shape[-1]:
        return audio
    return audio[..., cut:]


class Handler(BaseHTTPRequestHandler):
    engine: Engine | None = None
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        log.debug(fmt, *args)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/healthz":
            self._json(200, {"status": "ok" if self.engine else "loading"})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/synthesize":
            self._json(404, {"error": "not found"})
            return
        if self.engine is None:
            self._json(503, {"error": "model still loading"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception as exc:  # noqa: BLE001
            self._json(400, {"error": f"bad request: {exc}"})
            return

        t0 = time.monotonic()
        try:
            out = self.engine.synthesize(body)
        except ValueError as exc:
            # Terminal: the Go side must not retry these.
            self._json(400, {"error": str(exc)})
            return
        except Exception as exc:  # noqa: BLE001
            log.exception("synthesis failed")
            self._json(500, {"error": str(exc)})
            return

        log.info(
            "rendered %.1fs of audio in %.1fs (%.1fx realtime)",
            out["duration_s"], time.monotonic() - t0,
            out["duration_s"] / max(time.monotonic() - t0, 1e-6),
        )
        self._json(200, out)

    def _json(self, code: int, payload: dict[str, Any]) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="./pretrained_models")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9880)
    ap.add_argument("--llm-tn", action="store_true",
                    help="use LLM text normalisation (needed for full coverage "
                         "outside zh/en; requires .env credentials)")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    Handler.engine = Engine(args.model, use_llm_tn=args.llm_tn)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    log.info("listening on %s:%d", args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("shutting down")
        server.shutdown()


if __name__ == "__main__":
    main()
