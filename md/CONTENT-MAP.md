# Content Map: Original PPTX → New Slide Deck

Source: `20250322 Innovative Technologien für Drohnen - Luftverkehrsmodellierung.pptx` (59 slides, German).
Target: `slides/json/module_0.json … module_15.json` (English, enriched).

Translation policy: all German content translated to English; thin or image-only source slides were fleshed
out with cards/tables in the style of the [lecture-se-tools-ai](https://github.com/rl-experiments/lecture-se-tools-ai)
reference repo (marked **enriched** below). Speaker notes ("nicht vergessen …") were dropped or turned into
DEMO tags. Screenshots of third-party slide decks (DO-178C principles) and German-language text screenshots
were **transcribed into native slides** instead of being reused as images.

| PPTX slide(s) | Content | New location | Notes |
|---|---|---|---|
| 1 | Title | Main cover (`slides-config.json`) | Drone photo (image1) kept as `drone-hero.jpg`, currently unused |
| 2 | About the lecturer | Module 0 | 1986 ultralight photo kept (`ultralight-1986.jpg`); text translated |
| 3 | Lecture summary | Module 0 "What This Lecture Covers" + TOC | |
| 4 | Section 1 intro + near-miss news | Module 1 cover + "A Near Miss at Nuremberg Airport" | News screenshot kept |
| 5 | FlightAware demo | Module 1 "Live Flight Tracking" | DEMO tag |
| 6 | How are drones regulated? (image only) | Module 1 "How Are Drones Regulated Worldwide?" | Statista map kept; **enriched** with card |
| 7 | Importance of drone traffic (GIF only) | Module 1 "Why Drone Air Traffic Matters" | Dayton wing-strike GIF kept; **enriched** |
| 8 | EU drone laws 2025 (German text screenshot) | Module 1 "The EU Drone Regulation Framework" | **Transcribed** into Open/Specific/Certified + C0–C4 table |
| 9 | EU regulation 2024, A1/A2/A3 | Module 1: regulation chart slide + A1/A2/A3 table | Chart (helden.de) + EASA certificate image kept |
| 10–12 | 16 presentation topics | Module 2, three topic tables | |
| 13 | Competition announcement | Module 2 "The Competition" | EXERCISE tag |
| 14 | Expectation: 60h, grade 1.0 | Module 2 "Expectations" (+ Module 0 grading overview) | Reworked into expectation/achievement table: ≥60h homework + "able to explain the project" (stopwatch image dropped) |
| 15 | Section 3 overview | Module 4 cover + "The Standards Landscape" | **Enriched** with ARP 4754A / Mil-Std-882E rows |
| 16 | Ultralight photo (image only) | Module 4 "Safety Culture Predates Software" | Photo rotation fixed; **enriched** framing |
| 17 | Economics of defect fixing | Module 4 | Chart kept, Dawson et al. citation preserved |
| 18 | "Perfect" software can kill | Module 4 | MacKenzie pie chart kept |
| 19 | "Out of Control" | Module 4, two slides (HSG 238 chart + IS/ISN'T) | |
| 20 | SIL levels | Module 4 "Trust Levels & Categories" | |
| 21 | Principles 1–4 with accidents | Module 4, Principles 1/2/3/4 slides | Lufthansa 2904, Mars Polar Lander, Patriot images kept |
| 22 | Principle 4+1 + Boeing 777 FBW | Module 4, two slides | IEEE link preserved |
| 23–24 | DO-178C principle screenshots (image only) | Module 4, merged into Principles 1–4+1 | **Transcribed** from screenshots |
| 25 | Section 4 overview | Module 5 cover + "The Aviation Message Zoo" | All reference links preserved |
| 26 | NOTAM example | Module 5, two slides (example + field table) | |
| 27 | METAR example | Module 5 "METAR" | |
| 28 | AIXM | Module 5, two slides | **Enriched** with AIXM XML sample |
| 29 | ASTERIX | Module 5 "ASTERIX" | Protocol-analyzer screenshot kept |
| 25 (OLDI links) | OLDI | Module 5 "OLDI" | **Enriched** with message types and UTM relevance |
| 30–31 | Section 5: control & monitoring, UTM | Module 6 | UTM ecosystem diagram kept; fleet control & sensor fusion **enriched** |
| 32–34 | OpenAPI | Module 7 | YAML sample **enriched** (drone-flavored); 3.0-vs-3.1 + Swagger screenshots kept |
| 35–37 | Spring Reactive | Module 8 | MVC-vs-WebFlux + reactive-streams diagrams kept; Mono/Flux code **enriched** |
| 38–40 | Message queues / JMS | Module 9 | Queue-vs-topic ASCII diagram **enriched** |
| 41–44 | CQRS | Module 10 | Flow diagram kept; example repo link preserved |
| 45–47 | Karate | Module 11 | Both example screenshots **transcribed** into code blocks |
| 48–50 | Simulation, BlueSky | Module 12 | Simulator screenshot kept; competition setup **enriched** |
| 51–52 | GIS integration | Module 13 | DiPUL note expanded with links |
| 53–55 | Data transmission, edge/RL | Module 14 | Protocol list turned into table; RL video link preserved |
| 56–57 | Agile processes | — | **Dropped at instructor's request** (July 2026): generic agile content didn't fit the drone-focused conclusion |
| 58–59 | Conclusion & outlook | Module 15 ("Conclusion & Outlook"), three slides + wrap-up | |

## Added content (not in the PPTX)

**Module 3 "Lab: Hands-On DJI Mobile SDK"** (deck position directly after Module 2) — authored from the instructor's two assignment mails (July 2026):
mail 1 = feasibility test (hardware shopping list: Mini 4 Pro / RC-N3 / Android; toolchain: Android Studio,
DJI developer account + App Key, Mobile-SDK-Android-V5 sample; success = registration + battery via KeyManager),
mail 2 = optional deep dive (levels A–D: telemetry subscriptions, KeyManager key groups, live video stream,
simulator-only virtual stick) plus the guiding question "where does the SDK end?".

## Added assets (not in the PPTX)

| Asset | Source | Placed in |
|---|---|---|
| `edge-cases.png` | [monkeyuser.com — Edge Cases](https://www.monkeyuser.com/2019/edge-cases/) | Module 11 "Why We Test: Cover All Edge Cases" |
| `testing-vs-prod.png` | [monkeyuser.com — Testing vs. Production Environment](https://www.monkeyuser.com/2017/testing-vs-prod-env/) | Module 12 "Test Environment vs. Reality" |

Same comic source as the reference repo (lecture-se-tools-ai); watermarks/attribution preserved in the images and captions.

## Dropped assets

| Asset | Reason |
|---|---|
| image3.png (university logo) | Decorative, repeated on many slides |
| image11.png ("Challenge" stamp), image12.png ("Achtung Drohne" clipart) | Decorative clipart |
| image29.png (warning triangle) | Marker for speaker notes only |
| image8.png, image22–26.png, image35–36.png | German/third-party text screenshots — transcribed into native slides |
