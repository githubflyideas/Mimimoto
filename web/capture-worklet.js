// Copies microphone frames to the main thread, untouched.
//
// It runs on the audio thread, so it does the least possible: copy the block,
// note its peak for the level meter, post it. Any processing here would be
// exactly the contamination the quality gate exists to detect.

class Capture extends AudioWorkletProcessor {
    process(inputs) {
        const input = inputs[0];
        if (!input || input.length === 0) return true;

        const channel = input[0];
        if (!channel || channel.length === 0) return true;

        // The engine reuses this buffer on the next block, so it has to be
        // copied before it crosses the port.
        const samples = new Float32Array(channel.length);
        let peak = 0;
        for (let i = 0; i < channel.length; i++) {
            const v = channel[i];
            samples[i] = v;
            const a = v < 0 ? -v : v;
            if (a > peak) peak = a;
        }

        this.port.postMessage({ samples, peak }, [samples.buffer]);
        return true;
    }
}

registerProcessor('capture', Capture);
