# Audio test fixtures

Small audio files used by the `org.meltzg.fs.mtp.audio` reader tests. Kept tiny (~50 KB, 4 seconds) so
they are cheap to check in. Additional formats add their own fixture as the corresponding reader lands.

## `fixture.flac`

Real encoder output, generated locally so the tags are known and deterministic. Used by
`RealFixtureTagsTest`, which asserts our reader's result against the tags below **and** cross-checks it
against jaudiotagger (the reference parser).

- **Source audio:** [`Lachmoeweisma20052019.flac`](https://commons.wikimedia.org/wiki/File:Lachmoeweisma20052019.flac)
  from Wikimedia Commons, released under **CC0 1.0** (public-domain dedication — no attribution
  required; credited here for transparency).
- **Processing:** trimmed to the first 4 seconds and transcoded with **ffmpeg 7.0.2** to FLAC with an
  embedded PNG cover, to exercise picture-skipping.
- **Tags set at generation:**
  - title = `melt-jfs Fixture Title`
  - artist = `melt-jfs Fixture Artist`
  - album = `melt-jfs Fixture Album`
  - genre = `Jazz`
  - track = `7/12`  (trackNumber 7)
  - disc = `2/3`  (discNumber 2)
  - duration ≈ 4 s
