// Small portable DSP helpers used before and around Essentia: resampling and loudness.
#ifndef ABCD_DSP_H
#define ABCD_DSP_H

#include <cstddef>
#include <vector>

namespace abcd {

// Idea: windowed-sinc interpolation. For every output sample we find its position on the input
// timeline and sum the nearby input samples weighted by a sinc kernel (a perfect low-pass), faded out
// by a Hann window. When going down in rate the kernel is stretched so frequencies above the new
// Nyquist are removed first (anti-aliasing). Kernels for 256 sub-sample offsets are tabulated once.
std::vector<float> resample(const float* input, size_t frames, int inputRate, int outputRate);
inline std::vector<float> resample(const std::vector<float>& input, int inputRate, int outputRate) {
    return resample(input.data(), input.size(), inputRate, outputRate);
}

// Idea: ITU-R BS.1770 / EBU R128 integrated loudness of one channel. The signal goes through the
// K-weighting filter (a high shelf that mimics the head, then a high-pass), is cut into 400 ms blocks
// with 75% overlap, and the mean-square power of the blocks is averaged after two gates: absolute
// -70 LUFS (drops silence) and relative -10 LU (drops quiet passages). Result in LUFS.
// Approximation: we only have a mono downmix, so this is not a certified meter reading, but it is
// consistent across tracks, which is what gain matching needs.
double integratedLoudness(const std::vector<float>& mono, int sampleRate);

}  // namespace abcd

#endif  // ABCD_DSP_H
