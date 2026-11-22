// Echo-out effect for the DJ decks (brief §9.2 "Echo out"). Portable C++: no JNI, no Android.
#pragma once

#include <cstddef>
#include <vector>

namespace abcd {

// A feedback delay line on interleaved float samples.
//
// Idea: every sample is written into a ring buffer together with a fraction of what came back out of it
// (feedback), so a sound repeats every `delay` frames, quieter each time. A one-pole low-pass inside the
// loop makes each repeat a little darker, which is how a DJ mixer's echo sounds and why it fades out
// naturally instead of ringing. `send` is how much of the input enters the echo, `dry` how much of the
// input is heard directly and `wet` how loud the repeats are. Echo out = raise `send` over the last bars,
// then drop `dry` and `send` to 0 and let the tail decay.
class EchoLine {
public:
    EchoLine(int sampleRate, int channels, double maxDelaySeconds = 2.0);

    // Processes `frames` frames of `samples` (interleaved, `channels` per frame) in place.
    void process(float* samples, size_t frames, size_t delayFrames, float send, float feedback, float dry, float wet);

    // Empties the delay line (after a seek or when the deck gets a new song).
    void clear();

    int channels() const { return channels_; }

private:
    int channels_;
    size_t capacity_;  // frames
    size_t write_ = 0;
    std::vector<float> buffer_;    // capacity_ * channels_
    std::vector<float> lowpass_;   // one state per channel
    float damping_;
};

}  // namespace abcd
