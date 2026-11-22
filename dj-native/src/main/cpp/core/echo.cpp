#include "echo.h"

#include <algorithm>
#include <cmath>

namespace abcd {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kDampingHz = 4500.0;  // repeats lose their top end, like an analogue/DJ-mixer echo
}  // namespace

EchoLine::EchoLine(int sampleRate, int channels, double maxDelaySeconds)
    : channels_(std::max(1, channels)),
      capacity_(static_cast<size_t>(std::max(1.0, maxDelaySeconds * std::max(1, sampleRate))) + 1),
      buffer_(capacity_ * static_cast<size_t>(std::max(1, channels)), 0.0f),
      lowpass_(static_cast<size_t>(std::max(1, channels)), 0.0f) {
    // One-pole low-pass coefficient for kDampingHz at this sample rate.
    damping_ = static_cast<float>(1.0 - std::exp(-2.0 * kPi * kDampingHz / std::max(1, sampleRate)));
}

void EchoLine::process(float* samples, size_t frames, size_t delayFrames, float send, float feedback, float dry, float wet) {
    if (samples == nullptr || frames == 0) return;
    const size_t delay = std::clamp<size_t>(delayFrames, 1, capacity_ - 1);
    const size_t ch = static_cast<size_t>(channels_);
    feedback = std::clamp(feedback, 0.0f, 0.95f);  // never self-oscillate
    for (size_t f = 0; f < frames; ++f) {
        const size_t read = (write_ + capacity_ - delay) % capacity_;
        for (size_t c = 0; c < ch; ++c) {
            float& x = samples[f * ch + c];
            const float delayed = buffer_[read * ch + c];
            // Darken the repeat before it is heard and fed back.
            float& lp = lowpass_[c];
            lp += damping_ * (delayed - lp);
            buffer_[write_ * ch + c] = send * x + feedback * lp;
            x = dry * x + wet * lp;
        }
        write_ = (write_ + 1) % capacity_;
    }
}

void EchoLine::clear() {
    std::fill(buffer_.begin(), buffer_.end(), 0.0f);
    std::fill(lowpass_.begin(), lowpass_.end(), 0.0f);
    write_ = 0;
}

}  // namespace abcd
