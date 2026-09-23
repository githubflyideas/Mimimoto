"""CosyVoice 2 synthesis worker.

One segment in, one segment out. The Kotlin side handles batching,
ordering, retries and the delivery deadline; this process does nothing but hold
the model on the GPU and render text.

Run:
    # 1. the model's own repo and deps
    git clone https://github.com/FunAudioLLM/CosyVoice.git
    pip install -r CosyVoice/requirements.txt
    export PYTHONPATH="$PWD/CosyVoice:$PWD/CosyVoice/third_party/Matcha-TTS"

    # 2. the weights (Apache-2.0, commercial use permitted — D-015)
    modelscope download --model iic/CosyVoice2-0.5B --local_dir pretrained_models/CosyVoice2-0.5B

    # 3. this worker
    python server.py --model pretrained_models/CosyVoice2-0.5B --port 9880

CosyVoice's Python API has changed shape between releases. The call in
`Engine.synthesize` matches the 2.x `inference_zero_shot` signature; if the
installed version disagrees, that one method is what needs adjusting, and
nothing else in this file or on the Kotlin side does.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import logging
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse

log = logging.getLogger("mimimoto.worker")

# What CosyVoice 2 speaks. Kept in step with CosyVoice2.languages on the Kotlin
# side; the two disagreeing means a family is offered a language that comes back
# as an error at 20:30.
LANGUAGES = {"zh", "yue", "en", "ja", "ko"}

# Reference audio must be 16 kHz for CosyVoice 2 regardless of what was
# recorded. Note this is a resample for the MODEL's benefit and happens after
# the quality gate has already judged the original: the gate needs the full
# band to tell a real 48 kHz microphone from an upsampled one (D-014), so it
# must never see this downsampled copy.
PROMPT_SAMPLE_RATE = 16_000


class Engine:
    """Holds the model. Serialised with a lock: one GPU, one render at a time.

    Concurrency belongs upstream, in the job queue, where it can be ordered by
    delivery deadline. Letting requests interleave here would trade throughput
    for unpredictable per-segment latency, and per-segment latency is what
    decides whether a child hears a gap mid-story.
    """

    def __init__(self, model_dir: str, fp16: bool = False) -> None:
        from cosyvoice.cli.cosyvoice import CosyVoice2

        log.info("loading model from %s", model_dir)
        t0 = time.monotonic()
        self._tts = CosyVoice2(model_dir, load_jit=False, load_trt=False, fp16=fp16)
        self._lock = threading.Lock()
        self.sample_rate = int(getattr(self._tts, "sample_rate", 24_000))
        self.version = os.environ.get("MIMIMOTO_MODEL_VERSION", "cosyvoice2-0.5b")
        log.info("model ready in %.1fs, output %d Hz", time.monotonic() - t0, self.sample_rate)

    def synthesize(self, req: dict[str, Any]) -> dict[str, Any]:
        import torch
        import torchaudio
        from cosyvoice.utils.file_utils import load_wav

        text = (req.get("text") or "").strip()
        if not text:
            raise ValueError("empty text")

        prompt_uri = req.get("prompt_audio_uri") or ""
        prompt_text = (req.get("prompt_text") or "").strip()
        if not prompt_uri or not prompt_text:
            # Zero-shot cloning conditions on both the reference audio and its
            # transcript. Refusing here beats rendering something that quietly
            # does not sound like the parent.
            raise ValueError("prompt_audio_uri and prompt_text are both required")

        language = (req.get("language") or "").lower()
        if language and language not in LANGUAGES:
            raise ValueError(f"unsupported language: {language!r}")

        prompt_speech = load_wav(_local_path(prompt_uri), PROMPT_SAMPLE_RATE)

        # The previous segment's tail is prepended for prosodic continuity and
        # trimmed back off, so the contour carries across the segment boundary
        # instead of restarting on every call (D-009).
        prev_tail = (req.get("prev_tail") or "").strip()
        full_text = f"{prev_tail}{text}" if prev_tail else text

        with self._lock:
            chunks = [
                out["tts_speech"]
                for out in self._tts.inference_zero_shot(
                    full_text, prompt_text, prompt_speech, stream=False
                )
            ]
        if not chunks:
            raise RuntimeError("model returned no audio")
        audio = torch.cat(chunks, dim=1) if len(chunks) > 1 else chunks[0]

        if prev_tail:
            audio = _trim_prefix(audio, prev_tail, full_text)

        buf = io.BytesIO()
        torchaudio.save(buf, audio.cpu(), self.sample_rate, format="wav")
        data = buf.getvalue()

        return {
            "audio_b64": base64.b64encode(data).decode("ascii"),
            "sample_rate": self.sample_rate,
            "duration_s": float(audio.shape[-1]) / float(self.sample_rate),
            "model_version": self.version,
        }


def _local_path(uri: str) -> str:
    parsed = urlparse(uri)
    if parsed.scheme in ("", "file"):
        return parsed.path or uri
    raise ValueError(f"worker only reads local prompt audio, got {uri!r}")


def _trim_prefix(audio, prev_tail: str, full_text: str):
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
            # Terminal by contract: the Kotlin client treats 400 as a request it
            # must not retry, so a second GPU slot is not spent reaching the
            # same answer.
            self._json(400, {"error": str(exc)})
            return
        except Exception as exc:  # noqa: BLE001
            log.exception("synthesis failed")
            self._json(500, {"error": str(exc)})
            return

        elapsed = max(time.monotonic() - t0, 1e-6)
        log.info(
            "rendered %.1fs of audio in %.1fs (%.1fx realtime)",
            out["duration_s"], elapsed, out["duration_s"] / elapsed,
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
    ap.add_argument("--model", default="pretrained_models/CosyVoice2-0.5B")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9880)
    ap.add_argument("--fp16", action="store_true", help="half precision; faster, slightly lower fidelity")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    Handler.engine = Engine(args.model, fp16=args.fp16)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    log.info("listening on %s:%d", args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("shutting down")
        server.shutdown()


if __name__ == "__main__":
    main()
