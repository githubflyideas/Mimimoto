// Raw PCM capture.
//
// NOT MediaRecorder. MediaRecorder hands back encoded audio — webm/opus in
// Chrome, mp4/aac in Safari — and every one of those encoders band-limits the
// signal. The quality gate decides whether a recording is usable by looking at
// where the spectrum stops, so encoded input means measuring the ENCODER's
// low-pass instead of the microphone's: a clean 48 kHz take comes back as
// "this device is upsampling". Lossless in, always (docs/DECISIONS.md D-014).
//
// So: an AudioWorklet that copies Float32 frames out untouched, and we pack the
// WAV ourselves.
//
// What this cannot do, and the native apps can: iOS gives no way to ask for an
// unprocessed capture path — AGC, noise suppression and echo cancellation are
// the browser's to apply. The constraints below turn off what is turn-off-able
// and the rest is measured rather than assumed, which is why the report shows
// what the browser actually granted.

const WORKLET_URL = './capture-worklet.js';

export class Recorder {
    constructor() {
        this.context = null;
        this.stream = null;
        this.node = null;
        this.chunks = [];
        this.frames = 0;
        this.onLevel = null;
    }

    /** True once the browser has handed over a live microphone. */
    get running() { return this.node !== null; }

    /**
     * Starts capture. Must be called from a user gesture: every browser refuses
     * to start an AudioContext otherwise, and Safari refuses silently.
     */
    async start(onLevel) {
        this.onLevel = onLevel;
        this.chunks = [];
        this.frames = 0;

        this.stream = await navigator.mediaDevices.getUserMedia({
            audio: {
                // Ask for the untouched path. Browsers are free to ignore all
                // three, and several do — hence settings(), below.
                echoCancellation: false,
                noiseSuppression: false,
                autoGainControl: false,
                channelCount: 1,
                sampleRate: 48000,
            },
        });

        // Requesting 48 kHz is a hint; Safari in particular hands back whatever
        // the hardware is on. The rate we actually got is what the gate is told.
        this.context = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 });
        if (this.context.state === 'suspended') await this.context.resume();

        await this.context.audioWorklet.addModule(WORKLET_URL);

        const source = this.context.createMediaStreamSource(this.stream);
        this.node = new AudioWorkletNode(this.context, 'capture', { numberOfOutputs: 0 });
        this.node.port.onmessage = (event) => {
            const { samples, peak } = event.data;
            this.chunks.push(samples);
            this.frames += samples.length;
            if (this.onLevel) this.onLevel(peak);
        };
        source.connect(this.node);
    }

    /** Stops capture and returns the take. */
    async stop() {
        const sampleRate = this.context ? this.context.sampleRate : 48000;
        const settings = this.settings();

        if (this.node) { this.node.port.onmessage = null; this.node.disconnect(); this.node = null; }
        if (this.stream) { this.stream.getTracks().forEach((t) => t.stop()); this.stream = null; }
        if (this.context) { await this.context.close().catch(() => {}); this.context = null; }

        const samples = new Float32Array(this.frames);
        let at = 0;
        for (const part of this.chunks) { samples.set(part, at); at += part.length; }
        this.chunks = [];

        return { samples, sampleRate, channels: 1, bitDepth: 32, settings };
    }

    /**
     * What the browser actually granted, as opposed to what was asked for.
     *
     * Worth surfacing: a handset that silently keeps noise suppression on is
     * feeding the gate processed audio, and that shows up as a verdict nobody
     * can explain unless this is on screen next to it.
     */
    settings() {
        const track = this.stream && this.stream.getAudioTracks()[0];
        if (!track || !track.getSettings) return {};
        const s = track.getSettings();
        return {
            echoCancellation: s.echoCancellation,
            noiseSuppression: s.noiseSuppression,
            autoGainControl: s.autoGainControl,
            sampleRate: s.sampleRate,
            label: track.label || '',
        };
    }
}
