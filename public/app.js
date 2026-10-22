const REFRESH_INTERVAL_SEC = 20;

const savedFavs = (() => {
  try {
    return JSON.parse(localStorage.getItem('stiham_fav_lines') || '[]');
  } catch {
    return [];
  }
})();

const state = {
  mode: 'multi', // 'multi' (chytrý uzel v okruhu) | 'single' (1 zastávka)
  cisNumber: null,
  lat: 49.7463,
  lon: 13.3632,
  prevLat: 49.7448,
  prevLon: 13.3632,
  simulatedDistanceMeters: 40,
  useRealGpsDistance: false,
  preferredTraction: 'all', // 'all' | 'tram' | 'trolleybus' | 'bus'
  favoriteLines: new Set(savedFavs),
  activeLineFilter: null,
  lastData: null,
  refreshElapsedSec: 0,
  expandedRowIndex: null,
};

// DOM elementy
const els = {
  refreshProgressBar: document.getElementById('refreshProgressBar'),
  lastUpdatedText: document.getElementById('lastUpdatedText'),
  refreshBtn: document.getElementById('refreshBtn'),
  useGpsBtn: document.getElementById('useGpsBtn'),
  modeButtons: document.getElementById('modeButtons'),
  tractionButtons: document.getElementById('tractionButtons'),
  stopSearchInput: document.getElementById('stopSearchInput'),
  searchResultsDropdown: document.getElementById('searchResultsDropdown'),
  presetButtons: document.getElementById('presetButtons'),
  distanceSlider: document.getElementById('distanceSlider'),
  distanceValueLabel: document.getElementById('distanceValueLabel'),
  nearbyStopsContainer: document.getElementById('nearbyStopsContainer'),
  nearbyStopsList: document.getElementById('nearbyStopsList'),
  lockscreenClock: document.getElementById('lockscreenClock'),
  lockscreenDate: document.getElementById('lockscreenDate'),
  notifDistanceMeta: document.getElementById('notifDistanceMeta'),
  notifStopName: document.getElementById('notifStopName'),
  notifCatchBadge: document.getElementById('notifCatchBadge'),
  notifDeparturesList: document.getElementById('notifDeparturesList'),
  notifToggleTractionAction: document.getElementById('notifToggleTractionAction'),
  notifRefreshAction: document.getElementById('notifRefreshAction'),
  notifNextStopAction: document.getElementById('notifNextStopAction'),
  boardEyebrow: document.getElementById('boardEyebrow'),
  boardHeading: document.getElementById('boardHeading'),
  activeStopMeta: document.getElementById('activeStopMeta'),
  lineFiltersWrapper: document.getElementById('lineFiltersWrapper'),
  lineFilterPills: document.getElementById('lineFilterPills'),
  errorBanner: document.getElementById('errorBanner'),
  departuresContainer: document.getElementById('departuresContainer'),
};

function saveFavorites() {
  try {
    localStorage.setItem('stiham_fav_lines', JSON.stringify(Array.from(state.favoriteLines)));
  } catch {
    // ignore storage errors
  }
}

function evaluateCatchabilityClient(distanceMeters, leavingInMin) {
  if (leavingInMin < 0) {
    return {
      status: 'missed',
      label: 'Odjelo',
      walkMinutes: distanceMeters ? Math.max(1, Math.round(distanceMeters / 80)) : 0,
    };
  }

  if (distanceMeters != null && Number.isFinite(distanceMeters) && distanceMeters <= 45) {
    return { status: 'comfortable', label: 'U zastávky', walkMinutes: 0 };
  }

  if (distanceMeters == null || !Number.isFinite(distanceMeters)) {
    if (leavingInMin <= 0) return { status: 'now', label: 'Odjíždí teď' };
    if (leavingInMin <= 2) return { status: 'hurry', label: 'U zastávky' };
    return { status: 'comfortable', label: 'V klidu' };
  }

  const normalWalkMin = distanceMeters / 80;
  const fastWalkMin = distanceMeters / 135;

  if (leavingInMin < fastWalkMin - 0.4) {
    return {
      status: 'missed',
      label: 'Nestíháš',
      walkMinutes: Math.ceil(normalWalkMin),
    };
  }
  if (leavingInMin < normalWalkMin + 0.5) {
    return {
      status: 'hurry',
      label: distanceMeters > 60 ? '🏃 Poklusem!' : '⚡ Pospěš si!',
      walkMinutes: Math.ceil(normalWalkMin),
    };
  }
  return {
    status: 'comfortable',
    label: '✓ Stíháš v klidu',
    walkMinutes: Math.max(1, Math.round(normalWalkMin)),
  };
}

function getEffectiveStopDistance(rawStopDist) {
  const baseNearest = state.lastData?.stop?.distanceMeters ?? 40;
  if (state.useRealGpsDistance && rawStopDist != null) {
    return rawStopDist;
  }
  const diffFromPrimary = rawStopDist != null ? Math.max(0, rawStopDist - baseNearest) : 0;
  return state.simulatedDistanceMeters + diffFromPrimary;
}

function formatWalkText(distMeters) {
  const walkMin = Math.max(1, Math.round(distMeters / 80));
  return `${distMeters} m (~${walkMin} min chůze)`;
}

function updateLockscreenClock() {
  const now = new Date();
  els.lockscreenClock.textContent = now.toLocaleTimeString('cs-CZ', {
    hour: '2-digit',
    minute: '2-digit',
  });
  const dateFormatted = now.toLocaleDateString('cs-CZ', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  els.lockscreenDate.textContent =
    dateFormatted.charAt(0).toUpperCase() + dateFormatted.slice(1);
}

async function fetchDepartures() {
  try {
    els.errorBanner.hidden = true;
    els.lastUpdatedText.textContent = 'Aktualizuji…';

    const params = new URLSearchParams();
    if (state.cisNumber) {
      params.set('cis', String(state.cisNumber));
    } else if (state.mode === 'multi') {
      params.set('multi', '1');
      params.set('radius', '250');
    }

    if (state.lat != null && state.lon != null) {
      params.set('lat', String(state.lat));
      params.set('lon', String(state.lon));
    }
    if (state.prevLat != null && state.prevLon != null) {
      params.set('prevLat', String(state.prevLat));
      params.set('prevLon', String(state.prevLon));
    }
    params.set('limit', '20');

    const res = await fetch(`/api/departures?${params.toString()}`);
    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.error || `HTTP ${res.status}`);
    }

    state.lastData = data;
    state.refreshElapsedSec = 0;

    if (state.useRealGpsDistance && data.stop?.distanceMeters != null) {
      state.simulatedDistanceMeters = Math.min(450, Math.max(10, data.stop.distanceMeters));
      els.distanceSlider.value = String(state.simulatedDistanceMeters);
    }

    renderAll();

    const fetchedTime = new Date(data.fetchedAt).toLocaleTimeString('cs-CZ', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    els.lastUpdatedText.textContent = `Živě ${fetchedTime}`;
  } catch (err) {
    els.errorBanner.textContent = `Chyba načítání dat PMDP: ${err.message}`;
    els.errorBanner.hidden = false;
    els.lastUpdatedText.textContent = 'Chyba spojení';
  }
}

function getLineBadgeClass(dep) {
  if (dep.isNight) return 'line-badge night';
  if (dep.vehicleType === 'tram') return 'line-badge tram';
  if (dep.vehicleType === 'trolleybus') return 'line-badge trolleybus';
  return 'line-badge bus';
}

function formatCountdownText(leavingInMin) {
  if (leavingInMin < 0) return 'Odjelo';
  if (leavingInMin === 0) return 'Teď';
  return `${leavingInMin} min`;
}

function formatDelayBadge(dep) {
  if (dep.delayMin === null || dep.delayMin === undefined) {
    return `<span class="delay-tag not-started" title="Spoj ještě nevyjel z výchozí zastávky">dle JŘ</span>`;
  }
  if (dep.delayMin === 0) {
    return `<span class="delay-tag on-time">včas</span>`;
  }
  return `<span class="delay-tag delayed">+${dep.delayMin} min zpoždění</span>`;
}

function movementLabel(status) {
  if (status === 'approaching') return '🚶 přibližuješ se';
  if (status === 'leaving') return '↩ odcházíš';
  return '';
}

function renderAll() {
  const data = state.lastData;
  if (!data) return;

  const primaryDist = getEffectiveStopDistance(data.stop?.distanceMeters);
  els.distanceValueLabel.textContent = formatWalkText(primaryDist);

  const isMulti = data.mode === 'multi' && Array.isArray(data.clusterStops) && data.clusterStops.length > 1;
  const clusterNames = (data.clusterStops || []).map((s) => s.name).join(' + ');

  // 1. Hlavní hlavička zastávky / uzlu
  if (isMulti) {
    els.boardEyebrow.textContent = `🧠 Chytrý uzel (${data.clusterStops.length} zastávky v dosahu chůze)`;
    els.boardHeading.textContent = clusterNames;
    els.activeStopMeta.textContent = data.clusterStops
      .map((s) => {
        const d = getEffectiveStopDistance(s.distanceMeters);
        const mov = movementLabel(s.movementStatus);
        return `📍 ${s.name}: ${d} m${mov ? ` (${mov})` : ''}`;
      })
      .join('  •  ');
  } else {
    const stop = data.stop;
    els.boardEyebrow.textContent = '🎯 Jedna aktivní zastávka PMDP';
    els.boardHeading.textContent = stop.name;
    const barrierText = stop.isBarrierFree ? '♿ Bezbariérová' : '';
    els.activeStopMeta.textContent = [
      `Vzdálenost k označníku: ${formatWalkText(primaryDist)}`,
      `CIS: ${stop.cisNumber || '–'}`,
      barrierText,
    ]
      .filter(Boolean)
      .join(' • ');
  }

  // 2. Zastávky v okolí (možnost připnout jen jednu nebo vrátit Chytrý uzel)
  if (Array.isArray(data.nearbyStops) && data.nearbyStops.length > 1) {
    els.nearbyStopsContainer.hidden = false;
    const allChip = `<button type="button" class="chip ${!state.cisNumber && state.mode === 'multi' ? 'active' : ''}" data-cis="all">
      🧠 Vše v okolí (${Math.min(3, data.nearbyStops.length)})
    </button>`;
    const stopChips = data.nearbyStops
      .slice(0, 5)
      .map((st) => {
        const isCurrent = state.cisNumber === st.cisNumber || (!isMulti && st.cisNumber === data.stop.cisNumber);
        const movIcon = st.movementStatus === 'approaching' ? '🚶' : st.movementStatus === 'leaving' ? '↩' : '';
        return `<button type="button" class="chip ${isCurrent ? 'active' : ''}" data-cis="${st.cisNumber}">
          ${movIcon} ${st.name} <small>(${getEffectiveStopDistance(st.distanceMeters)} m)</small>
        </button>`;
      })
      .join('');
    els.nearbyStopsList.innerHTML = allChip + stopChips;
  } else {
    els.nearbyStopsContainer.hidden = true;
  }

  // Aktualizace tlačítka trakce v Android notifikaci
  els.notifToggleTractionAction.textContent =
    state.preferredTraction === 'tram' ? '🚌 Zobrazit vše (nejen tramvaje)' : '🚋 Jen tramvaje';

  // 3. Filtry linek
  const uniqueLines = [];
  const seen = new Set();
  for (const d of data.departures) {
    if (!seen.has(d.lineName)) {
      seen.add(d.lineName);
      uniqueLines.push({ name: d.lineName, icon: d.vehicleIcon, vehicleType: d.vehicleType });
    }
  }

  if (uniqueLines.length > 1) {
    els.lineFiltersWrapper.hidden = false;
    els.lineFilterPills.innerHTML = [
      `<button type="button" class="chip ${state.activeLineFilter === null ? 'active' : ''}" data-line="">Vše (${data.departures.length})</button>`,
      ...uniqueLines.map((l) => {
        const isFav = state.favoriteLines.has(l.name);
        return `<button type="button" class="chip ${state.activeLineFilter === l.name ? 'active' : ''}" data-line="${l.name}">
          ${isFav ? '⭐ ' : ''}${l.icon} ${l.name}
        </button>`;
      }),
    ].join('');
  } else {
    els.lineFiltersWrapper.hidden = true;
  }

  // Filtrování podle trakce a konkrétní linky
  let filteredDepartures = data.departures;
  if (state.preferredTraction !== 'all') {
    filteredDepartures = filteredDepartures.filter((d) => d.vehicleType === state.preferredTraction);
  }
  if (state.activeLineFilter) {
    filteredDepartures = filteredDepartures.filter((d) => d.lineName === state.activeLineFilter);
  }

  // Přepočet "Stíhám to?" pro každý spoj podle vzdálenosti JEHO vlastní zastávky!
  const enrichedDepartures = filteredDepartures.map((d) => {
    const effDist = getEffectiveStopDistance(d.stopDistanceMeters);
    return {
      ...d,
      effectiveDistanceMeters: effDist,
      isFavorite: state.favoriteLines.has(d.lineName),
      catchability: evaluateCatchabilityClient(effDist, d.leavingInMin),
    };
  });

  // Pro Live notifikaci upřednostníme:
  // 1) Oblíbené linky (⭐), které se dají stihnout
  // 2) Spoje ze zastávek, ke kterým se přibližujeme (🚶 approaching)
  // 3) Ostatní stihnutelné spoje podle času odjezdu
  const notifSorted = [...enrichedDepartures].sort((a, b) => {
    const aCatch = a.catchability.status !== 'missed' ? 1 : 0;
    const bCatch = b.catchability.status !== 'missed' ? 1 : 0;
    if (aCatch !== bCatch) return bCatch - aCatch;
    if (a.isFavorite !== b.isFavorite) return a.isFavorite ? -1 : 1;
    return a.leavingInMin - b.leavingInMin;
  });

  // 4. Vykreslení Android Lockscreen Live Notifikace
  els.notifStopName.textContent = isMulti ? `Okolí: ${clusterNames}` : data.stop.name;
  els.notifDistanceMeta.textContent = isMulti
    ? `${data.clusterStops.length} zast. do 250 m`
    : `${primaryDist} m (~${Math.max(1, Math.round(primaryDist / 80))} min)`;

  const firstCatchable = notifSorted[0];
  if (firstCatchable) {
    els.notifCatchBadge.className = `catch-pill ${firstCatchable.catchability.status}`;
    els.notifCatchBadge.textContent = `${firstCatchable.vehicleIcon} ${firstCatchable.lineName}: ${firstCatchable.catchability.label}`;
  } else {
    els.notifCatchBadge.className = 'catch-pill comfortable';
    els.notifCatchBadge.textContent = 'Žádné odjezdy';
  }

  const topNotifDeps = notifSorted.slice(0, 4);
  if (topNotifDeps.length === 0) {
    els.notifDeparturesList.innerHTML = `<div class="notif-dep-row">Žádné nejbližší spoje zvoleného filtru</div>`;
  } else {
    els.notifDeparturesList.innerHTML = topNotifDeps
      .map((d) => {
        const delayShort =
          d.delayMin === null ? '' : d.delayMin === 0 ? ' (včas)' : ` (+${d.delayMin}m)`;
        const stopShort = isMulti ? ` • 📍 ${d.stopName} (${d.effectiveDistanceMeters}m)` : '';
        return `<div class="notif-dep-row">
          <span class="${getLineBadgeClass(d)}" style="min-inline-size:2.4rem;font-size:0.78rem;padding:0.15rem 0.4rem;">
            ${d.isFavorite ? '⭐' : d.vehicleIcon} ${d.lineName}
          </span>
          <span class="notif-dep-dest">→ ${d.destination}<small style="color:#7dd3fc;">${stopShort}</small></span>
          <span class="notif-dep-time">${formatCountdownText(d.leavingInMin)}<small style="font-weight:500;color:#94a3b8;">${delayShort}</small></span>
        </div>`;
      })
      .join('');
  }

  // 5. Vykreslení hlavní tabule odjezdů
  if (enrichedDepartures.length === 0) {
    els.departuresContainer.innerHTML = `<div class="panel-card" style="text-align:center;color:var(--text-secondary);">
      V nejbližší době neodjíždí žádný spoj odpovídající zvolenému filtru.
    </div>`;
    return;
  }

  els.departuresContainer.innerHTML = enrichedDepartures
    .map((d, idx) => {
      const isExpanded = state.expandedRowIndex === idx;
      const onlineTag = d.inTraffic
        ? `<span title="Vozidlo právě vysílá online polohu">📡 Online</span>`
        : `<span>🕒 JŘ</span>`;

      const stopOriginHtml = `<span class="stop-origin-badge" title="Vzdálenost k označníku této zastávky">
        📍 ${d.stopName} (${d.effectiveDistanceMeters} m${d.stopMovementStatus === 'approaching' ? ' • 🚶 blížíš se' : ''})
      </span>`;

      const timeComparison =
        d.delayMin && d.delayMin > 0
          ? `<s style="opacity:0.6">${d.scheduledHHMM}</s> → <strong>${d.estimatedHHMM}</strong>`
          : d.scheduledHHMM;

      const stopsDrawerHtml =
        isExpanded && d.nextStops && d.nextStops.length > 0
          ? `<div class="dep-stops-drawer">
              <div><strong>Následující zastávky na trase spoje:</strong></div>
              <div class="route-stops-chain">
                ${d.nextStops
                  .map(
                    (st) =>
                      `<span class="route-stop-chip ${st.highlight ? 'current' : ''}">${st.name} ${st.time ? `(${st.time})` : ''}</span>`
                  )
                  .join('')}
              </div>
            </div>`
          : '';

      return `<article class="departure-card catch-${d.catchability.status}" data-idx="${idx}">
        <div class="dep-main-row">
          <div style="display:flex;align-items:center;gap:0.35rem;">
            <button type="button" class="fav-star-btn ${d.isFavorite ? 'is-fav' : ''}" data-fav-line="${d.lineName}" title="Připnout linku ${d.lineName} mezi oblíbené (zobrazí se v notifikaci nahoře)">
              ★
            </button>
            <div class="${getLineBadgeClass(d)}">
              <span>${d.vehicleIcon}</span>
              <span>${d.lineName}</span>
            </div>
          </div>

          <div class="dep-info">
            <div class="dep-destination">→ ${d.destination}</div>
            <div class="dep-submeta">
              ${stopOriginHtml}
              ${formatDelayBadge(d)}
              <span>•</span>
              ${onlineTag}
              ${d.onRequest ? '<span>• ✋ Na znamení</span>' : ''}
            </div>
          </div>

          <div class="catch-pill ${d.catchability.status}">
            ${d.catchability.label}
          </div>

          <div class="dep-countdown">
            <div class="countdown-minutes">${formatCountdownText(d.leavingInMin)}</div>
            <div class="countdown-clock">${timeComparison}</div>
          </div>
        </div>
        ${stopsDrawerHtml}
      </article>`;
    })
    .join('');
}

// Přepínač režimu (Chytrý uzel vs. 1 nejbližší zastávka)
els.modeButtons.addEventListener('click', (e) => {
  const chip = e.target.closest('.chip[data-mode]');
  if (!chip) return;
  for (const b of els.modeButtons.querySelectorAll('.chip')) b.classList.remove('active');
  chip.classList.add('active');

  state.mode = chip.dataset.mode;
  if (state.mode === 'multi') {
    state.cisNumber = null;
  }
  fetchDepartures();
});

// Přepínač preference trakce (Vše / Tramvaj / Trolejbus / Autobus)
function setPreferredTraction(traction) {
  state.preferredTraction = traction;
  for (const b of els.tractionButtons.querySelectorAll('.chip')) {
    b.classList.toggle('active', b.dataset.traction === traction);
  }
  renderAll();
}

els.tractionButtons.addEventListener('click', (e) => {
  const chip = e.target.closest('.chip[data-traction]');
  if (!chip) return;
  setPreferredTraction(chip.dataset.traction);
});

// Tlačítko v Android notifikaci: rychlý filtr "Jen tramvaje" <-> "Vše"
els.notifToggleTractionAction.addEventListener('click', () => {
  const next = state.preferredTraction === 'tram' ? 'all' : 'tram';
  setPreferredTraction(next);
});

// Vyhledávání zastávek (autocomplete)
let searchDebounce = null;
els.stopSearchInput.addEventListener('input', (e) => {
  const q = e.target.value.trim();
  clearTimeout(searchDebounce);
  if (q.length < 1) {
    els.searchResultsDropdown.hidden = true;
    return;
  }

  searchDebounce = setTimeout(async () => {
    try {
      const res = await fetch(`/api/stops?q=${encodeURIComponent(q)}&limit=8`);
      const data = await res.json();
      if (!data.stops || data.stops.length === 0) {
        els.searchResultsDropdown.innerHTML = `<div class="search-item">Žádná zastávka nenalezena</div>`;
        els.searchResultsDropdown.hidden = false;
        return;
      }

      els.searchResultsDropdown.innerHTML = data.stops
        .map((st) => {
          const linesPreview = st.lines
            .slice(0, 6)
            .map((l) => `${l.icon}${l.name}`)
            .join(' ');
          return `<button type="button" class="search-item" data-cis="${st.cisNumber}" data-lat="${st.latitude}" data-lon="${st.longitude}" data-name="${st.name}">
            <strong>${st.name}</strong>
            <small style="color:var(--text-secondary)">${linesPreview}</small>
          </button>`;
        })
        .join('');
      els.searchResultsDropdown.hidden = false;
    } catch {
      els.searchResultsDropdown.hidden = true;
    }
  }, 180);
});

els.searchResultsDropdown.addEventListener('click', (e) => {
  const btn = e.target.closest('.search-item[data-cis]');
  if (!btn) return;

  state.cisNumber = Number(btn.dataset.cis);
  state.lat = Number(btn.dataset.lat);
  state.lon = Number(btn.dataset.lon);
  state.prevLat = null;
  state.prevLon = null;
  state.activeLineFilter = null;
  state.useRealGpsDistance = false;
  els.stopSearchInput.value = btn.dataset.name || '';
  els.searchResultsDropdown.hidden = true;
  fetchDepartures();
});

document.addEventListener('click', (e) => {
  if (!e.target.closest('.search-box')) {
    els.searchResultsDropdown.hidden = true;
  }
});

// Kliknutí na modelové situace v Plzni
els.presetButtons.addEventListener('click', (e) => {
  const chip = e.target.closest('.chip[data-lat]');
  if (!chip) return;

  for (const b of els.presetButtons.querySelectorAll('.chip')) {
    b.classList.remove('active');
  }
  chip.classList.add('active');

  state.cisNumber = null;
  state.lat = Number(chip.dataset.lat);
  state.lon = Number(chip.dataset.lon);
  state.prevLat = chip.dataset.prevLat ? Number(chip.dataset.prevLat) : null;
  state.prevLon = chip.dataset.prevLon ? Number(chip.dataset.prevLon) : null;
  state.simulatedDistanceMeters = Number(chip.dataset.dist || 60);
  state.useRealGpsDistance = false;
  state.activeLineFilter = null;
  els.distanceSlider.value = String(state.simulatedDistanceMeters);
  fetchDepartures();
});

// Kliknutí na okolní zastávky
els.nearbyStopsList.addEventListener('click', (e) => {
  const chip = e.target.closest('.chip[data-cis]');
  if (!chip) return;
  if (chip.dataset.cis === 'all') {
    state.cisNumber = null;
    state.mode = 'multi';
    for (const b of els.modeButtons.querySelectorAll('.chip')) {
      b.classList.toggle('active', b.dataset.mode === 'multi');
    }
  } else {
    state.cisNumber = Number(chip.dataset.cis);
  }
  state.activeLineFilter = null;
  fetchDepartures();
});

// Kliknutí na filtr linek
els.lineFilterPills.addEventListener('click', (e) => {
  const chip = e.target.closest('.chip[data-line]');
  if (!chip) return;
  state.activeLineFilter = chip.dataset.line || null;
  renderAll();
});

// Kliknutí na hvězdičku oblíbené linky nebo rozbalení trasy spoje
els.departuresContainer.addEventListener('click', (e) => {
  const favBtn = e.target.closest('.fav-star-btn[data-fav-line]');
  if (favBtn) {
    e.stopPropagation();
    const line = favBtn.dataset.favLine;
    if (state.favoriteLines.has(line)) {
      state.favoriteLines.delete(line);
    } else {
      state.favoriteLines.add(line);
    }
    saveFavorites();
    renderAll();
    return;
  }

  const card = e.target.closest('.departure-card[data-idx]');
  if (!card) return;
  const idx = Number(card.dataset.idx);
  state.expandedRowIndex = state.expandedRowIndex === idx ? null : idx;
  renderAll();
});

// Posuvník vzdálenosti k zastávce (Geofence simulátor)
els.distanceSlider.addEventListener('input', (e) => {
  state.simulatedDistanceMeters = Number(e.target.value);
  state.useRealGpsDistance = false;
  renderAll();
});

// Skutečná GPS poloha z prohlížeče
els.useGpsBtn.addEventListener('click', () => {
  if (!navigator.geolocation) {
    alert('Váš prohlížeč nepodporuje geolokaci.');
    return;
  }

  els.useGpsBtn.disabled = true;
  els.useGpsBtn.textContent = '📡 Zjišťuji GPS…';

  navigator.geolocation.getCurrentPosition(
    (pos) => {
      els.useGpsBtn.disabled = false;
      els.useGpsBtn.textContent = '📍 Moje GPS (Aktivní)';
      state.prevLat = state.lat;
      state.prevLon = state.lon;
      state.cisNumber = null;
      state.lat = pos.coords.latitude;
      state.lon = pos.coords.longitude;
      state.useRealGpsDistance = true;
      state.activeLineFilter = null;
      fetchDepartures();
    },
    (err) => {
      els.useGpsBtn.disabled = false;
      els.useGpsBtn.textContent = '📍 Moje GPS';
      alert(`Nepodařilo se získat GPS polohu: ${err.message}`);
    },
    { enableHighAccuracy: true, timeout: 10000 }
  );
});

// Tlačítka v notifikaci pro ruční obnovení a přepnutí zastávky
els.refreshBtn.addEventListener('click', () => fetchDepartures());
els.notifRefreshAction.addEventListener('click', () => fetchDepartures());
els.notifNextStopAction.addEventListener('click', () => {
  const nearby = state.lastData?.nearbyStops || [];
  if (nearby.length < 2) return;
  if (state.cisNumber == null) {
    state.cisNumber = nearby[1].cisNumber;
  } else {
    const currentIdx = nearby.findIndex((s) => s.cisNumber === state.cisNumber);
    if (currentIdx === nearby.length - 1) {
      state.cisNumber = null; // zpět na Chytrý uzel všech zastávek v okolí
    } else {
      state.cisNumber = nearby[(currentIdx + 1) % nearby.length].cisNumber;
    }
  }
  fetchDepartures();
});

// Časovač pro živé obnovování každých 20 sekund + hodiny na lockscreenu
setInterval(() => {
  updateLockscreenClock();
  state.refreshElapsedSec += 1;
  const pct = Math.min(100, (state.refreshElapsedSec / REFRESH_INTERVAL_SEC) * 100);
  els.refreshProgressBar.style.inlineSize = `${pct}%`;

  if (state.refreshElapsedSec >= REFRESH_INTERVAL_SEC) {
    state.refreshElapsedSec = 0;
    fetchDepartures();
  }
}, 1000);

// Start
updateLockscreenClock();
fetchDepartures();
