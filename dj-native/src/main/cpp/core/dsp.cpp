#include "dsp.h"

#include <algorithm>
#include <cmath>

namespace abcd {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr int kPhases = 256;  // sub-sample offsets in the kernel table
constexpr int kHalfTaps = 16; // kernel reaches 16 input samples either side
}  // namespace

std::vector<float> resample(const float* input, size_t frames, int inputRate, int outputRate) {
    if (inputRate <= 0 || outputRate <= 0 || input == nullptr || frames == 0) return {};
    if (inputRate == outputRate) return std::vector<float>(input, input + frames);

    const double ratio = static_cast<double>(outputRate) / inputRate;
    // Cut-off as a fraction of the input Nyquist: 1 when going up, stretched down when going down.
    const double cutoff = std::min(1.0, ratio) * 0.97;
    const int taps = 2 * kHalfTaps;

    // table[phase][tap]: kernel value for an output point `phase/kPhases` of a sample past input[i].
    std::vector<float> table(static_cast<size_t>(kPhases) * taps);
    for (int phase = 0; phase < kPhases; ++phase) {
        const double frac = static_cast<double>(phase) / kPhases;
        double sum = 0;
        for (int tap = 0; tap < taps; ++tap) {
            const double x = (tap - (kHalfTaps - 1)) - frac;  // distance from output point to input[i + tap - 15]
            const double window = 0.5 * (1.0 + std::cos(kPi * x / kHalfTaps));
            const double arg = kPi * x * cutoff;
            const double sinc = std::fabs(arg) < 1e-9 ? 1.0 : std::sin(arg) / arg;
            const double value = cutoff * sinc * window;
            table[static_cast<size_t>(phase) * taps + tap] = static_cast<float>(value);
            sum += value;
        }
        // Normalise so DC passes at unity gain for every phase.
        for (int tap = 0; tap < taps; ++tap) table[static_cast<size_t>(phase) * taps + tap] /= static_cast<float>(sum);
    }

    const size_t outFrames = static_cast<size_t>(static_cast<double>(frames) * ratio);
    std::vector<float> output(outFrames);
    const long inCount = static_cast<long>(frames);
    for (size_t n = 0; n < outFrames; ++n) {
        const double position = static_cast<double>(n) / ratio;  // input-timeline position
        const long base = static_cast<long>(position);
        const int phase = std::min(kPhases - 1, static_cast<int>((position - base) * kPhases));
        const float* kernel = &table[static_cast<size_t>(phase) * taps];
        double acc = 0;
        for (int tap = 0; tap < taps; ++tap) {
            const long index = base + tap - (kHalfTaps - 1);
            if (index >= 0 && index < inCount) acc += kernel[tap] * input[index];
        }
        output[n] = static_cast<float>(acc);
    }
    return output;
}

namespace {

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double z1 = 0, z2 = 0;
    double process(double x) {  // transposed direct form II
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

// BS.1770 stage 1: high shelf (+4 dB above ~1.7 kHz).
Biquad highShelf(double fs) {
    const double f0 = 1681.974450955533, gain = 3.999843853973347, q = 0.7071752369554196;
    const double k = std::tan(kPi * f0 / fs);
    const double vh = std::pow(10.0, gain / 20.0);
    const double vb = std::pow(vh, 0.4996667741545416);
    const double a0 = 1.0 + k / q + k * k;
    Biquad f;
    f.b0 = (vh + vb * k / q + k * k) / a0;
    f.b1 = 2.0 * (k * k - vh) / a0;
    f.b2 = (vh - vb * k / q + k * k) / a0;
    f.a1 = 2.0 * (k * k - 1.0) / a0;
    f.a2 = (1.0 - k / q + k * k) / a0;
    return f;
}

// BS.1770 stage 2: high-pass at ~38 Hz.
Biquad highPass(double fs) {
    const double f0 = 38.13547087602444, q = 0.5003270373238773;
    const double k = std::tan(kPi * f0 / fs);
    const double a0 = 1.0 + k / q + k * k;
    Biquad f;
    f.b0 = 1.0;
    f.b1 = -2.0;
    f.b2 = 1.0;
    f.a1 = 2.0 * (k * k - 1.0) / a0;
    f.a2 = (1.0 - k / q + k * k) / a0;
    return f;
}

}  // namespace

double integratedLoudness(const std::vector<float>& mono, int sampleRate) {
    const size_t block = static_cast<size_t>(0.4 * sampleRate);
    const size_t hop = static_cast<size_t>(0.1 * sampleRate);
    if (mono.size() < block || hop == 0) return -70.0;

    Biquad shelf = highShelf(sampleRate), pass = highPass(sampleRate);
    std::vector<double> power(mono.size());
    for (size_t i = 0; i < mono.size(); ++i) {
        const double y = pass.process(shelf.process(mono[i]));
        power[i] = y * y;
    }
    // Running sum so every 400 ms block costs O(1).
    std::vector<double> cumulative(power.size() + 1, 0.0);
    for (size_t i = 0; i < power.size(); ++i) cumulative[i + 1] = cumulative[i] + power[i];

    std::vector<double> blocks;
    for (size_t start = 0; start + block <= power.size(); start += hop) {
        blocks.push_back((cumulative[start + block] - cumulative[start]) / static_cast<double>(block));
    }
    auto toLufs = [](double meanSquare) { return -0.691 + 10.0 * std::log10(std::max(meanSquare, 1e-12)); };

    double sum = 0;
    size_t count = 0;
    for (double z : blocks) {
        if (toLufs(z) > -70.0) { sum += z; ++count; }
    }
    if (count == 0) return -70.0;
    const double relativeGate = toLufs(sum / static_cast<double>(count)) - 10.0;
    sum = 0;
    count = 0;
    for (double z : blocks) {
        if (toLufs(z) > -70.0 && toLufs(z) > relativeGate) { sum += z; ++count; }
    }
    return count == 0 ? -70.0 : toLufs(sum / static_cast<double>(count));
}

}  // namespace abcd
