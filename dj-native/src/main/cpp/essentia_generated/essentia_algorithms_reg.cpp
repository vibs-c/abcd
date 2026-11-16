#include "algorithmfactory.h"
#include "algorithms\standard\fftk.h"
#include "algorithms\spectral\mfcc.h"
#include "algorithms\standard\dct.h"
#include "algorithms\spectral\melbands.h"
#include "algorithms\spectral\triangularbands.h"
#include "algorithms\spectral\hpcp.h"
#include "algorithms\spectral\spectralpeaks.h"
#include "algorithms\standard\peakdetection.h"
#include "algorithms\standard\spectrum.h"
#include "algorithms\complex\magnitude.h"
#include "algorithms\standard\windowing.h"
#include "algorithms\extractor\keyextractor.h"
#include "algorithms\tonal\key.h"
#include "algorithms\spectral\spectralwhitening.h"
#include "algorithms\standard\framecutter.h"
#include "algorithms\standard\noiseadder.h"
#include "algorithms\rhythm\rhythmextractor2013.h"
#include "algorithms\rhythm\bpmrubato.h"
#include "algorithms\rhythm\beattrackerdegara.h"
#include "algorithms\rhythm\tempotapdegara.h"
#include "algorithms\standard\autocorrelation.h"
#include "algorithms\standard\ifftk.h"
#include "algorithms\filters\movingaverage.h"
#include "algorithms\filters\iir.h"
#include "algorithms\rhythm\onsetdetection.h"
#include "algorithms\spectral\flux.h"
#include "algorithms\spectral\hfc.h"
#include "algorithms\complex\cartesiantopolar.h"
#include "algorithms\rhythm\beattrackermultifeature.h"
#include "algorithms\standard\scale.h"
#include "algorithms\rhythm\tempotapmaxagreement.h"
#include "algorithms\rhythm\onsetdetectionglobal.h"
#include "algorithms\spectral\erbbands.h"

namespace essentia {
namespace standard {

ESSENTIA_API void registerAlgorithm() {
    AlgorithmFactory::Registrar<FFTK> regFFTK;
    AlgorithmFactory::Registrar<MFCC> regMFCC;
    AlgorithmFactory::Registrar<DCT> regDCT;
    AlgorithmFactory::Registrar<MelBands> regMelBands;
    AlgorithmFactory::Registrar<TriangularBands> regTriangularBands;
    AlgorithmFactory::Registrar<HPCP> regHPCP;
    AlgorithmFactory::Registrar<SpectralPeaks> regSpectralPeaks;
    AlgorithmFactory::Registrar<PeakDetection> regPeakDetection;
    AlgorithmFactory::Registrar<Spectrum> regSpectrum;
    AlgorithmFactory::Registrar<Magnitude> regMagnitude;
    AlgorithmFactory::Registrar<Windowing> regWindowing;
    AlgorithmFactory::Registrar<KeyExtractor> regKeyExtractor;
    AlgorithmFactory::Registrar<Key> regKey;
    AlgorithmFactory::Registrar<SpectralWhitening> regSpectralWhitening;
    AlgorithmFactory::Registrar<FrameCutter> regFrameCutter;
    AlgorithmFactory::Registrar<NoiseAdder> regNoiseAdder;
    AlgorithmFactory::Registrar<RhythmExtractor2013> regRhythmExtractor2013;
    AlgorithmFactory::Registrar<BpmRubato> regBpmRubato;
    AlgorithmFactory::Registrar<BeatTrackerDegara> regBeatTrackerDegara;
    AlgorithmFactory::Registrar<TempoTapDegara> regTempoTapDegara;
    AlgorithmFactory::Registrar<AutoCorrelation> regAutoCorrelation;
    AlgorithmFactory::Registrar<IFFTK> regIFFTK;
    AlgorithmFactory::Registrar<MovingAverage> regMovingAverage;
    AlgorithmFactory::Registrar<IIR> regIIR;
    AlgorithmFactory::Registrar<OnsetDetection> regOnsetDetection;
    AlgorithmFactory::Registrar<Flux> regFlux;
    AlgorithmFactory::Registrar<HFC> regHFC;
    AlgorithmFactory::Registrar<CartesianToPolar> regCartesianToPolar;
    AlgorithmFactory::Registrar<BeatTrackerMultiFeature> regBeatTrackerMultiFeature;
    AlgorithmFactory::Registrar<Scale> regScale;
    AlgorithmFactory::Registrar<TempoTapMaxAgreement> regTempoTapMaxAgreement;
    AlgorithmFactory::Registrar<OnsetDetectionGlobal> regOnsetDetectionGlobal;
    AlgorithmFactory::Registrar<ERBBands> regERBBands;
}}}



namespace essentia {
namespace streaming {

ESSENTIA_API void registerAlgorithm() {
    AlgorithmFactory::Registrar<FFTK, essentia::standard::FFTK> regFFTK;
    AlgorithmFactory::Registrar<MFCC, essentia::standard::MFCC> regMFCC;
    AlgorithmFactory::Registrar<DCT, essentia::standard::DCT> regDCT;
    AlgorithmFactory::Registrar<MelBands, essentia::standard::MelBands> regMelBands;
    AlgorithmFactory::Registrar<TriangularBands, essentia::standard::TriangularBands> regTriangularBands;
    AlgorithmFactory::Registrar<HPCP, essentia::standard::HPCP> regHPCP;
    AlgorithmFactory::Registrar<SpectralPeaks, essentia::standard::SpectralPeaks> regSpectralPeaks;
    AlgorithmFactory::Registrar<PeakDetection, essentia::standard::PeakDetection> regPeakDetection;
    AlgorithmFactory::Registrar<Spectrum, essentia::standard::Spectrum> regSpectrum;
    AlgorithmFactory::Registrar<Magnitude, essentia::standard::Magnitude> regMagnitude;
    AlgorithmFactory::Registrar<Windowing, essentia::standard::Windowing> regWindowing;
    AlgorithmFactory::Registrar<KeyExtractor, essentia::standard::KeyExtractor> regKeyExtractor;
    AlgorithmFactory::Registrar<Key, essentia::standard::Key> regKey;
    AlgorithmFactory::Registrar<SpectralWhitening, essentia::standard::SpectralWhitening> regSpectralWhitening;
    AlgorithmFactory::Registrar<FrameCutter, essentia::standard::FrameCutter> regFrameCutter;
    AlgorithmFactory::Registrar<NoiseAdder, essentia::standard::NoiseAdder> regNoiseAdder;
    AlgorithmFactory::Registrar<RhythmExtractor2013, essentia::standard::RhythmExtractor2013> regRhythmExtractor2013;
    AlgorithmFactory::Registrar<BpmRubato, essentia::standard::BpmRubato> regBpmRubato;
    AlgorithmFactory::Registrar<BeatTrackerDegara, essentia::standard::BeatTrackerDegara> regBeatTrackerDegara;
    AlgorithmFactory::Registrar<TempoTapDegara, essentia::standard::TempoTapDegara> regTempoTapDegara;
    AlgorithmFactory::Registrar<AutoCorrelation, essentia::standard::AutoCorrelation> regAutoCorrelation;
    AlgorithmFactory::Registrar<IFFTK, essentia::standard::IFFTK> regIFFTK;
    AlgorithmFactory::Registrar<MovingAverage, essentia::standard::MovingAverage> regMovingAverage;
    AlgorithmFactory::Registrar<IIR, essentia::standard::IIR> regIIR;
    AlgorithmFactory::Registrar<OnsetDetection, essentia::standard::OnsetDetection> regOnsetDetection;
    AlgorithmFactory::Registrar<Flux, essentia::standard::Flux> regFlux;
    AlgorithmFactory::Registrar<HFC, essentia::standard::HFC> regHFC;
    AlgorithmFactory::Registrar<CartesianToPolar, essentia::standard::CartesianToPolar> regCartesianToPolar;
    AlgorithmFactory::Registrar<BeatTrackerMultiFeature, essentia::standard::BeatTrackerMultiFeature> regBeatTrackerMultiFeature;
    AlgorithmFactory::Registrar<Scale, essentia::standard::Scale> regScale;
    AlgorithmFactory::Registrar<TempoTapMaxAgreement, essentia::standard::TempoTapMaxAgreement> regTempoTapMaxAgreement;
    AlgorithmFactory::Registrar<OnsetDetectionGlobal, essentia::standard::OnsetDetectionGlobal> regOnsetDetectionGlobal;
    AlgorithmFactory::Registrar<ERBBands, essentia::standard::ERBBands> regERBBands;
}}}
