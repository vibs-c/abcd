# abcd: notices and credits

**abcd ("Anybody Can DJ")** is a modified version of **Metrolist**
(https://github.com/MetrolistGroup/Metrolist), a YouTube Music client by the Metrolist contributors.
abcd adds a DJ Mode on top of Metrolist.

## License

- Metrolist is licensed under the GNU General Public License v3.0; the full text is in
  [`LICENSE`](LICENSE), unchanged. abcd is a derived work and keeps that license as a base (see the
  AGPL note below for the combined app).
- All Metrolist copyright notices, credits and third-party notices are kept. Third-party license
  files that ship with Metrolist (for example under `opusDecoder/` and `shared/src/*/resources/`)
  remain in place and apply to those components.
- abcd links **Essentia** (AGPL-3.0) for audio analysis. As GPL-3.0 section 13 allows, the combined
  app is therefore distributed under the **GNU Affero General Public License v3.0**: anyone who runs
  a modified abcd as a network service must offer its source, and everyone who receives the app may
  get the source. The AGPL text is in [`LICENSES/AGPL-3.0.txt`](LICENSES/AGPL-3.0.txt). The GPL-3.0
  text of Metrolist stays in `LICENSE` unchanged.

## Third-party code used by DJ Mode

| Component | Where | License |
|---|---|---|
| Essentia (Music Technology Group, UPF) | `dj-native/third_party/essentia`, submodule; only 33 algorithms are compiled | AGPL-3.0 |
| KissFFT (bundled inside Essentia) | compiled from Essentia's `3rdparty/kiss_fft130` | BSD-3-Clause |
| Eigen 3.4.0 | `dj-native/third_party/eigen`, submodule, header-only | MPL-2.0 |

Test music used while developing (never part of the app or the repository): "Shiny Tech" by Kevin
MacLeod (incompetech.com), CC BY 3.0.

## Changes from Metrolist

abcd's changes to upstream files are listed with reasons in
[`docs/UPSTREAM_CHANGES.md`](docs/UPSTREAM_CHANGES.md). New DJ Mode code lives in the `dj-*` modules.

## Not affiliated

abcd is not affiliated with or endorsed by the Metrolist project, YouTube or Google.
