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

let lastHazardState = null; // for enter/exit edge detection (no repeat alerts while lingering)

function initMap(){
    if(mapInitialized) return;

    // doubleClickZoom disabled: a double-click on the map otherwise fires two
    // 'click' events (plus a dblclick) at the same coordinates, which previously
    // meant two identical geofence checks fired back-to-back for every double-click.
    map = L.map('map', { zoomControl:true, doubleClickZoom:false }).setView([currentLat, currentLon], 7);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap contributors',
        maxZoom: 12
    }).addTo(map);

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
    // removed for now. Checks now only fire on explicit triggers: map open (above)
    // and map click (below). Re-enable by restoring the setInterval call here if/when
    // continuous GPS-tracking simulation is needed again.
    geofencePollHandle = null;

    mapInitialized = true;
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
        }, 380);
    }
}

// ---------------- LIVE PFZ POINTS ----------------
// Replaces the old hardcoded pfzPoints array — pulled from PoiTool.findWithinRadius
// via LocalDevServer. Falls back silently (no points drawn) if the dev server isn't running.
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
// GeoFenceToolWrapper.getHazardsWithGeometryForMap returns a flat JSON array of hits:
//   [{ source, riskLevel, message, fetchedAt, distanceMeters, geometryJson }, ...]
// geometryJson is a GeoJSON geometry string (Polygon/MultiPolygon/LineString/etc),
// pre-simplified server-side (ST_SimplifyPreserveTopology) so payload stays reasonable
// even for large shapes like the EEZ boundary. We draw each hit's real shape rather
// than a generic marker.
const GEOFENCE_RADIUS_METERS = 50000; // matches the wrapper's default 50km overload

let geofenceAbortController = null;   // in-flight request, if any — aborted when superseded
let geofenceDebounceTimer = null;
let lastCheckedKey = null;            // rounded "lat,lon" of the last request actually sent

function roundCoordKey(lat, lon){
    // ~11m precision at the equator — plenty for "did the point actually change"
    return `${lat.toFixed(4)},${lon.toFixed(4)}`;
}

// Debounced/deduped entry point — use this everywhere instead of calling
// checkGeofence directly. Collapses rapid repeated calls (double taps, a poll
// tick landing right after a click, etc.) so we don't fire redundant requests.
function requestGeofenceCheck(lat, lon, { immediate = false } = {}){
    clearTimeout(geofenceDebounceTimer);
    const run = () => checkGeofence(lat, lon);
    if (immediate) run();
    else geofenceDebounceTimer = setTimeout(run, 300);
}

async function checkGeofence(lat, lon){
    const key = roundCoordKey(lat, lon);

    // Same point (to ~11m) as the request currently in flight — skip rather than
    // firing a second identical request while the first hasn't resolved yet.
    if (key === lastCheckedKey && geofenceAbortController) {
        return;
    }

    // A request for a *different*, now-stale point is still in flight — cancel it.
    // Its response would land after this newer one and could overwrite fresher state.
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
        if (err.name === 'AbortError') return; // expected — a newer request superseded this one
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

// message is polymorphic: a plain string for some sources, or a nested
// { day, advisories: [{ text, status, boat_width }] } shape (seen from sva_advisory).
// Render whatever shape shows up without assuming one schema.
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
        // generic object fallback — show a compact key: value listing
        return Object.entries(message).map(([k, v]) => `${k}: ${formatHazardMessage(v)}`).join('<br>');
    }
    return String(message);
}

// A hit's identity for redraw purposes. fetchedAt changes when the underlying data
// for that source actually refreshes, so pairing it with source gives us a stable
// key that only changes when there's something new to draw.
function hazardKey(hit){
    return `${hit.source}::${hit.fetchedAt}`;
}

function popupHtmlFor(hit){
    return `<b>${hit.source}</b> [${hit.riskLevel}] — ${(hit.distanceMeters/1000).toFixed(1)}km<br>${formatHazardMessage(hit.message)}`;
}

// ---------------- SYSTEM ALERT (full-screen emergency popup) ----------------
// Fires instead of a chat bubble when the vessel enters a hazardous state.
// Deliberately modal — dismissed only via the acknowledge action or Escape,
// never by clicking outside, since this represents an active safety condition.
let systemAlertEscHandler = null;

function showSystemAlert(hazardousHits){
    const overlay = document.getElementById('systemAlertOverlay');
    const subtitle = document.getElementById('systemAlertSubtitle');
    const body = document.getElementById('systemAlertBody');
    if (!overlay || !subtitle || !body) {
        console.warn('System alert markup missing from index.html — falling back to chat.');
        const summary = hazardousHits
            .map(h => `<b>${h.source}</b> (${h.riskLevel}, ${(h.distanceMeters/1000).toFixed(1)}km): ${formatHazardMessage(h.message)}`)
            .join('<br>');
        addBotMessage(summary, 'HAZARD ALERT', true);
        return;
    }

    subtitle.textContent = hazardousHits.length > 1
        ? `${hazardousHits.length} HAZARDS DETECTED IN RANGE`
        : 'HAZARD DETECTED IN RANGE';

    body.innerHTML = hazardousHits.map(h => `
        <div class="sa-entry">
            <div class="sa-entry-row">
                <span class="sa-entry-source">${h.source}</span>
                <span class="sa-entry-dist">${(h.distanceMeters/1000).toFixed(1)} KM</span>
            </div>
            <div class="sa-entry-risk">RISK: ${(h.riskLevel || 'UNKNOWN').toUpperCase()}</div>
            <div class="sa-entry-msg">${formatHazardMessage(h.message)}</div>
        </div>
    `).join('');

    overlay.classList.remove('sa-clear'); // ensure red palette, in case a clear-alert left this set
    setSystemAlertChrome('⚠', 'SYSTEM WARNING');
    playSystemAlertEntrance(overlay);
    showHazardBadge();
}

// Blue "all clear" variant of the same popup — same panel, re-skinned via the
// .sa-clear modifier class rather than a second markup block, so both states
// stay visually consistent by construction.
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
    overlay.classList.remove('sa-flicker'); // restart the entrance animation if re-triggered
    void overlay.offsetWidth; // force reflow so the class removal/re-add actually replays
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
// Stays visible for the whole duration a hazard is in range, independent of
// whether the popup itself has been acknowledged/dismissed.
function showHazardBadge(){
    const badge = document.getElementById('hazardBadge');
    if (badge) badge.classList.add('active');
}
function hideHazardBadge(){
    const badge = document.getElementById('hazardBadge');
    if (badge) badge.classList.remove('active');
}

function renderGeofenceState(hits){
    hits = hits || [];

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
            return; // unchanged since last poll — leave the existing layer as-is
        }

        if (!hit.geometryJson) {
            return; // nothing to draw for this hit (shouldn't normally happen)
        }

        let geom;
        try {
            geom = JSON.parse(hit.geometryJson);
        } catch (e) {
            console.warn('Unparseable geometryJson for', hit.source, e);
            return;
        }

        const hazardous = isHazardous(hit);
        const layer = L.geoJSON(geom, {
            style: {
                color: hazardous ? '#E2673F' : '#7FA6A8',
                weight: 2,
                fillOpacity: hazardous ? 0.18 : 0.08
            }
        });
        layer.bindPopup(popupHtmlFor(hit));
        layer.addTo(map);
        drawnHazardLayers.set(key, layer);
    });

    // remove layers for hits that are no longer in range / no longer returned
    for (const [key, layer] of drawnHazardLayers) {
        if (!currentKeys.has(key)) {
            map.removeLayer(layer);
            drawnHazardLayers.delete(key);
        }
    }

    // ---- alert only on state CHANGE — enter/exit hazardous status ----
    // (not on every poll while lingering in the same state)
    const hazardousHits = hits.filter(isHazardous);
    const currentState = hazardousHits.length
        ? hazardousHits.map(h => h.source).sort().join(',')
        : null;

    if (currentState !== lastHazardState) {
        if (currentState) {
            showSystemAlert(hazardousHits); // full-screen emergency popup, not a chat bubble
        } else if (lastHazardState) {
            dismissSystemAlert();
            showClearAlert(); // blue "out of hazard zone" popup, same treatment as the warning
        }
        lastHazardState = currentState;
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

function addBotMessage(text, tag, warn){
    const wrap = document.createElement('div');
    wrap.className = 'msg bot';
    wrap.innerHTML = `
      <div class="bot-avatar">O</div>
      <div>
        <div class="bot-bubble">
          ${tag ? `<span class="tag${warn ? ' warn' : ''}">${tag}</span><br>` : ''}
          ${text}
        </div>
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
      <div><div class="bot-bubble loading">Thinking…</div></div>
    `;
    log.appendChild(wrap);
    log.scrollTop = log.scrollHeight;
    return wrap;
}

async function sendMessage(){
    const input = document.getElementById('textInput');
    const text = input.value.trim();
    if(!text) return;
    addUserMessage(text);
    input.value = '';

    const loadingEl = addLoadingMessage();

    try {
        const resp = await fetch(`${API_BASE}/api/v1/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: text, lat: currentLat, lon: currentLon, sessionId })
        });
        if (!resp.ok) throw new Error(`status ${resp.status}`);
        const data = await resp.json();
        setConnState(true);

        loadingEl.remove();
        addBotMessage(data.text, data.tag, data.warn);

        if (data.mapUpdate) {
            renderGeofenceState(data.mapUpdate); // expects the same flat hazard-hit array shape
        }
    } catch (err) {
        setConnState(false);
        loadingEl.remove();
        addBotMessage("Couldn't reach the ORCA backend — is LocalDevServer running on :8080? Falling back to a general answer.", 'OFFLINE', true);
        const r = generateResponse(text); // offline fallback so the demo still works without a server
        addBotMessage(r.text, r.tag, r.warn);
    }
}

// Offline fallback engine — used only if the backend is unreachable,
// so the UI remains testable even before LocalDevServer is running.
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

window.addEventListener('load', () => {
    showSystemAlert([
        {
            source: "DEBUG TEST",
            riskLevel: "HIGH",
            distanceMeters: 12500,
            message: "This is a test hazard alert."
        }
    ]);
});