// audioqc — the voice-cloning quality gate, ported from the Kotlin service.
//
// Same thresholds, same method, same numbers. It runs in the browser so a
// parent can see the verdict on their own handset before anything is uploaded,
// and in Node so those numbers can be checked against the server's.
//
// Nothing here trusts what the container claims. An Android HAL reporting
// 48 kHz while feeding 16 kHz content upsampled is routine, and the only way to
// catch it is to look at where the spectrum actually stops (report.cutoffHz).

export const FRAME_MS = 25;
export const HOP_MS = 10;
export const FLOOR_DB = -120;
const CUTOFF_FLOOR_DB = 60;

export const PROFILES = {
  enrolment: {
    minSampleRate: 16000, minSpeechSeconds: 20, minSpeechRatio: 0.25,
    failSnrDb: 18, warnSnrDb: 25,
    failCutoffHz: 7000, warnCutoffHz: 10000,
    maxClippingRatio: 0.005, maxDcOffset: 0.02,
  },
  daily: {
    minSampleRate: 16000, minSpeechSeconds: 2.5, minSpeechRatio: 0.25,
    failSnrDb: 15, warnSnrDb: 25,
    failCutoffHz: 7000, warnCutoffHz: 10000,
    maxClippingRatio: 0.005, maxDcOffset: 0.02,
  },
};

// Human-facing copy per code. The gate returns codes; this is the only place
// that turns one into a sentence, so the app can swap languages without the
// algorithm knowing.
export const MESSAGES = {
  too_short:        { title: '再说长一点', how: '一句完整的话就够，大概三五秒。' },
  low_sample_rate:  { title: '这台设备的采样率太低', how: '换一台手机录，或检查录音权限设置。' },
  noisy:            { title: '背景有点吵', how: '找个安静的地方，关掉风扇和电视。' },
  band_limited:     { title: '这条像是从通话里录的', how: '直接在这里按住录，别用通话或语音消息转发过来。' },
  clipping:         { title: '声音录爆了', how: '手机离嘴一拳远就好，不用贴着。' },
  dc_offset:        { title: '采集链路有点异常', how: '重启一下 App 再试。' },
  mostly_silence:   { title: '大部分是空白', how: '按住之后直接说，别等。' },
  no_speech:        { title: '没听到说话', how: '检查一下麦克风权限。' },
};

// ---------- WAV ----------

export function decodeWav(buffer) {
  const view = new DataView(buffer);
  const str = (off, n) => String.fromCharCode(...new Uint8Array(buffer, off, n));
  if (buffer.byteLength < 12 || str(0, 4) !== 'RIFF' || str(8, 4) !== 'WAVE') {
    throw new Error('not a RIFF/WAVE stream');
  }

  let format = 0, channels = 0, sampleRate = 0, bits = 0;
  let dataOff = -1, dataLen = 0;

  for (let off = 12; off + 8 <= buffer.byteLength;) {
    const id = str(off, 4);
    let size = view.getUint32(off + 4, true);
    const body = off + 8;
    if (body + size > buffer.byteLength) size = buffer.byteLength - body;
    if (size <= 0) break;

    if (id === 'fmt ') {
      format = view.getUint16(body, true);
      channels = view.getUint16(body + 2, true);
      sampleRate = view.getUint32(body + 4, true);
      bits = view.getUint16(body + 14, true);
      if (format === 0xfffe && size >= 40) format = view.getUint16(body + 24, true);
    } else if (id === 'data') {
      dataOff = body; dataLen = size;
    }
    off = body + size + (size % 2);
  }

  if (!channels || !sampleRate || !bits) throw new Error('missing or unusable fmt chunk');
  if (dataOff < 0) throw new Error('missing data chunk');

  const bytesPer = bits >> 3;
  const stride = bytesPer * channels;
  const frames = Math.floor(dataLen / stride);
  const out = new Float32Array(frames);
  const scale = Math.pow(2, bits - 1);

  for (let f = 0; f < frames; f++) {
    let sum = 0;
    for (let c = 0; c < channels; c++) {
      const at = dataOff + f * stride + c * bytesPer;
      if (format === 3 && bits === 32) sum += view.getFloat32(at, true);
      else if (bits === 8) sum += (view.getUint8(at) - 128) / 128;
      else if (bits === 16) sum += view.getInt16(at, true) / scale;
      else if (bits === 24) {
        let v = view.getUint8(at) | (view.getUint8(at + 1) << 8) | (view.getUint8(at + 2) << 16);
        if (v & 0x800000) v |= ~0xffffff;
        sum += v / scale;
      } else if (bits === 32) sum += view.getInt32(at, true) / scale;
      else throw new Error(`unsupported format ${format}/${bits}-bit`);
    }
    out[f] = sum / channels;
  }
  return { samples: out, sampleRate, channels, bitDepth: bits };
}

/** Packs Float32 samples as 16-bit PCM WAV — what gets uploaded. */
export function encodeWav(samples, sampleRate) {
  const buf = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buf);
  const put = (off, s) => { for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i)); };

  put(0, 'RIFF');  view.setUint32(4, 36 + samples.length * 2, true);
  put(8, 'WAVEfmt ');  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);  view.setUint16(34, 16, true);
  put(36, 'data');  view.setUint32(40, samples.length * 2, true);

  for (let i = 0; i < samples.length; i++) {
    const v = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, Math.round(v * 32767), true);
  }
  return buf;
}

// ---------- DSP ----------

export function fftRadix2(re, im) {
  const n = re.length;
  if (n <= 1) return;

  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j |= bit;
    if (i < j) {
      [re[i], re[j]] = [re[j], re[i]];
      [im[i], im[j]] = [im[j], im[i]];
    }
  }

  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len;
    const wRe = Math.cos(ang), wIm = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let curRe = 1, curIm = 0;
      const half = len >> 1;
      for (let k = 0; k < half; k++) {
        const uRe = re[i + k], uIm = im[i + k];
        const vRe = re[i + k + half] * curRe - im[i + k + half] * curIm;
        const vIm = re[i + k + half] * curIm + im[i + k + half] * curRe;
        re[i + k] = uRe + vRe;  im[i + k] = uIm + vIm;
        re[i + k + half] = uRe - vRe;  im[i + k + half] = uIm - vIm;
        const nextRe = curRe * wRe - curIm * wIm;
        curIm = curRe * wIm + curIm * wRe;
        curRe = nextRe;
      }
    }
  }
}

const hann = (n) => Float64Array.from({ length: n }, (_, i) => 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
const nextPow2 = (n) => { let p = 1; while (p < n) p <<= 1; return p; };

function medianOf(xs, from, to) {
  const c = Array.prototype.slice.call(xs, from, to).sort((a, b) => a - b);
  if (!c.length) return 0;
  const m = c.length >> 1;
  return c.length % 2 ? c[m] : (c[m - 1] + c[m]) / 2;
}

function percentile(xs, p) {
  if (!xs.length) return 0;
  const c = Array.prototype.slice.call(xs).sort((a, b) => a - b);
  const i = Math.min(Math.max(Math.round(p / 100 * (c.length - 1)), 0), c.length - 1);
  return c[i];
}

// ---------- the gate ----------

export function judge(audio, policy) {
  const failures = [], warnings = [];
  const duration = audio.samples.length / audio.sampleRate;
  const frameLen = Math.floor(audio.sampleRate * FRAME_MS / 1000);
  const hopLen = Math.max(Math.floor(audio.sampleRate * HOP_MS / 1000), 1);

  const fail = (code, measured, threshold) => failures.push({ code, measured, threshold });
  const warn = (code, measured, threshold) => warnings.push({ code, measured, threshold });

  if (audio.sampleRate < policy.minSampleRate) {
    fail('low_sample_rate', audio.sampleRate, policy.minSampleRate);
  }
  if (!frameLen || audio.samples.length < frameLen) {
    fail('too_short', duration, policy.minSpeechSeconds);
    return report(audio, duration, 0, FLOOR_DB, FLOOR_DB, 0, 0, 0, 0, 0, failures, warnings);
  }

  // amplitude domain
  let sum = 0, clipped = 0, run = 0;
  for (const v of audio.samples) {
    sum += v;
    if (Math.abs(v) >= 0.995) run++;
    else { if (run >= 3) clipped += run; run = 0; }
  }
  if (run >= 3) clipped += run;
  const clippingRatio = clipped / audio.samples.length;
  const dcOffset = sum / audio.samples.length;

  if (clippingRatio > policy.maxClippingRatio) fail('clipping', clippingRatio, policy.maxClippingRatio);
  if (Math.abs(dcOffset) > policy.maxDcOffset) warn('dc_offset', Math.abs(dcOffset), policy.maxDcOffset);

  // frame energies
  const count = Math.floor((audio.samples.length - frameLen) / hopLen) + 1;
  const frameDb = new Float64Array(count);
  for (let f = 0, i = 0; f < count; f++, i += hopLen) {
    let e = 0;
    for (let k = i; k < i + frameLen; k++) e += audio.samples[k] * audio.samples[k];
    const rms = Math.sqrt(e / frameLen);
    frameDb[f] = rms > 0 ? Math.max(20 * Math.log10(rms), FLOOR_DB) : FLOOR_DB;
  }

  const noiseFloor = percentile(frameDb, 10);
  const speechLevel = percentile(frameDb, 95);
  const snrDb = speechLevel - noiseFloor;

  const vadThreshold = Math.max(noiseFloor + 6, -55);
  const voiced = [];
  for (let f = 0; f < count; f++) if (frameDb[f] >= vadThreshold) voiced.push(f);

  const speechSeconds = voiced.length * HOP_MS / 1000;
  const speechRatio = duration > 0 ? speechSeconds / duration : 0;

  if (!voiced.length) fail('no_speech', 0, policy.minSpeechSeconds);
  else if (speechSeconds < policy.minSpeechSeconds) fail('too_short', speechSeconds, policy.minSpeechSeconds);
  if (voiced.length && speechRatio < policy.minSpeechRatio) {
    warn('mostly_silence', speechRatio, policy.minSpeechRatio);
  }

  if (snrDb < policy.failSnrDb) fail('noisy', snrDb, policy.failSnrDb);
  else if (snrDb < policy.warnSnrDb) warn('noisy', snrDb, policy.warnSnrDb);

  let cutoffHz = 0, spectrum = null;
  if (voiced.length) {
    spectrum = spectrumOf(audio, voiced, frameLen, hopLen);
    cutoffHz = spectrum.cutoffHz;
    if (cutoffHz < policy.failCutoffHz) fail('band_limited', cutoffHz, policy.failCutoffHz);
    else if (cutoffHz < policy.warnCutoffHz) warn('band_limited', cutoffHz, policy.warnCutoffHz);
  }

  const out = report(audio, duration, snrDb, noiseFloor, speechLevel, speechSeconds,
    speechRatio, cutoffHz, clippingRatio, dcOffset, failures, warnings);
  out.spectrum = spectrum;
  return out;
}

function report(a, duration, snrDb, noiseFloorDbfs, speechLevelDbfs, speechSeconds,
                speechRatio, cutoffHz, clippingRatio, dcOffset, failures, warnings) {
  return {
    duration, sampleRate: a.sampleRate, channels: a.channels, bitDepth: a.bitDepth,
    snrDb, noiseFloorDbfs, speechLevelDbfs, speechSeconds, speechRatio,
    cutoffHz, clippingRatio, dcOffset,
    passed: failures.length === 0, failures, warnings,
  };
}

/**
 * The averaged, smoothed spectrum plus the cutoff read off it.
 *
 * Returned as data rather than kept private, because the plot of this curve is
 * the whole explanation: a parent who sees the trace fall off a cliff at 3.4 kHz
 * understands "this came through a phone call" instantly, where a dB figure
 * means nothing to them.
 */
export function spectrumOf(audio, voiced, frameLen, hopLen) {
  const nfft = Math.max(nextPow2(frameLen), 512);
  const window = hann(frameLen);
  const half = nfft >> 1;

  const acc = new Float64Array(half);
  const re = new Float64Array(nfft);
  const im = new Float64Array(nfft);

  const step = Math.max(Math.floor(voiced.length / 400), 1);
  let used = 0;

  for (let k = 0; k < voiced.length; k += step) {
    const start = voiced[k] * hopLen;
    if (start + frameLen > audio.samples.length) continue;
    re.fill(0); im.fill(0);
    for (let i = 0; i < frameLen; i++) re[i] = audio.samples[start + i] * window[i];
    fftRadix2(re, im);
    for (let i = 0; i < half; i++) acc[i] += Math.hypot(re[i], im[i]);
    used++;
  }
  if (!used) return 0;

  const spec = new Float64Array(half);
  for (let i = 0; i < half; i++) {
    const v = acc[i] / used;
    spec[i] = v <= 0 ? FLOOR_DB : 20 * Math.log10(v);
  }

  const smooth = new Float64Array(half);
  for (let i = 0; i < half; i++) {
    smooth[i] = medianOf(spec, Math.max(i - 2, 0), Math.min(i + 3, half));
  }

  const binHz = audio.sampleRate / nfft;
  const bin = (hz) => Math.min(Math.max(Math.floor(hz / binHz), 0), half - 1);

  let ref = FLOOR_DB;
  for (let i = bin(200); i <= bin(4000); i++) if (smooth[i] > ref) ref = smooth[i];
  const threshold = ref - CUTOFF_FLOOR_DB;

  let cutoffHz = 0;
  for (let i = half - 1; i >= 0; i--) {
    if (smooth[i] > threshold) { cutoffHz = i * binHz; break; }
  }
  return { cutoffHz, curve: smooth, binHz, ref, threshold };
}

export function analyse(buffer, profileName) {
  return judge(decodeWav(buffer), PROFILES[profileName] ?? PROFILES.daily);
}
