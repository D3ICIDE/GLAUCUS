# GLAUCUS

**Marine EcOsystem Reasoning with Collaborative Agents**

![Java](https://img.shields.io/badge/Java-25-orange)
![LangChain4j](https://img.shields.io/badge/LangChain4j-1.19-blue)
![PostGIS](https://img.shields.io/badge/PostgreSQL-PostGIS-336791)
![Status](https://img.shields.io/badge/status-prototype-yellow)

GLAUCUS is a conversational, multi-agent platform that answers marine and coastal questions for fishers, boat operators and coastal officials. A user asks in natural language (typed or spoken, in their own language) and gets an explainable, location-aware answer through chat, a live map and hazard alerts.

Every fact in an answer comes from a verified data store, and every geofence decision is computed by a spatial database, never by the language model.


---

## Contents

- [Key features](#key-features)
- [How it works](#how-it-works)
- [Agents](#agents)
- [Data sources](#data-sources)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [API reference](#api-reference)
- [Roadmap](#roadmap)
- [Disclaimer](#disclaimer)

---

## Key features

- **Multi-agent reasoning.** A Supervisor decomposes each request and invokes only the sub-agents the question needs (risk, geo-spatial, data discovery, ocean analytics, weather, visualisation, reporting).
- **Grounded answers.** The LLM can only call a fixed set of typed tools. It never writes raw SQL and never browses the internet.
- **Geofencing and hazards.** Containment, proximity and nearest-zone checks run inside PostgreSQL/PostGIS and return hazard geometry that the map can draw.
- **Official data.** Warnings and advisories from IMD, NDMA SACHET, RSMC and INCOIS are collected by fetchers into the database.
- **Multilingual and voice.** Language detection, translation and speech input/output through Azure AI services.
- **Streaming and explainability.** Answers stream token by token over Server-Sent Events, and agent execution can be traced.
- **Live map UI.** A Leaflet web client with chat, potential fishing zones, hazard overlays and alerts.

## How it works

<img width="942" height="795" alt="image" src="https://github.com/user-attachments/assets/3eb1cb9e-6ce3-484b-8d85-cb21bd3898ba" />


**Design rule:** the data layer and the language layer are separate. Fetchers collect and store facts; the agents only read them through typed tools. If a source is down, the answer says data is missing instead of inventing it.

**Request flow**

1. The client sends a message with the user's coordinates to `POST /api/v1/chat` and listens on `GET /api/v1/events` for streamed tokens.
2. The Supervisor classifies the request and delegates to the sub-agents it needs.
3. Sub-agents call typed tools (hazards, geofence, ocean data, locations, points of interest).
4. The Reporting agent writes the answer. The response also carries map geometry and points of interest for the client to draw.

## Agents

| Agent | Responsibility |
|---|---|
| `GlaucusSupervisor` | Understands intent, plans the task and routes work to sub-agents |
| `RiskAssessmentAgent` | Combines hazards and conditions into a risk level and safety advice for one or more positions |
| `GeoSpatialReasoningAgent` | Resolves places, zones and distances |
| `MarineDataDiscoveryAgent` | Finds which marine datasets and advisories apply to the question |
| `OceanAnalyticsAgent` | Sea-surface temperature, waves, currents and wind around a point |
| `WeatherIntelligenceAgent` | Current weather and warnings |
| `VisualizationAgent` | Prepares map layers and visual output |
| `ReportingAgent` | Writes the user-facing answer and streams it |

The agents live in `src/main/java/agents/`, and `GlaucusPipeline` wires them together. Only the last agent in a sequence can stream, so the Supervisor runs to completion first and the Reporting agent then streams the final answer.

## Data sources

| Source | Data |
|---|---|
| IMD (including RSMC New Delhi) | Fishermen warnings, port warnings, coastal stations, buoys and ships |
| NDMA SACHET | Location-wise alerts and alert polygons |
| INCOIS | Potential fishing zones, SVAS advisories, landing centres |
| Marine Regions (VLIZ) | EEZ boundary polygon |
| Ocean readings | Sea-surface temperature, waves, currents and wind, stored as point readings |
| OpenStreetMap Nominatim | Place-name to coordinates lookup |
| Points-of-interest table | Ports, landing centres and other points of interest stored in the database |

Fetchers are in `src/main/java/fetchers/`, and the response models for each source are in `fetchers/response/`.

## Tech stack

| Layer | Technology |
|---|---|
| Language and build | Java 25, Maven |
| Server | `com.sun.net.httpserver` with virtual threads |
| Agents | [LangChain4j](https://docs.langchain4j.dev/) 1.19 (agentic module) with Gemini, Mistral and OpenAI-compatible providers (Groq, OpenRouter, TokenRouter) |
| Database | PostgreSQL with PostGIS and pgRouting (JDBC driver 42.7) |
| Parsing and data | Jsoup, PDFBox, Tabula, Gson, NetCDF (Unidata CDM) |
| Speech and translation | Azure AI Speech and Azure AI Translator |
| Frontend | HTML, CSS and JavaScript with Leaflet |

## Project structure

```
database/                schema.sql (tables, views, route function) and seed.sql (data snapshot)
src/
  main/java/
    Main.java            entry point (starts the server on :8080)
    core/                Server (HTTP endpoints), Orchestration (agent wiring), ModelFactory (LLM clients)
    agents/              Supervisor, sub-agents, pipeline, streaming listener
    tools/               Typed tool wrappers the agents are allowed to call
    fetchers/            Data collectors (one runnable class per source) and response models
    utils/               Config (keys, model names, URLs), DatabaseManager, SSE broker, map context, voice, HTTP helpers
  main/resources/        Sample ocean data (SST.nc) and helper scripts
  frontend/              Web client (index.html, app.js, style.css)
pom.xml
.env.example             template for API keys (copy to .env)
```

## Getting started

### Prerequisites

- JDK 25 and Maven
- PostgreSQL with the PostGIS and pgRouting extensions
- API key for at least one supported LLM provider
- (Optional) Azure Speech and Translator keys for voice and multilingual features

### 1. Clone

```bash
git clone https://github.com/D3ICIDE/GLAUCUS.git
cd GLAUCUS
```

### 2. Database

Create a database named `glaucus_db`, then restore the schema and the seed data. `schema.sql` creates the PostGIS and pgRouting extensions, the tables, the hazard views, the indexes and the route-finding function. `seed.sql` is a data-only dump (about 48 MB) taken on 2026-11-07 that fills the tables, including the India EEZ polygon and the place-name lookup, so the app has data before you run any fetcher.

```bash
createdb -U postgres glaucus_db
psql -U postgres -d glaucus_db -f database/schema.sql
psql -U postgres -d glaucus_db -f database/seed.sql
psql -U postgres -d glaucus_db -c "REFRESH MATERIALIZED VIEW hazard_eez_boundary;"
```

Both files were dumped with PostgreSQL 18.6 and contain `\restrict` / `\unrestrict` lines, so use a recent `psql` client.

`hazard_eez_boundary` is a materialized view that draws a 5 km caution buffer around the boundary of each polygon in `india_eez_polygon` and reports it as an `EEZ BOUNDARY` hazard ("Approaching international maritime boundary"). The dump does not populate it, so the last command above is required: until the view is refreshed, queries against `hazard_unified` fail with "materialized view has not been populated". Run the refresh again whenever `india_eez_polygon` changes.

The backend connects to `localhost:5432/glaucus_db` as user `postgres`, with the password read from `DB_Password` in `.env` (see `src/main/java/utils/DatabaseManager.java`).

| Group | Objects |
|---|---|
| Warnings and advisories | `fishermen_warnings`, `port_warnings`, `lightning_alerts`, `svas_districts`, `svas_advisory_status`, `svas_advisory_text` |
| Fishing zones and coast | `pfz_point`, `pfz_lines`, `pfz_eez`, `landing_centers`, `marine_observations`, `tide_forecasts` |
| Ocean and weather data | `ocean_point_readings`, `weather_hourly`, `weather_daily_summary`, `climatology_baseline` |
| Boundaries and places | `india_eez`, `india_eez_polygon`, `location_lookup`, `geocoded_places` |
| Views | `hazard_unified` (combines `hazard_fishermen`, `hazard_ports`, `hazard_observations`, `hazard_sva` and the materialized view `hazard_eez_boundary`), `poi_unified` |
| Routing | function `find_safe_route(...)`, a pgRouting A* search over a grid that avoids hazard areas |
| Logging | `fetch_log` |

### 3. Configuration

Copy the template and fill in your keys. The backend loads `.env` from the project root, and the file must exist.

```bash
cp .env.example .env
```

| Variable (in `.env`) | Used for |
|---|---|
| `DB_Password` | Password for the local `postgres` user (add this line in core.Config.java) |
| `gemini_api` | Google Gemini models |
| `groq_api` | Groq-hosted models (Supervisor) |
| `open_router_api`, `token_router_api`, `mistral_api` | Optional OpenRouter, TokenRouter and Mistral models |
| `weather_api` | Weather data |
| `tavily_api`, `exa_api`, `nvidia_api` | Optional search and model providers |

Server-side speech recognition and synthesis also read the environment variables `AZURE_SPEECH_KEY` and `AZURE_SPEECH_REGION`.

Model names, base URLs and data-source URLs are defined in `src/main/java/utils/Config.java`. LLM clients are built in `core/ModelFactory.java`.


### 4. Load data

Each fetcher in `src/main/java/fetchers/` is a standalone program that fills its tables. Run the ones you need, for example:

```bash
mvn compile exec:java -Dexec.mainClass=fetchers.PotentialFishingZoneFetcher
mvn compile exec:java -Dexec.mainClass=fetchers.PortWarningFetcher
mvn compile exec:java -Dexec.mainClass=fetchers.FishermenAlertFetcher
mvn compile exec:java -Dexec.mainClass=fetchers.OceanPointDataFetcher
```

Others: `GeneralAlertFetcher`, `CoastObservationFetcher`, `LandingCentresFetcher`, `ExclusiveEconomicZoneLineFetcher`. The seed data is a snapshot, so run them again whenever you want fresh warnings and ocean readings. An automatic refresh scheduler is on the roadmap.

### 5. Run the backend

```bash
mvn compile exec:java -Dexec.mainClass=Main
```

The server starts on `http://localhost:8080`.

### 6. Run the web client

Copy the example config and add your browser-side Azure keys:

```bash
cp src/frontend/config.example.js src/frontend/config.js
```

Then serve the folder and open it in a browser:

```bash
cd src/frontend
# open http://localhost:5500
```

The client expects the backend at `http://localhost:8080` (see `API_BASE` in `app.js`). Keys in `config.js` are visible to anyone who loads the page, so use it for local development only.

## API reference

| Method and path | Description |
|---|---|
| `POST /api/v1/chat` | Body: `{ "message", "lat", "lon", "sessionId" }`. Returns the final answer plus `mapUpdate` (hazard geometry), `poiUpdate` (points of interest) and a hazard flag. |
| `GET /api/v1/events?sessionId=<id>` | Long-lived Server-Sent Events stream. Streamed answer tokens for that session are pushed here. |
| `GET /api/v1/geofence?lat=<lat>&lon=<lon>` | Hazards near a coordinate, with geometry, as JSON. |

The web client also calls `/api/v1/poi/nearby`, which the server does not serve yet (see the roadmap).

## Roadmap

- Native Android client that reuses the same API
- Offline mode with an on-device SQLite mirror and rule-based alerts
- NavIC DAT-SG terminal integration for emergency messages with no cellular network
- Hazard-aware route optimisation: the `find_safe_route` database function exists, but it is not yet exposed through the API
- Points-of-interest endpoint (`/api/v1/poi`) and route endpoint (`/api/v1/routeOptimization`)
- Automatic scheduled refresh of all data sources

  ##Screenshot
  <img width="1891" height="868" alt="image" src="https://github.com/user-attachments/assets/4d985209-bd99-4cc5-8135-ab8234d8d905" />
  <img width="1903" height="869" alt="image" src="https://github.com/user-attachments/assets/a6ac6ba0-6878-443e-92f9-7ca1182d62b6" />
  <img width="1904" height="847" alt="image" src="https://github.com/user-attachments/assets/120e3561-6749-45e3-8b30-be9981e0fbb9" />
  <img width="1901" height="862" alt="image" src="https://github.com/user-attachments/assets/8445beff-3062-45ef-8255-17f4991a945e" />
  <img width="1908" height="878" alt="image" src="https://github.com/user-attachments/assets/73eb50da-6aef-44d6-9aa9-ce0bb32b8e74" />






## Disclaimer

GLAUCUS is a decision-support aid. Official warnings from IMD, INCOIS, NDMA and RSMC remain the authority; always follow them.
