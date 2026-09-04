# PlateWatch

A sideloadable Android app that reads vehicle number plates from a mounted phone
while you drive, and logs each one with a timestamp, a GPS position and a street
address.

Built for a neighbourhood watch patrol: no account, no server, no sync. Everything
stays in app-private storage on the phone until you deliberately export it.

---

## What it actually does

Being straight about this up front, because ALPR products are usually sold with more
confidence than they deserve:

| Field | How it is captured | Honest accuracy |
|---|---|---|
| **Plate number** | On-device OCR, repaired against the region's plate layouts, confirmed across several frames | Good on clean plates in daylight at moderate closing speed. Degrades hard at night, at angles, on dirty or personalised plates |
| **Time seen** | System clock, first and last frame | Exact |
| **Location** | GPS fix at the moment of confirmation, with accuracy, speed and heading | As good as the phone's GPS — typically 5–15 m moving |
| **Street address** | Reverse geocoded from the fix | Usually right to the street, often not to the house number |
| **Colour** | Median of the pixels just above the plate | An estimate. Useful as a filter, not as evidence |
| **Make / model** | **Typed in by you**, on the sighting screen | Always right, because a human did it |
| **Registration tab** | The expiry month and year read as **printed text**, not inferred from tab colour | Only legible at close range — in a queue, or parked. Blank the rest of the time, which is the correct answer |

### Why the tab date is read, not guessed from the colour

Washington tabs are colour-coded by year, and it is tempting to sample that colour and
call it a year when the tab is too small to read. That cannot work, and not for a
reason a better camera would fix:

| Year | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 | 2027 |
|------|------|------|------|------|------|------|------|------|
| Tab  | white | blue | red | green | black | white | blue | red |

The cycle is five long and then repeats, so **blue means 2021 or 2026 or 2031** — a
colour identifies a year modulo five, and nothing narrower. The month, which is printed
on the same tab, is not encoded in the colour at all, so no colour sampling will ever
produce one. And two of the five colours are white and black: the two that survive
worst through a moving camera, on a plate that has a white background for a white tab
to vanish into.

There is also no useful distance band where the idea would pay off. At 720p with the
plate characters filling the frame height the app is tuned for, the tab is around 30 px
wide — close to legible. Back off until the plate itself stops reading and the tab is
about 6 px of luma and, after the 4:2:0 chroma subsampling every camera pipeline
applies, roughly **three by five colour samples**, smeared with the white plate around
it. The window where "too far to read, close enough to colour-match" exists is not a
window at all.

So the app reads the month and year **as text**, from the same recognition pass that is
already running over the whole frame — which costs nothing extra — and reports nothing
when the tab is not legible. Colour is sampled for exactly one purpose: checking it
against a year that was actually read. A tab printed 2023 that samples as blue is
flagged, because [recolouring an expired tab](https://www.king5.com/article/news/nation-world/expired-license-plate-tab-colored-to-look-2019/507-ea9435b1-b2e4-4ec0-9d5f-c88853810fa7)
is a cheap and well-documented forgery. That flag is a prompt to look at the crop, not
a finding — poor light shifts colour badly.

`TabColorCycle.candidateYears()` returns a *list* of years and there is deliberately no
function anywhere that turns a colour into one year. The API shape is the documentation.

### Why make and model are not automatic

There is no free, on-device model that reads make and model off a phone camera with
anything like the reliability of the plate itself. Rather than ship a guess that is
wrong a third of the time and gets written into a log that people treat as fact, the
app leaves those two fields to you — it takes about three seconds per vehicle, and
the saved crop is right there to look at.

The hook is real, though: implement `VehicleClassifier` and the pipeline will use it.
[`docs/MAKE_AND_MODEL.md`](docs/MAKE_AND_MODEL.md) has a worked TensorFlow Lite
implementation and the Gradle lines it needs.

---

## How it works

```
CameraX frame  ──►  ML Kit text recognition  ──►  PlateTextParser  ──►  SightingAggregator  ──►  Room
   (720p,             (bundled model,              (mask-driven          (multi-frame           (+ GPS,
    throttled          fully on-device)             OCR repair)           consensus,             address,
    to N fps)                                                             dedup)                 crops)
```

Three ideas do most of the work:

**Mask-driven OCR repair.** A recogniser cannot tell a letter `O` from a digit `0` —
on a plate they are often the same glyph. But if you know the region's layouts, you
know slot 3 of `AB12CD` can only ever be a digit, so a recognised `O` there *is* a
zero. The parser tries each layout, repairs what it can, and charges a confidence
penalty for every character it had to touch.

**Multi-frame consensus.** Nothing is written down until several frames agree, inside
a short window. One blurred frame at 60 km/h never becomes a permanent record of the
wrong car. Readings that differ by a single character are pooled together and the
majority spelling wins, so a flipped digit does not split the vote.

**Deduplication that knows the difference between following a car and seeing it
again.** A car you sit behind for a kilometre is one sighting. The same plate two
streets away twenty minutes later is a *second* sighting — for a patrol log, that
repeat is the entire point. Same plate, close in both time and distance, tops up the
existing entry instead.

The whole of that logic lives in `:core`, a plain Kotlin module with no Android
dependencies, covered by 77 unit tests. Run them on a laptop in seconds:

```bash
./gradlew :core:test
```

---

## Building and sideloading

Requires Android Studio (or the command-line SDK) with **API 35** and **JDK 17**.

```bash
git clone <this repo>
cd android/PlateWatch
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug-signed APK installs fine when sideloaded. For a build you can distribute to
the rest of the group and upgrade in place later, create a keystore and drop a
`keystore.properties` next to `app/build.gradle.kts`:

```properties
storeFile=platewatch.jks
storePassword=…
keyAlias=platewatch
keyPassword=…
```

then `./gradlew :app:assembleRelease`. `keystore.properties` and `*.jks` are already
gitignored — keep them that way.

**Minimum Android 8.0 (API 26).** No Google Play Services required: the text
recognition model is bundled into the APK, and location comes from the platform
`LocationManager` rather than the fused provider, so this runs on a de-Googled phone.

---

## Using it

1. Mount the phone where the rear camera has a clear forward view. A windscreen mount
   at roughly eye height works best; the app reads plates head-on far better than at
   an angle.
2. Open Settings, set **Plate formats** to your region. Defaults to **United States /
   Canada**, which covers Washington's `ABC1234` passenger format.
3. Tap **Start logging**. The screen stays on and dims to a dark UI.
4. Drive. A short tick means a plate went into the log; a longer alternating alert
   means a watchlist plate just went past.
5. Stop, then review in **Log** — tap any entry to check the crop, correct the plate,
   and add make and model.

Capture only runs while the app is in the foreground. That is an Android platform
rule, not a design choice: apps cannot hold the camera in the background, so there is
deliberately no background service pretending otherwise.

### Tuning

Everything below is in Settings, and takes effect immediately.

| Symptom | Change |
|---|---|
| Missing too many plates | Lower **frames that must agree** to 2, lower **minimum single-frame quality**, raise **frames analysed per second** |
| Logging obvious rubbish | Raise **frames that must agree** to 4–5, raise **minimum quality**, make sure the region is right |
| Phone getting hot, battery dying | Drop **frames analysed per second** to 2–3, turn off **save crops** |
| Personalised plates being missed | Switch region to **Generic** — catches far more, at the cost of more false positives |
| One car logged over and over | Raise the **same-encounter window** and **radius** |
| Tab date never populated | Expected at speed — tabs only read close up. Nothing to tune |

---

## Data, retention and privacy

Design decisions worth knowing about, since this app exists to collect data about
people who did not opt in:

- **Nothing is uploaded.** There is no server and no account. The single network call
  the app can make is reverse geocoding a fix into a street address, and that has its
  own switch in Settings.
- **Cloud backup is disabled** in the manifest. A patrol log should not quietly end up
  in someone's Google Drive, or get carried across in a device-to-device transfer.
- **Retention defaults to 30 days.** A daily background job deletes older sightings
  and their crops. Set it to 0 to keep everything, but that is a decision, not a
  default.
- **Crops live in app-private storage**, never the gallery, and go when the app is
  uninstalled.
- **The raw OCR text is kept** alongside the repaired plate, so a disputed reading can
  actually be audited rather than argued about.

Worth settling with your group before you start rather than after: who holds the
exported files, how long the group keeps them, who is allowed to ask for a lookup,
and what happens to a sighting when it turns out to be nothing. Plate logging on
public roads is broadly lawful in most places, but the rules on *retaining and
sharing* that data vary by jurisdiction, and a neighbourhood group that hands its log
to anyone who asks is a different thing from one that keeps it for 30 days and gives
it to police on request. Worth ten minutes with your local rules and, if the group is
formally constituted, whoever advises it.

---

## Project layout

```
core/                       Pure Kotlin. No Android. Fully unit tested.
  plate/                    Mask-driven formats, OCR character repair, parser
  sighting/                 Multi-frame consensus, dedup, geo maths
  color/                    Body colour estimation from a pixel patch
  tab/                      Registration tab: text parsing, expiry, colour cross-check
  export/                   CSV and JSON writers (locale-safe)

app/
  capture/                  CameraX analyser, alerts, VehicleClassifier hook
  data/db/                  Room entities, DAOs
  data/repo/                Repository, crop storage
  data/prefs/               DataStore settings
  location/                 LocationManager tracker, reverse geocoder
  work/                     Daily retention purge
  ui/                       Compose screens
```

## Known limitations

- Night-time accuracy is poor without street lighting. The torch helps at a standstill
  and not at all at speed.
- Motorbike plates, and plates on a steep angle, are largely missed.
- Registration tabs are usually unreadable from a moving car. The field stays blank
  rather than being filled with a guess, and that is the intended behaviour.
- Tab reading assumes the month and year are printed on a single tab on the rear plate,
  per [WAC 308-96A-295](https://app.leg.wa.gov/wac/default.aspx?cite=308-96A-295). The
  parser accepts several renderings (`SEP 26`, `SEP2026`, `09 2026`) rather than
  betting on one layout, but a redesigned tab could still need a new pattern.
- Personalised and novelty plates match no standard layout — use Generic.
- Two lanes of traffic at once will lose plates; the analyser commits to the best
  candidate per frame.
- `ImageProxy.toBitmap()` (CameraX 1.4) is used for the crops. If you downgrade
  CameraX, that call moves or disappears.
