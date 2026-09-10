// ---------------- BACKEND CONFIG ----------------
const API_BASE = 'http://localhost:8080'; // LocalDevServer — swap once a real host exists
const sessionId = crypto.randomUUID();

// ---------------- POSITION STATE ----------------

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
    if (!coordsReadout) return;
    const dot = document.getElementById('connDot');
    coordsReadout.innerHTML = `<span class="conn-dot ${dot && dot.classList.contains('online') ? 'online' : ''}" id="connDot"></span>${currentLat.toFixed(2)}°N · ${currentLon.toFixed(2)}°E`;
}

// ---------------- MAP SETUP ----------------
let map = null;
let mapInitialized = false;
let geofencePollHandle = null;


let searchRadiusLayer = null;


let drawnHazardLayers = new Map();

let lastHazardState = null;   // for enter/exit edge detection (no repeat auto-popups while lingering)
let lastHazardousHits = [];   // raw hazardous hits from the most recent check, so the
// badge click can redisplay them (with updated distances) on demand


function initMap(){
    if(mapInitialized) return;

    map = L.map('map', { zoomControl:true, doubleClickZoom:false, renderer: L.svg() }).setView([currentLat, currentLon], 7);


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
        requestGeofenceCheck(currentLat, currentLon, { immediate: true });
    });

    loadNearbyPfz();
    requestGeofenceCheck(currentLat, currentLon, { immediate: true });

    geofencePollHandle = null;

    mapInitialized = true;
}

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
            `${API_BASE}/api/v1/geofence?lat=${lat}&lon=${lon}`,
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

// Strips HTML tags so text can be safely sent to the Translator API or read
// aloud by TTS without literal tag text ("less than b greater than") leaking in.
function stripHtml(html){
    if (html == null) return '';
    return String(html).replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
}

function hazardKey(hit){
    return `${hit.source}::${hit.fetchedAt}`;
}

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

// ---------------- SYSTEM ALERT ----------------

let systemAlertEscHandler = null;

function formatDateShort(s){
    if (!s) return '—';
    const m = s.match(/(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/);
    return m ? `${m[1]} ${m[2]}` : s;
}

const hazardTypeMeta = {
    'PORT WARNING':           { icon: '⚓', label: 'Port Warning',      accent: '#ffaa00', bright: '#ffc866', dim: '#ffddaa' },
    'FISHERMEN WARNING':      { icon: '🎣', label: 'Fishermen Warning', accent: '#ff6600', bright: '#ff9955', dim: '#ffcc99' },
    'HIGH_WAVE_OR_WIND':      { icon: '🌊', label: 'High Wave / Wind',  accent: '#ff3333', bright: '#ff7777', dim: '#ffaaaa' },
    'LOW_VISIBILITY':         { icon: '🌫️', label: 'Low Visibility',    accent: '#cc66ff', bright: '#dd99ff', dim: '#eeccff' },
    'SVA ADVISORY':           { icon: '⛵', label: 'SVA Advisory',      accent: '#29c1ff', bright: '#7fe0ff', dim: '#a8e6ff' },
    'eez_boundary_proximity': { icon: '🌐', label: 'EEZ Boundary',      accent: '#7FA6A8', bright: '#a8c5c6', dim: '#c7dcdd' },
};

function entryHtmlFor(h, idx) {
    const entryId = `hazard-entry-${idx}`;
    const dist = formatDistanceKm(h);
    const meta = hazardTypeMeta[h.hazard_type];
    const style = meta
        ? ` style="--sa-accent:${meta.accent};--sa-accent-bright:${meta.bright};--sa-accent-text-dim:${meta.dim}"`
        : '';
    const typeLabel = meta ? meta.label : (h.hazard_type || 'UNKNOWN');
    const icon = meta ? meta.icon : '⚠';

    const isInside = h.distanceMeters === 0 || h.distanceMeters === 0.0;
    const distHtml = isInside
        ? `<span class="sa-entry-dist-inline sa-entry-dist-critical">INSIDE HAZARD AREA — LEAVE ASAP</span>`
        : (dist ? `<span class="sa-entry-dist-inline">${dist.toUpperCase()}</span>` : '');

    const msgHtml = h.hazard_type === 'SVA ADVISORY'
        ? renderSvaDayTabs(h.message, entryId)
        : `<div class="sa-entry-msg">${formatHazardMessage(h.message)}</div>`;

    return `
        <div class="sa-entry" id="${entryId}"${style}>
            <div class="sa-entry-type-row">
                <span class="sa-entry-type">${icon} ${typeLabel}</span>
                ${distHtml}
            </div>
            <div class="sa-entry-issued-by">
                <span class="sa-label">ISSUED BY</span>
                <span class="sa-value">${h.issued_by || '—'}</span>
            </div>
            <div class="sa-entry-meta">
                <span>Issued ${formatDateShort(h.issued_at)}</span>
                <span class="sa-entry-meta-sep">·</span>
                <span>Fetched ${formatDateShort(h.fetchedAt)}</span>
            </div>
            <div class="sa-entry-badge">RISK: ${(h.riskLevel || 'UNKNOWN').toUpperCase()}</div>
            ${msgHtml}
            <div class="sa-entry-footer">
                <a class="sa-entry-source" href="${h.source}" target="_blank" rel="noopener">${h.source}</a>
            </div>
        </div>
    `;
}
function formatSvaDate(dateStr) {
    if (!dateStr) return null;
    const d = new Date(dateStr + 'T00:00:00');
    if (isNaN(d)) return dateStr;
    return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
}

function renderSvaDayTabs(message, entryId) {
    if (!Array.isArray(message) || message.length === 0) {
        return `<div class="sa-entry-msg">${formatHazardMessage(message)}</div>`;
    }

    const tabsHtml = message.map((dayObj, idx) => {
        const dayLabel = formatSvaDate(dayObj.forecast_date) || `Day ${idx + 1}`;
        return `<button class="sva-day-tab${idx === 0 ? ' active' : ''}"
                    onclick="selectSvaDay('${entryId}', ${idx})"
                    data-day-idx="${idx}">${dayLabel}</button>`;
    }).join('');

    const panelsHtml = message.map((dayObj, idx) => {
        const advisories = Array.isArray(dayObj.advisories) ? dayObj.advisories : [];
        const linesHtml = advisories.map(a => {
            const width = a.boat_width ? ` <span class="sva-boat-width">(boats &lt;${a.boat_width}m)</span>` : '';
            const statusClass = a.status === 'safe' ? 'sva-status-safe' : 'sva-status-alert';
            return `<div class="sva-advisory-line ${statusClass}">${a.text || ''}${width}</div>`;
        }).join('') || '<div class="sva-advisory-line">No advisories for this day.</div>';
        return `<div class="sva-day-panel${idx === 0 ? ' active' : ''}" data-day-idx="${idx}">${linesHtml}</div>`;
    }).join('');

    return `<div class="sva-day-tabs">${tabsHtml}</div><div class="sva-day-panels">${panelsHtml}</div>`;
}

function selectSvaDay(entryId, dayIdx) {
    const entry = document.getElementById(entryId);
    if (!entry) return;
    entry.querySelectorAll('.sva-day-tab').forEach(el =>
        el.classList.toggle('active', el.dataset.dayIdx == dayIdx));
    entry.querySelectorAll('.sva-day-panel').forEach(el =>
        el.classList.toggle('active', el.dataset.dayIdx == dayIdx));
}

// Builds one plain-text-ish string from a hazardous-hits array, suitable for
// translation + TTS (kept separate from the HTML rendering used in the overlay).
function hazardousHitsToSpeechText(hazardousHits){
    return hazardousHits.map(h => {
        const meta = hazardTypeMeta[h.hazard_type];
        const label = meta ? meta.label : (h.hazard_type || 'Hazard');
        const dist = formatDistanceKm(h);
        const isInside = h.distanceMeters === 0 || h.distanceMeters === 0.0;
        const distPart = isInside ? 'You are inside the hazard area, leave immediately.' : (dist ? `${dist} away.` : '');
        const msg = stripHtml(formatHazardMessage(h.message));
        return `${label}. ${distPart} ${msg}`;
    }).join(' ');
}

function showSystemAlert(hazardousHits, { speak = true } = {}){
    const overlay = document.getElementById('systemAlertOverlay');
    const subtitle = document.getElementById('systemAlertSubtitle');
    const body = document.getElementById('systemAlertBody');
    if (!overlay || !subtitle || !body) {
        console.warn('System alert markup missing from index.html — falling back to chat.');
        const summary = hazardousHits
            .map(h => `<b>${h.source}</b> (${h.riskLevel}${formatDistanceKm(h) ? `, ${formatDistanceKm(h)}` : ''}): ${formatHazardMessage(h.message)}`)
            .join('<br>');
        addBotMessage(summary, 'HAZARD ALERT', true);
        speakInUserLanguage(stripHtml(summary));
        return;
    }

    subtitle.textContent = hazardousHits.length > 1
        ? `${hazardousHits.length} HAZARDS DETECTED IN RANGE`
        : 'HAZARD DETECTED IN RANGE';

    body.innerHTML = hazardousHits.map((h, idx) => entryHtmlFor(h, idx)).join('');

    overlay.classList.remove('sa-clear');
    setSystemAlertChrome('⚠', 'SYSTEM WARNING');
    playSystemAlertEntrance(overlay);
    showHazardBadge();

    // Speak the hazard summary aloud in the fisherman's chosen language —
    // safety alerts are the single most important thing to get spoken correctly.
    if (speak) {
        speakInUserLanguage(hazardousHitsToSpeechText(hazardousHits));
    }


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

function showHazardBadge(){
    const badge = document.getElementById('hazardBadge');
    if (badge) badge.classList.add('active');
}
function hideHazardBadge(){
    const badge = document.getElementById('hazardBadge');
    if (badge) badge.classList.remove('active');
}

async function onHazardBadgeClick(){
    await checkGeofence(currentLat, currentLon);

    if (lastHazardousHits.length) {
        showSystemAlert(lastHazardousHits); // updated distances baked in
    }

}

function renderGeofenceState(hits, { speak = true } = {}){
    hits = hits || [];

    const hazardousHits = hits.filter(isHazardous);
    lastHazardousHits = hazardousHits;

    const currentState = hazardousHits.length
        ? hazardousHits.map(h => h.source).sort().join(',')
        : null;

    if (currentState !== lastHazardState) {
        if (currentState) {
            showSystemAlert(hazardousHits, { speak });
        } else if (lastHazardState) {
            dismissSystemAlert();
            showClearAlert();
        }
        lastHazardState = currentState;
    }
    if (!map) {
        pendingMapHits = hits;
        return;
    }
    drawHazardsOnMap(hits);
}


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

    agentEventSource = new EventSource(`${API_BASE}/api/v1/events?sessionId=${sessionId}`);
    const textEl = loadingEl.querySelector('.loading-text');
    const timerEl = loadingEl.querySelector('.loading-timer');
    const bubbleEl = loadingEl.querySelector('.bot-bubble');

    let answerStarted = false;
    let streamedText = '';

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

        if (evt.type === 'token') {
            if (!answerStarted) {
                answerStarted = true;
                bubbleEl.classList.add('answer-streaming'); // drop the "thinking" pulse look, keep the timer running
                textEl.textContent = '';
            }
            streamedText += evt.text;
            textEl.textContent = streamedText;
        } else if (!answerStarted) {
            // pre-answer phase only — once tokens arrive, agent status stops
            // fighting with the answer text for the same element
            if (evt.type === 'agent_start') textEl.textContent = `⏳ ${evt.agent} working…`;
            if (evt.type === 'agent_end')   textEl.textContent = `✅ ${evt.agent} done — continuing…`;
            if (evt.type === 'agent_error') textEl.textContent = `⚠ ${evt.agent} hit an error…`;
        }

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
        speakInUserLanguage(stripHtml(data.text));   // translate (if needed) + speak the reply aloud

        if (data.mapUpdate) {
            try {
                renderGeofenceState(data.mapUpdate, { speak: false });
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

// ---------------- VOICE I/O (Azure Speech Translation + Translator + TTS) ----------------
const AZURE_SPEECH_KEY = window.ORCA_CONFIG.AZURE_SPEECH_KEY;
const AZURE_SPEECH_REGION = window.ORCA_CONFIG.AZURE_SPEECH_REGION;
const AZURE_TRANSLATOR_KEY = window.ORCA_CONFIG.AZURE_TRANSLATOR_KEY;
const AZURE_TRANSLATOR_REGION = window.ORCA_CONFIG.AZURE_TRANSLATOR_REGION;

// Matches your SVAS 10-language set (English, Hindi, Tamil, Telugu, Malayalam,
// Kannada, Bengali, Gujarati, Marathi, Odia). Extend/adjust voice names as needed.
const LANGUAGE_MAP = {
    'en': { label: 'English',   recog: 'en-IN', voice: 'en-IN-NeerjaNeural',    translatorCode: 'en' },
    'hi': { label: 'हिन्दी',      recog: 'hi-IN', voice: 'hi-IN-SwaraNeural',     translatorCode: 'hi' },
    'ta': { label: 'தமிழ்',      recog: 'ta-IN', voice: 'ta-IN-PallaviNeural',   translatorCode: 'ta' },
    'te': { label: 'తెలుగు',     recog: 'te-IN', voice: 'te-IN-ShrutiNeural',    translatorCode: 'te' },
    'ml': { label: 'മലയാളം',    recog: 'ml-IN', voice: 'ml-IN-SobhanaNeural',   translatorCode: 'ml' },
    'kn': { label: 'ಕನ್ನಡ',      recog: 'kn-IN', voice: 'kn-IN-SapnaNeural',     translatorCode: 'kn' },
    'bn': { label: 'বাংলা',      recog: 'bn-IN', voice: 'bn-IN-TanishaaNeural',  translatorCode: 'bn' },
    'gu': { label: 'ગુજરાતી',    recog: 'gu-IN', voice: 'gu-IN-DhwaniNeural',    translatorCode: 'gu' },
    'mr': { label: 'मराठी',      recog: 'mr-IN', voice: 'mr-IN-AarohiNeural',    translatorCode: 'mr' },
    'or': { label: 'ଓଡ଼ିଆ',      recog: 'or-IN', voice: 'or-IN-SubhasiniNeural', translatorCode: 'or' },
};

const LANGUAGE_STORAGE_KEY = 'orca_language';
let currentLanguage = localStorage.getItem(LANGUAGE_STORAGE_KEY) || 'en';

let listening = false;
const micBtn = document.getElementById('micBtn');
const listeningHint = document.getElementById('listeningHint');
const langSelect = document.getElementById('langSelect');

// Populates the <select id="langSelect"> dropdown (see index.html snippet below)
// and wires it to update + persist currentLanguage.
function initLanguageDropdown(){
    if (!langSelect) {
        console.warn('langSelect element not found — add <select id="langSelect"></select> to index.html');
        return;
    }
    langSelect.innerHTML = Object.entries(LANGUAGE_MAP)
        .map(([code, lang]) => `<option value="${code}">${lang.label}</option>`)
        .join('');
    langSelect.value = currentLanguage;

    langSelect.addEventListener('change', () => {
        currentLanguage = langSelect.value;
        localStorage.setItem(LANGUAGE_STORAGE_KEY, currentLanguage);
    });
}
initLanguageDropdown();

// Domain-specific terms and place names to bias speech recognition toward —
// extend this list as you discover more mishearings in the field.
const ASR_PHRASE_LIST = [
    // Fishing / marine domain
    'तट से नज़दीकी', 'संभावित मछली पकड़ने की जगह', 'मछली पकड़ने का क्षेत्र',
    'सुरक्षित मछली पकड़ने का क्षेत्र', 'समुद्री सीमा', 'चक्रवात', 'तूफान',
    'लहर की ऊंचाई', 'हवा की गति', 'ज्वार भाटा', 'तटरक्षक चेतावनी',
    'मछुआरा चेतावनी', 'बंदरगाह चेतावनी',
    // Coastal states / cities likely to come up
    'उड़ीसा', 'ओडिशा', 'तमिलनाडु', 'केरल', 'आंध्र प्रदेश', 'गुजरात',
    'महाराष्ट्र', 'पश्चिम बंगाल', 'कर्नाटक', 'तूतीकोरिन', 'कोलकाता',
    'हल्दिया', 'सागर द्वीप', 'चेन्नई', 'कोच्चि', 'विशाखापत्तनम'
];

function toggleListening(){
    if (listening) return; // Azure's recognizeOnceAsync is single-shot; ignore repeat clicks mid-listen

    const lang = LANGUAGE_MAP[currentLanguage];

    const speechConfig = SpeechSDK.SpeechConfig.fromSubscription(AZURE_SPEECH_KEY, AZURE_SPEECH_REGION);
    speechConfig.speechRecognitionLanguage = lang.recog;

    const audioConfig = SpeechSDK.AudioConfig.fromDefaultMicrophoneInput();
    const recognizer = new SpeechSDK.SpeechRecognizer(speechConfig, audioConfig);

    // Bias recognition toward domain terms and place names. Hindi-specific for
    // now — add per-language lists later if other languages need the same boost.
    if (currentLanguage === 'hi') {
        const phraseList = SpeechSDK.PhraseListGrammar.fromRecognizer(recognizer);
        ASR_PHRASE_LIST.forEach(phrase => phraseList.addPhrase(phrase));
    }

    listening = true;
    micBtn.classList.add('listening');
    listeningHint.textContent = 'Listening…';

    recognizer.recognizeOnceAsync(
        async (result) => {
            listening = false;
            micBtn.classList.remove('listening');
            listeningHint.textContent = '';
            recognizer.close();

            if (result.reason === SpeechSDK.ResultReason.RecognizedSpeech) {
                const originalText = result.text;
                let englishText = originalText;
                try {
                    englishText = (currentLanguage === 'en')
                        ? originalText
                        : await translateText(originalText, 'en');
                } catch (err) {
                    console.error('Input translation failed, sending original text:', err);
                }
                console.log(`[toggleListening] lang=${currentLanguage} | original="${originalText}" | translatedToEnglish="${englishText}"`);
                document.getElementById('textInput').value = englishText;
                sendMessage();
            } else {
                listeningHint.textContent = 'Could not hear that — try again';
                setTimeout(() => listeningHint.textContent = '', 2000);
            }
        },
        (err) => {
            listening = false;
            micBtn.classList.remove('listening');
            listeningHint.textContent = 'Could not hear that — try again';
            setTimeout(() => listeningHint.textContent = '', 2000);
            console.error(err);
            recognizer.close();
        }
    );
}

async function translateText(text, targetLangCode){
    const endpoint = "https://api.cognitive.microsofttranslator.com";
    const resp = await fetch(`${endpoint}/translate?api-version=3.0&to=${targetLangCode}`, {
        method: 'POST',
        headers: {
            'Ocp-Apim-Subscription-Key': AZURE_TRANSLATOR_KEY,
            'Ocp-Apim-Subscription-Region': AZURE_TRANSLATOR_REGION,
            'Content-Type': 'application/json'
        },
        body: JSON.stringify([{ text }])

    });
    console.log(text)
    const data = await resp.json();
    console.log(data[0].translations[0].text)
    return data[0].translations[0].text;
}

// Translates (if needed) English text into the current UI language, then speaks it.
async function speakInUserLanguage(englishText){
    if (!englishText) return;
    const lang = LANGUAGE_MAP[currentLanguage];

    let translated = englishText;
    try {
        translated = (currentLanguage === 'en')
            ? englishText
            : await translateText(englishText, lang.translatorCode);
    } catch (err) {
        console.error('Translation failed, falling back to English speech:', err);
    }
    console.log(`[speakInUserLanguage] lang=${currentLanguage} | original="${englishText}" | translated="${translated}"`);

    const speechConfig = SpeechSDK.SpeechConfig.fromSubscription(AZURE_SPEECH_KEY, AZURE_SPEECH_REGION);
    speechConfig.speechSynthesisVoiceName = (currentLanguage === 'en')
        ? LANGUAGE_MAP.en.voice
        : lang.voice;

    const synthesizer = new SpeechSDK.SpeechSynthesizer(speechConfig);
    synthesizer.speakTextAsync(
        translated,
        () => synthesizer.close(),
        (err) => { console.error(err); synthesizer.close(); }
    );
}

function formatElapsed(ms){
    const totalSeconds = ms / 1000;
    if (totalSeconds < 60) return `${totalSeconds.toFixed(1)}s`;
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = Math.round(totalSeconds % 60);
    return `${minutes}m ${seconds}s`;
}




// styling
