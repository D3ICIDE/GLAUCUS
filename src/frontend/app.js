// ---------------- BACKEND CONFIG ----------------
const API_BASE = 'http://localhost:8080'; // LocalDevServer — swap once a real host exists
const sessionId = crypto.randomUUID();

// ---------------- POSITION STATE ----------------
// Falls back to a fixed coord until geolocation resolves, or until the
// dev "click map to simulate" handler overrides it.
let currentLat = 9.9, currentLon = 78.5;

if (navigator.geolocation) {
    navigator.geolocation.watchPosition(
        (pos) => {
            currentLat = pos.coords.latitude;
            currentLon = pos.coords.longitude;
            updateCoordsReadout();
            if (map) map.setView([currentLat, currentLon]);
        },
        (err) => console.warn('Geolocation unavailable, using default coords:', err.message),
        { enableHighAccuracy: true, maximumAge: 60000 }
    );
}

function updateCoordsReadout(){
    // coordsReadout (and the connDot inside it) are currently commented out in
    // index.html. Without this guard, callers of this function — including the
    // map click handler — throw here and silently abort everything after this
    // line (marker drawing, the geofence check itself never fires).
    if (!coordsReadout) return;
    const dot = document.getElementById('connDot');
    coordsReadout.innerHTML = `<span class="conn-dot ${dot && dot.classList.contains('online') ? 'online' : ''}" id="connDot"></span>${currentLat.toFixed(2)}°N · ${currentLon.toFixed(2)}°E`;
}

// ---------------- MAP SETUP ----------------
let map = null;
let mapInitialized = false;
let geofencePollHandle = null;

// Query-point search-radius indicator (dashed circle) — kept as a lightweight visual
// for "this is the area being checked", separate from the actual hazard shapes below.
let searchRadiusLayer = null;

// Actual hazard geometry drawn on the map, keyed by a stable identity per hit so we
// don't re-parse/re-render unchanged geometry on every 5s poll (this matters a lot
// once ST_Simplify tolerance still leaves e.g. the EEZ line at a few hundred points,
// fetched repeatedly). Key -> Leaflet layer.
let drawnHazardLayers = new Map();

let lastHazardState = null;   // for enter/exit edge detection (no repeat auto-popups while lingering)
let lastHazardousHits = [];   // raw hazardous hits from the most recent check, so the
// badge click can redisplay them (with updated distances) on demand

// Hazards received while Chart View was closed (map == null). Replayed onto the
// map the moment it's opened, instead of forcing initMap() to run early — forcing
// it open against a display:none container can leave Leaflet with a broken tile
// grid, which just trades this bug for a worse one.
let pendingMapHits = null;

function initMap(){
    if(mapInitialized) return;

    // doubleClickZoom disabled: a double-click on the map otherwise fires two
    // 'click' events (plus a dblclick) at the same coordinates, which previously
    // meant two identical geofence checks fired back-to-back for every double-click.
    // renderer: L.svg() forces the vector SVG layer to exist immediately (instead
    // of being created lazily on first shape) so hazard glow gradients can be
    // injected into it right away, before any hazard has been drawn.
    map = L.map('map', { zoomControl:true, doubleClickZoom:false, renderer: L.svg() }).setView([currentLat, currentLon], 7);

    // Esri World Dark Gray Canvas — dark, minimal basemap matching the GLAUCUS
    // dark UI, free and keyless. (CartoDB Dark Matter, used previously, started
    // requiring an API key and serves watermarked "API KEY REQUIRED" tiles
    // without one — that's the grid of tiled text seen in testing, not a bug
    // in this code.) Base layer is the dark ground/water fill; the Reference
    // layer on top adds crisp labels and borders, which Esri ships as a
    // separate tile set for this basemap so labels stay sharp at all zooms.
    L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}', {
        attribution: 'Tiles &copy; Esri &mdash; Esri, DeLorme, NAVTEQ',
        maxZoom: 16
    }).addTo(map);
    L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}', {
        maxZoom: 16
    }).addTo(map);

    ensureHazardGradients(map);

    // Dev-only: click anywhere on the map to simulate the vessel being there,
    // so hazard/geofence transitions can be tested without real GPS movement.
    map.on('click', (e) => {
        currentLat = e.latlng.lat;
        currentLon = e.latlng.lng;
        updateCoordsReadout();
        L.circleMarker([currentLat, currentLon], {
            radius: 5, color:'#F5EFE2', fillColor:'#F5EFE2', fillOpacity:1, weight:1
        }).addTo(map);
        // immediate: true — a user click should feel responsive, not wait out the debounce.
        // Overlapping/duplicate requests are still guarded against inside checkGeofence itself.
        requestGeofenceCheck(currentLat, currentLon, { immediate: true });
    });

    loadNearbyPfz();
    requestGeofenceCheck(currentLat, currentLon, { immediate: true });

    // NOTE: auto-polling (setInterval-based continuous geofence checking) has been
    // removed for now. Checks now only fire on explicit triggers: map open (above),
    // map click (below), and the hazard badge click. Re-enable by restoring
    // the setInterval call here if/when continuous GPS-tracking simulation is needed again.
    geofencePollHandle = null;

    mapInitialized = true;
}

// Injects two radial-gradient defs (hazardous / informational) into the map's
// SVG root, once. drawHazardsOnMap() references these by id as fillColor
// ('url(#hazardGlowHazard)' etc.) — SVG's fill attribute accepts a paint-server
// reference directly, and Leaflet just writes whatever string you give it into
// that attribute, so this works without any Leaflet-specific gradient support.
function ensureHazardGradients(map){
    const renderer = map.getRenderer(map);
    const svg = renderer && renderer._container;
    if (!svg || svg.querySelector('#hazardGlowHazard')) return;

    const NS = 'http://www.w3.org/2000/svg';
    const defs = document.createElementNS(NS, 'defs');

    const makeGlow = (id, color) => {
        const grad = document.createElementNS(NS, 'radialGradient');
        grad.setAttribute('id', id);
        grad.setAttribute('cx', '50%');
        grad.setAttribute('cy', '50%');
        grad.setAttribute('r', '50%');

        const stops = [
            ['0%', color, '0.55'],
            ['70%', color, '0.18'],
            ['100%', color, '0']
        ];
        stops.forEach(([offset, stopColor, opacity]) => {
            const stop = document.createElementNS(NS, 'stop');
            stop.setAttribute('offset', offset);
            stop.setAttribute('stop-color', stopColor);
            stop.setAttribute('stop-opacity', opacity);
            grad.appendChild(stop);
        });
        return grad;
    };

    defs.appendChild(makeGlow('hazardGlowHazard', '#E2673F'));
    defs.appendChild(makeGlow('hazardGlowSafe', '#7FA6A8'));
    svg.insertBefore(defs, svg.firstChild);
}

function toggleMap(){
    const toggle = document.getElementById('mapToggle');
    const main = document.getElementById('mainSplit');
    toggle.classList.toggle('on');
    main.classList.toggle('map-open');
    if(main.classList.contains('map-open')){
        setTimeout(() => {
            initMap();
            map && map.invalidateSize();

            // Draw whatever hazard state arrived from chat while the panel was
            // closed. Only the most recent set is kept, so this is always current.
            if (pendingMapHits) {
                drawHazardsOnMap(pendingMapHits);
                pendingMapHits = null;
            }
        }, 380);
    }
}

// ---------------- LIVE PFZ POINTS ----------------
let pfzMarkers = [];
async function loadNearbyPfz(){
    try {
        const resp = await fetch(`${API_BASE}/api/v1/poi/nearby?lat=${currentLat}&lon=${currentLon}&radiusKm=100&type=pfz`);
        if (!resp.ok) throw new Error(`status ${resp.status}`);
        const points = await resp.json();
        setConnState(true);

        pfzMarkers.forEach(m => map.removeLayer(m));
        pfzMarkers = [];

        points.forEach(p => {
            const marker = L.circleMarker([p.lat, p.lon], {
                radius: 7, color:'#2FBE9C', fillColor:'#1E8A72', fillOpacity:0.9, weight:2
            }).addTo(map);
            const dist = (p.distanceKm ?? -1).toFixed(1);
            marker.bindPopup(`<b>${p.name}</b><br>${dist} km away`);
            pfzMarkers.push(marker);
        });
    } catch (err) {
        setConnState(false);
        console.warn('Could not load live PFZ points (is LocalDevServer running on :8080?):', err);
    }
}

// ---------------- GEOFENCE / HAZARD CHECK ----------------
const GEOFENCE_RADIUS_METERS = 50000; // matches the wrapper's default 50km overload

let geofenceAbortController = null;
let geofenceDebounceTimer = null;
let lastCheckedKey = null;

function roundCoordKey(lat, lon){
    return `${lat.toFixed(4)},${lon.toFixed(4)}`;
}

function requestGeofenceCheck(lat, lon, { immediate = false } = {}){
    clearTimeout(geofenceDebounceTimer);
    const run = () => checkGeofence(lat, lon);
    if (immediate) run();
    else geofenceDebounceTimer = setTimeout(run, 300);
}

async function checkGeofence(lat, lon){
    const key = roundCoordKey(lat, lon);

    if (key === lastCheckedKey && geofenceAbortController) {
        return;
    }

    if (geofenceAbortController) {
        geofenceAbortController.abort();
    }
    geofenceAbortController = new AbortController();
    lastCheckedKey = key;

    try {
        const resp = await fetch(
            `${API_BASE}/api/v1/geofence/check?lat=${lat}&lon=${lon}`,
            { signal: geofenceAbortController.signal }
        );
        if (!resp.ok) throw new Error(`status ${resp.status}`);
        const hits = await resp.json();
        setConnState(true);
        renderGeofenceState(hits);
    } catch (err) {
        if (err.name === 'AbortError') return;
        setConnState(false);
        console.warn('Geofence check failed (is LocalDevServer running on :8080?):', err);
    } finally {
        geofenceAbortController = null;
    }
}

function isHazardous(hit){
    const level = (hit.riskLevel || '').toLowerCase();
    return level !== 'safe' && level !== '';
}

function formatHazardMessage(message){
    if (message == null) return '';
    if (typeof message === 'string') return message;
    if (Array.isArray(message)) return message.map(formatHazardMessage).join('<br>');
    if (typeof message === 'object') {
        if (Array.isArray(message.advisories)) {
            const dayLabel = message.day != null ? `Day ${message.day}: ` : '';
            return dayLabel + message.advisories
                .map(a => `${a.text || ''}${a.boat_width ? ` (boats &lt;${a.boat_width}m)` : ''}`)
                .join('<br>');
        }
        return Object.entries(message).map(([k, v]) => `${k}: ${formatHazardMessage(v)}`).join('<br>');
    }
    return String(message);
}

function hazardKey(hit){
    return `${hit.source}::${hit.fetchedAt}`;
}

// Two backend shapes are in play: /api/v1/chat's mapUpdate sends the leaner
// post-Bug#1 shape (hit.geometry, already-parsed object; no distanceMeters or
// message), while /api/v1/geofence/check still sends the older shape
// (hit.geometryJson as a string, plus distanceMeters/message). Handle both.
function getHazardGeometry(hit){
    if (hit.geometry) return hit.geometry;
    if (hit.geometryJson) {
        try {
            return JSON.parse(hit.geometryJson);
        } catch (e) {
            console.warn('Unparseable geometryJson for', hit.source, e);
            return null;
        }
    }
    return null;
}

// distanceMeters isn't present on hazards that arrived via chat (mapUpdate) —
// return '' rather than producing "NaN km".
function formatDistanceKm(hit){
    return (typeof hit.distanceMeters === 'number')
        ? `${(hit.distanceMeters / 1000).toFixed(1)}km`
        : '';
}

function popupHtmlFor(hit){
    const dist = formatDistanceKm(hit);
    const msg = hit.message != null ? `<br>${formatHazardMessage(hit.message)}` : '';
    return `<b>${hit.source}</b> [${hit.riskLevel}]${dist ? ` — ${dist}` : ''}${msg}`;
}

// ---------------- SYSTEM ALERT (full-screen emergency popup) ----------------
let systemAlertEscHandler = null;

function showSystemAlert(hazardousHits){
    const overlay = document.getElementById('systemAlertOverlay');
    const subtitle = document.getElementById('systemAlertSubtitle');
    const body = document.getElementById('systemAlertBody');
    if (!overlay || !subtitle || !body) {
        console.warn('System alert markup missing from index.html — falling back to chat.');
        const summary = hazardousHits
            .map(h => `<b>${h.source}</b> (${h.riskLevel}${formatDistanceKm(h) ? `, ${formatDistanceKm(h)}` : ''}): ${formatHazardMessage(h.message)}`)
            .join('<br>');
        addBotMessage(summary, 'HAZARD ALERT', true);
        return;
    }

    subtitle.textContent = hazardousHits.length > 1
        ? `${hazardousHits.length} HAZARDS DETECTED IN RANGE`
        : 'HAZARD DETECTED IN RANGE';

    body.innerHTML = hazardousHits.map(h => {
        const dist = formatDistanceKm(h);
        return `
        <div class="sa-entry">
            <div class="sa-entry-row">
                <span class="sa-entry-source">${h.source}</span>
                ${dist ? `<span class="sa-entry-dist">${dist.toUpperCase()}</span>` : ''}
            </div>
            <div class="sa-entry-risk">RISK: ${(h.riskLevel || 'UNKNOWN').toUpperCase()}</div>
            <div class="sa-entry-msg">${formatHazardMessage(h.message)}</div>
        </div>
    `;
    }).join('');

    overlay.classList.remove('sa-clear');
    setSystemAlertChrome('⚠', 'SYSTEM WARNING');
    playSystemAlertEntrance(overlay);
    showHazardBadge();
}

function showClearAlert(){
    const overlay = document.getElementById('systemAlertOverlay');
    const subtitle = document.getElementById('systemAlertSubtitle');
    const body = document.getElementById('systemAlertBody');
    if (!overlay || !subtitle || !body) {
        addBotMessage('No hazardous advisories in range anymore.', 'HAZARD ALERT', false);
        return;
    }

    subtitle.textContent = 'OUT OF HAZARD ZONE';
    body.innerHTML = `
        <div class="sa-entry">
            <div class="sa-entry-msg">No hazardous advisories remain within the ${(GEOFENCE_RADIUS_METERS/1000).toFixed(0)}km search radius. Standing by.</div>
        </div>
    `;

    overlay.classList.add('sa-clear');
    setSystemAlertChrome('✓', 'ALL CLEAR');
    playSystemAlertEntrance(overlay);
    hideHazardBadge();
}

function setSystemAlertChrome(glyph, title){
    const titleEl = document.getElementById('systemAlertTitle');
    if (titleEl) titleEl.textContent = title;
    document.querySelectorAll('#systemAlertOverlay .sa-glyph').forEach(el => el.textContent = glyph);
}

function playSystemAlertEntrance(overlay){
    overlay.classList.remove('sa-flicker');
    void overlay.offsetWidth;
    overlay.classList.add('active');
    overlay.setAttribute('aria-hidden', 'false');

    systemAlertEscHandler = (e) => { if (e.key === 'Escape') dismissSystemAlert(); };
    document.addEventListener('keydown', systemAlertEscHandler);
}

function dismissSystemAlert(){
    const overlay = document.getElementById('systemAlertOverlay');
    if (!overlay) return;
    overlay.classList.remove('active', 'sa-clear');
    overlay.setAttribute('aria-hidden', 'true');
    if (systemAlertEscHandler) {
        document.removeEventListener('keydown', systemAlertEscHandler);
        systemAlertEscHandler = null;
    }
}

// ---------------- persistent "hazards active" status chip ----------------
// Visibility is driven entirely by renderGeofenceState() below, based on
// whether a hazard is actually in range — the badge click handler further
// down never touches this directly.
function showHazardBadge(){
    const badge = document.getElementById('hazardBadge');
    if (badge) badge.classList.add('active');
}
function hideHazardBadge(){
    const badge = document.getElementById('hazardBadge');
    if (badge) badge.classList.remove('active');
}

// Badge click handler. Fires a real fetch so distances/geometry are current,
// then re-opens the popup. Doesn't manage badge visibility itself:
// renderGeofenceState() (invoked inside checkGeofence) already shows/hides
// the badge purely based on whether the vessel is still in a hazard zone.
async function onHazardBadgeClick(){
    await checkGeofence(currentLat, currentLon);

    if (lastHazardousHits.length) {
        showSystemAlert(lastHazardousHits); // updated distances baked in
    }
    // If no longer hazardous, renderGeofenceState has already hidden the
    // badge as part of the check above — nothing to pop up.
}

// Handles hazard state that isn't tied to Leaflet: alerts, the persistent
// badge, and (via showSystemAlert's fallback) chat. Always runs, regardless
// of whether Chart View has ever been opened.
function renderGeofenceState(hits){
    hits = hits || [];

    const hazardousHits = hits.filter(isHazardous);
    lastHazardousHits = hazardousHits;

    const currentState = hazardousHits.length
        ? hazardousHits.map(h => h.source).sort().join(',')
        : null;

    if (currentState !== lastHazardState) {
        if (currentState) {
            showSystemAlert(hazardousHits);
        } else if (lastHazardState) {
            dismissSystemAlert();
            showClearAlert();
        }
        lastHazardState = currentState;
    }

    // Actual Leaflet drawing needs a real, initialized map. If Chart View
    // hasn't been opened yet, stash the hits and draw them the moment it is,
    // instead of forcing initMap() to run against a hidden container.
    if (!map) {
        pendingMapHits = hits;
        return;
    }
    drawHazardsOnMap(hits);
}

// Pure Leaflet drawing — only ever called once `map` is known to exist
// (either live, from renderGeofenceState, or replayed from toggleMap).
function drawHazardsOnMap(hits){
    // ---- search-radius indicator around the query point (context only) ----
    const anyHazard = hits.some(isHazardous);
    const radiusColor = anyHazard ? '#E2673F' : '#7FA6A8';

    if (searchRadiusLayer) {
        map.removeLayer(searchRadiusLayer);
    }
    searchRadiusLayer = L.circle([currentLat, currentLon], {
        radius: GEOFENCE_RADIUS_METERS, color: radiusColor,
        fillOpacity: anyHazard ? 0.08 : 0.03,
        weight: 1.5, dashArray: '4,4'
    }).addTo(map);

    // ---- actual hazard geometry, added/removed only where the hit set changed ----
    const currentKeys = new Set();

    hits.forEach(hit => {
        const key = hazardKey(hit);
        currentKeys.add(key);

        if (drawnHazardLayers.has(key)) {
            return;
        }

        const geom = getHazardGeometry(hit);
        if (!geom) {
            return;
        }

        const hazardous = isHazardous(hit);
        const hazardColor = hazardous ? '#E2673F' : '#7FA6A8';
        const glowFill = hazardous ? 'url(#hazardGlowHazard)' : 'url(#hazardGlowSafe)';

        const layer = L.geoJSON(geom, {
            style: {
                color: hazardColor,
                weight: 1.5,
                opacity: 0.9,
                fillColor: glowFill,
                fillOpacity: 1 // the gradient itself fades to transparent; this just lets it show fully
            },
            pointToLayer: (feature, latlng) => {
                return L.circleMarker(latlng, {
                    radius: hazardous ? 16 : 11,
                    color: hazardColor,
                    weight: 1.5,
                    fillColor: glowFill,
                    fillOpacity: 1
                });
            }
        });
        layer.bindPopup(popupHtmlFor(hit));
        layer.addTo(map);

        // Soft glow on the outline itself (CSS drop-shadow on the SVG path/circle),
        // so the boundary reads as a glow rather than a hard line on the dark tiles.
        layer.eachLayer(sub => {
            const el = sub.getElement && sub.getElement();
            if (el) el.style.filter = `drop-shadow(0 0 5px ${hazardColor}99)`;
        });

        drawnHazardLayers.set(key, layer);
    });

    for (const [key, layer] of drawnHazardLayers) {
        if (!currentKeys.has(key)) {
            map.removeLayer(layer);
            drawnHazardLayers.delete(key);
        }
    }
}

function setConnState(online){
    const dot = document.getElementById('connDot');
    if (dot) dot.classList.toggle('online', online);
}

// ---------------- CHAT LOGIC ----------------
const log = document.getElementById('log');
const coordsReadout = document.getElementById('coordsReadout');

function timeNow(){
    return new Date().toLocaleTimeString('en-IN', { hour:'2-digit', minute:'2-digit' });
}

function addUserMessage(text){
    const div = document.createElement('div');
    div.className = 'msg user';
    div.textContent = text;
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
}

// Accepts an optional `trace` array (collected agent_start/agent_end/agent_error
// events from the SSE stream for this query) and, if present, renders it as a
// collapsible <details> block under the answer so the record of which agents ran
// and what they returned isn't thrown away once the loading bubble disappears.
function addBotMessage(text, tag, warn, trace, elapsedMs){
    const wrap = document.createElement('div');
    wrap.className = 'msg bot';

    let traceHtml = '';
    if (trace && trace.length) {
        const endEvents = trace.filter(e => e.type === 'agent_end');
        const rows = trace.map(evt => {
            if (evt.type === 'agent_start') {
                return `<div class="trace-row trace-start"><b>${evt.agent}</b> started…</div>`;
            }
            if (evt.type === 'agent_end') {
                const label = evt.agent === evt.outputKey ? evt.agent : `${evt.agent} → ${evt.outputKey}`;
                const out = evt.output != null ? String(evt.output).slice(0, 300) : '';
                return `<div class="trace-row"><b>${label}</b>${out ? `: ${out}` : ''}</div>`;
            }
            if (evt.type === 'agent_error') {
                return `<div class="trace-row error"><b>${evt.agent}</b> failed: ${evt.message ?? 'unknown error'}</div>`;
            }
            return '';
        }).join('');

        const summaryLabel = elapsedMs != null
            ? `Thought for ${formatElapsed(elapsedMs)}`
            : `Show agent trace (${endEvents.length} step${endEvents.length === 1 ? '' : 's'})`;

        traceHtml = `<details class="agent-trace"><summary>${summaryLabel}</summary>${rows}</details>`;
    }

    wrap.innerHTML = `
      <div class="bot-avatar"></div>
      <div>
        <div class="bot-bubble">
          ${tag ? `<span class="tag${warn ? ' warn' : ''}">${tag}</span><br>` : ''}
          ${text}
        </div>
        ${traceHtml}
        <div class="timestamp">${timeNow()}</div>
      </div>
    `;
    log.appendChild(wrap);
    log.scrollTop = log.scrollHeight;
    return wrap;
}

function addLoadingMessage(){
    const wrap = document.createElement('div');
    wrap.className = 'msg bot';
    wrap.innerHTML = `
      <div class="bot-avatar">O</div>
      <div>
        <div class="bot-bubble loading">
          <span class="loading-text">Thinking…</span>
          <span class="loading-timer">0s</span>
        </div>
      </div>
    `;
    log.appendChild(wrap);
    log.scrollTop = log.scrollHeight;
    return wrap;
}

// ---------------- AGENT ACTIVITY STREAM ----------------
let agentEventSource = null;
let currentTrace = [];
let streamDoneResolve = null;

function startAgentStream(loadingEl, startTime){
    if (agentEventSource) agentEventSource.close();
    currentTrace = [];

    agentEventSource = new EventSource(`${API_BASE}/events?sessionId=${sessionId}`);
    const textEl = loadingEl.querySelector('.loading-text');
    const timerEl = loadingEl.querySelector('.loading-timer');

    const timerHandle = setInterval(() => {
        timerEl.textContent = formatElapsed(performance.now() - startTime);
    }, 100);

    const donePromise = new Promise((resolve) => { streamDoneResolve = resolve; });

    agentEventSource.onmessage = (e) => {
        let evt;
        try { evt = JSON.parse(e.data); } catch { return; }

        if (evt.type === 'agent_start' || evt.type === 'agent_end' || evt.type === 'agent_error') {
            currentTrace.push(evt);
        }

        if (evt.type === 'agent_start') textEl.textContent = `⏳ ${evt.agent} working…`;
        if (evt.type === 'agent_end')   textEl.textContent = `✅ ${evt.agent} done — continuing…`;
        if (evt.type === 'agent_error') textEl.textContent = `⚠ ${evt.agent} hit an error…`;

        if (evt.type === 'done') {
            agentEventSource.close();
            agentEventSource = null;
            if (streamDoneResolve) { streamDoneResolve(); streamDoneResolve = null; }
        }
        log.scrollTop = log.scrollHeight;
    };

    agentEventSource.onerror = () => {
        if (agentEventSource) { agentEventSource.close(); agentEventSource = null; }
        if (streamDoneResolve) { streamDoneResolve(); streamDoneResolve = null; }
        clearInterval(timerHandle);
    };

    donePromise.then(() => clearInterval(timerHandle));
    return donePromise;
}

async function sendMessage(){
    const input = document.getElementById('textInput');
    const text = input.value.trim();
    if(!text) return;
    addUserMessage(text);
    input.value = '';

    const loadingEl = addLoadingMessage();
    const startTime = performance.now();
    const streamDone = startAgentStream(loadingEl, startTime);

    try {
        const resp = await fetch(`${API_BASE}/api/v1/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: text, lat: currentLat, lon: currentLon, sessionId })
        });
        if (!resp.ok) throw new Error(`status ${resp.status}`);
        const data = await resp.json();
        setConnState(true);

        await Promise.race([
            streamDone,
            new Promise(resolve => setTimeout(resolve, 1500))
        ]);

        const elapsedMs = performance.now() - startTime;   // <-- capture before removing the bubble

        loadingEl.remove();
        addBotMessage(data.text, data.tag, data.warn, currentTrace, elapsedMs);

        if (data.mapUpdate) {
            try {
                renderGeofenceState(data.mapUpdate);
            } catch (mapErr) {
                console.warn('Map update failed to render (answer above is still valid):', mapErr);
            }
        }
    } catch (err) {
        setConnState(false);
        if (agentEventSource) { agentEventSource.close(); agentEventSource = null; }
        loadingEl.remove();
        addBotMessage("Couldn't reach the ORCA backend — is LocalDevServer running on :8080? Falling back to a general answer.", 'OFFLINE', true);
        const r = generateResponse(text);
        addBotMessage(r.text, r.tag, r.warn);
    }
}

function generateResponse(userText){
    const t = userText.toLowerCase();

    if(t.includes('safe') || t.includes('venture') || t.includes('go out')){
        return { tag:'SAFETY ADVISORY', warn:false, text:
                "Sea conditions off Tamil Nadu look moderate today — wave height around 1.6–2.0m, wind from the southwest at 25–35 kmph. Generally safe for larger boats; smaller craft should stay alert through the afternoon as wind picks up." };
    }
    if(t.includes('fish') || t.includes('pfz') || t.includes('where')){
        return { tag:'FISHING ZONE', warn:false, text:
                "There's an active fishing zone advisory near Valiyapani reef, about 12–17km southwest of the coast. Water conditions there currently support good fish activity — toggle Chart View to see it on the map." };
    }
    if(t.includes('tide')){
        return { tag:'TIDE', warn:false, text:
                "Tide near Tuticorin is currently low, around 0.3m, rising toward a high of 0.85m by early evening." };
    }
    if(t.includes('cyclone') || t.includes('storm') || t.includes('warning')){
        return { tag:'HAZARD ALERT', warn:true, text:
                "No cyclone warning is active for your area right now. There is a rough-sea advisory near the Cauvery delta coast — wave heights above 2m. Worth checking Chart View before heading that direction." };
    }
    if(t.includes('wind')){
        return { tag:'WIND', warn:false, text:
                "Wind is currently from the southwest at roughly 28 kmph near the coast, strengthening slightly toward evening." };
    }
    return { tag:null, warn:false, text:
            "I can help with sea safety, fishing zones, tides, weather, and hazard alerts for the coast. Try asking something like \"is it safe to go out tomorrow\" or \"where should I fish near Tuticorin.\"" };
}

// Seed the conversation
addBotMessage("Welcome aboard. Ask me about sea safety, tides, weather, or where to fish along the coast — in text or by voice.", null, false);
updateCoordsReadout();

// ---------------- VOICE INPUT ----------------
let recognition = null;
let listening = false;
const micBtn = document.getElementById('micBtn');
const listeningHint = document.getElementById('listeningHint');

const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
if(SpeechRecognition){
    recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = false;
    recognition.lang = 'en-IN';

    recognition.onresult = (event) => {
        const transcript = event.results[0][0].transcript;
        document.getElementById('textInput').value = transcript;
        sendMessage();
    };
    recognition.onend = () => {
        listening = false;
        micBtn.classList.remove('listening');
        listeningHint.textContent = '';
    };
    recognition.onerror = () => {
        listening = false;
        micBtn.classList.remove('listening');
        listeningHint.textContent = 'Could not hear that — try again';
        setTimeout(() => listeningHint.textContent = '', 2000);
    };
}

function toggleListening(){
    if(!recognition){
        listeningHint.textContent = 'Voice input not supported in this browser';
        setTimeout(() => listeningHint.textContent = '', 2500);
        return;
    }
    if(listening){
        recognition.stop();
    } else {
        listening = true;
        micBtn.classList.add('listening');
        listeningHint.textContent = 'Listening…';
        recognition.start();
    }
}

function formatElapsed(ms){
    const totalSeconds = ms / 1000;
    if (totalSeconds < 60) return `${totalSeconds.toFixed(1)}s`;
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = Math.round(totalSeconds % 60);
    return `${minutes}m ${seconds}s`;
}