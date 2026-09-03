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
dependencies, covered by 44 unit tests. Run them on a laptop in seconds:

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
2. Open Settings, set **Plate formats** to your region (defaults to Australia).
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
- Personalised and novelty plates match no standard layout — use Generic.
- Two lanes of traffic at once will lose plates; the analyser commits to the best
  candidate per frame.
- `ImageProxy.toBitmap()` (CameraX 1.4) is used for the crops. If you downgrade
  CameraX, that call moves or disappears.
