// ---------------- MAP SETUP ----------------
let map = null;
let mapInitialized = false;

function initMap(){
    if(mapInitialized) return;
    map = L.map('map', { zoomControl:true }).setView([9.9, 78.5], 7);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap contributors',
        maxZoom: 12
    }).addTo(map);

    // PFZ advisory points (sample)
    const pfzPoints = [
        { lat: 9.28, lon: 79.31, name: "Valiyapani reef", note: "PFZ · 12–17km offshore, SW" },
        { lat: 9.98, lon: 79.83, name: "Near Tuticorin", note: "PFZ advisory active" },
        { lat: 8.90, lon: 76.60, name: "Kollam coast", note: "PFZ · favourable SST" }
    ];
    pfzPoints.forEach(p => {
        const marker = L.circleMarker([p.lat, p.lon], {
            radius: 7, color:'#2FBE9C', fillColor:'#1E8A72', fillOpacity:0.9, weight:2
        }).addTo(map);
        marker.bindPopup(`<b>${p.name}</b><br>${p.note}`);
    });

    // Hazard zone (sample)
    L.circle([10.4, 79.9], {
        radius: 30000, color:'#E2673F', fillColor:'#E2673F', fillOpacity:0.15, weight:1.5, dashArray:'4,4'
    }).addTo(map).bindPopup("Rough sea advisory — wave height 2.1m");

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
}

// Simple canned-response engine for demo purposes
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

function sendMessage(){
    const input = document.getElementById('textInput');
    const text = input.value.trim();
    if(!text) return;
    addUserMessage(text);
    input.value = '';

    setTimeout(() => {
        const r = generateResponse(text);
        addBotMessage(r.text, r.tag, r.warn);
    }, 550);
}

// Seed the conversation
addBotMessage("Welcome aboard. Ask me about sea safety, tides, weather, or where to fish along the coast — in text or by voice.", null, false);

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