import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const STOPS_CACHE_PATH = path.join(__dirname, '..', 'data', 'stops.json');
const PMDP_BASE_URL = 'https://jizdnirady.pmdp.cz';

const TRACTION_MAP = {
  1: { type: 'tram', label: 'Tramvaj', icon: '🚋' },
  2: { type: 'trolleybus', label: 'Trolejbus', icon: '🚎' },
  3: { type: 'bus', label: 'Autobus', icon: '🚌' },
};

/**
 * Vypočítá vzdálenost dvou GPS bodů v metrech (Haversine formula).
 */
export function haversineDistanceMeters(lat1, lon1, lat2, lon2) {
  const R = 6371000;
  const toRad = (deg) => (deg * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * Vyhodnotí, zda chodec spoj stíhá podle vzdálenosti k označníku (v metrech) a času do odjezdu (v minutách).
 */
export function evaluateCatchability(distanceMeters, leavingInMin) {
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

  // Běžná chůze ~80 m/min (4,8 km/h), rychlá chůze/klus ~135 m/min
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
      label: distanceMeters > 60 ? 'Poklusem!' : 'Pospěš si!',
      walkMinutes: Math.ceil(normalWalkMin),
    };
  }
  return {
    status: 'comfortable',
    label: 'Stíháš v klidu',
    walkMinutes: Math.max(1, Math.round(normalWalkMin)),
  };
}

export class PmdpClient {
  constructor() {
    this.stops = [];
    this.sessionCookie = null;
    this.verificationToken = null;
    this.tokenFetchedAt = 0;
    this.loadLocalStops();
  }

  loadLocalStops() {
    if (fs.existsSync(STOPS_CACHE_PATH)) {
      const raw = fs.readFileSync(STOPS_CACHE_PATH, 'utf8');
      this.stops = JSON.parse(raw);
    }
  }

  async ensureStopsLoaded() {
    if (this.stops && this.stops.length > 0) {
      return this.stops;
    }
    const res = await fetch(`${PMDP_BASE_URL}/zastavky/zastavky`, {
      headers: {
        'User-Agent': 'StihamTo-Plzen/1.0',
        Accept: 'application/json',
      },
    });
    if (!res.ok) {
      throw new Error(`Nepodařilo se načíst zastávky PMDP: HTTP ${res.status}`);
    }
    this.stops = await res.json();
    fs.mkdirSync(path.dirname(STOPS_CACHE_PATH), { recursive: true });
    fs.writeFileSync(STOPS_CACHE_PATH, JSON.stringify(this.stops), 'utf8');
    return this.stops;
  }

  /**
   * Získá a nakešuje CSRF token + session cookie z webu jizdnirady.pmdp.cz
   */
  async ensureSession(forceRefresh = false) {
    const ttlMs = 25 * 60 * 1000; // 25 minut
    if (
      !forceRefresh &&
      this.sessionCookie &&
      this.verificationToken &&
      Date.now() - this.tokenFetchedAt < ttlMs
    ) {
      return;
    }

    const res = await fetch(`${PMDP_BASE_URL}/odjezdy`, {
      headers: {
        'User-Agent':
          'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36',
        Accept: 'text/html,application/xhtml+xml',
      },
    });

    if (!res.ok) {
      throw new Error(`Chyba při inicializaci PMDP relace: HTTP ${res.status}`);
    }

    const setCookies =
      typeof res.headers.getSetCookie === 'function'
        ? res.headers.getSetCookie()
        : [res.headers.get('set-cookie')].filter(Boolean);

    const cookiePairs = setCookies.map((c) => c.split(';')[0].trim());
    if (cookiePairs.length > 0) {
      this.sessionCookie = cookiePairs.join('; ');
    }

    const html = await res.text();
    const tokenMatch = html.match(
      /name=['"]X-API-Request-Verification-Token['"]\s+value=['"]([^'"]+)['"]/i
    );
    if (!tokenMatch) {
      throw new Error('Nepodařilo se nalézt X-API-Request-Verification-Token v HTML PMDP.');
    }

    this.verificationToken = tokenMatch[1];
    this.tokenFetchedAt = Date.now();
  }

  /**
   * Interní POST volání na PMDP endpoint s automatickým obnovením tokenu při expiraci.
   */
  async apiPost(endpoint, payload, isRetry = false) {
    await this.ensureSession(isRetry);

    const headers = {
      'Content-Type': 'application/json; charset=UTF-8',
      Accept: 'application/json, text/javascript, */*; q=0.01',
      'X-Requested-With': 'XMLHttpRequest',
      'X-API-Request-Verification-Token': this.verificationToken,
      Origin: PMDP_BASE_URL,
      Referer: `${PMDP_BASE_URL}/odjezdy`,
      'User-Agent':
        'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36',
    };

    if (this.sessionCookie) {
      headers.Cookie = this.sessionCookie;
    }

    const res = await fetch(`${PMDP_BASE_URL}${endpoint}`, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
    });

    if (!res.ok) {
      if (
        !isRetry &&
        (res.status === 400 || res.status === 401 || res.status === 403 || res.status === 500)
      ) {
        return this.apiPost(endpoint, payload, true);
      }
      const errText = await res.text().catch(() => '');
      throw new Error(`PMDP API chyba (${endpoint}): HTTP ${res.status} ${errText.slice(0, 200)}`);
    }

    return res.json();
  }

  /**
   * Vypočítá nejmenší vzdálenost uživatele k označníkům (Markers) nebo středu zastávky.
   */
  calculateStopDistance(stop, userLat, userLon) {
    if (userLat == null || userLon == null) return null;
    const lat = Number(userLat);
    const lon = Number(userLon);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;

    let minDist = haversineDistanceMeters(
      lat,
      lon,
      stop.Placement.Latitude,
      stop.Placement.Longitude
    );

    if (Array.isArray(stop.Markers) && stop.Markers.length > 0) {
      for (const m of stop.Markers) {
        if (m.Latitude != null && m.Longitude != null) {
          const d = haversineDistanceMeters(lat, lon, m.Latitude, m.Longitude);
          if (d < minDist) minDist = d;
        }
      }
    }
    if (minDist <= 45) return Math.round(minDist);
    const factor = minDist <= 120 ? 1.14 : minDist <= 280 ? 1.2 : 1.25;
    return Math.round(minDist * factor);
  }

  /**
   * Vyhodnotí, zda se chodec k zastávce přibližuje nebo od ní odchází (pokud máme předchozí GPS bod).
   * Vrací deltaMeters (záporné = přibližuje se, kladné = vzdaluje se) a walkingStatus ('approaching' | 'leaving' | 'stationary').
   */
  evaluateMovementTowardsStop(stop, lat, lon, prevLat, prevLon) {
    if (prevLat == null || prevLon == null || lat == null || lon == null) {
      return { deltaMeters: 0, movementStatus: 'stationary' };
    }
    const currDist = this.calculateStopDistance(stop, lat, lon);
    const prevDist = this.calculateStopDistance(stop, prevLat, prevLon);
    if (currDist == null || prevDist == null) {
      return { deltaMeters: 0, movementStatus: 'stationary' };
    }
    const delta = currDist - prevDist; // < 0 znamená, že jsme blíž než předtím
    if (delta <= -8) {
      return { deltaMeters: delta, movementStatus: 'approaching' };
    }
    if (delta >= 8) {
      return { deltaMeters: delta, movementStatus: 'leaving' };
    }
    return { deltaMeters: delta, movementStatus: 'stationary' };
  }

  /**
   * Najde nejbližší zastávky podle GPS souřadnic, včetně vyhodnocení směru chůze (pokud je zadán prevLat/prevLon).
   */
  async findNearestStops(lat, lon, limit = 6, { prevLat = null, prevLon = null } = {}) {
    await this.ensureStopsLoaded();

    const withDistance = this.stops.map((stop) => {
      const dist = this.calculateStopDistance(stop, lat, lon);
      const movement = this.evaluateMovementTowardsStop(stop, lat, lon, prevLat, prevLon);

      // Efektivní skóre vzdálenosti: pokud od zastávky odcházíme (+delta), mírně ji penalizujeme,
      // pokud k ní jdeme (-delta), zvýhodníme ji.
      const effectiveRankDistance =
        dist + (movement.movementStatus === 'leaving' ? 95 : movement.movementStatus === 'approaching' ? -55 : 0);

      return {
        ...this.formatStopSummary(stop),
        distanceMeters: dist,
        walkingMinutes: dist != null ? Math.max(1, Math.round(dist / 80)) : null,
        movementStatus: movement.movementStatus,
        deltaMeters: movement.deltaMeters,
        effectiveRankDistance,
      };
    });

    withDistance.sort((a, b) => a.effectiveRankDistance - b.effectiveRankDistance);
    return withDistance.slice(0, limit);
  }

  /**
   * Vyhledá zastávky podle názvu (bez diakritiky, case-insensitive).
   */
  async searchStopsByName(query, limit = 10) {
    await this.ensureStopsLoaded();
    const norm = (str) =>
      (str || '')
        .toLowerCase()
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '');
    const q = norm((query || '').trim());
    if (!q) return this.stops.slice(0, limit).map((s) => this.formatStopSummary(s));

    const startsWith = [];
    const contains = [];
    for (const stop of this.stops) {
      const n = norm(stop.Name);
      if (n.startsWith(q)) {
        startsWith.push(this.formatStopSummary(stop));
      } else if (n.includes(q)) {
        contains.push(this.formatStopSummary(stop));
      }
    }
    return [...startsWith, ...contains].slice(0, limit);
  }

  formatStopSummary(stop) {
    const tractions = new Set();
    const lines = (stop.Lines || []).map((l) => {
      const tType = l.Line.TractionType;
      tractions.add(TRACTION_MAP[tType]?.type || 'other');
      return {
        name: l.Line.Name,
        tractionType: tType,
        vehicleType: TRACTION_MAP[tType]?.type || 'other',
        icon: TRACTION_MAP[tType]?.icon || '🚍',
        isNight: l.Line.IsNightConnection,
      };
    });

    return {
      name: stop.Name,
      cisNumber: stop.Placement.CISJRNumber || stop.Number,
      stopId: stop.Placement.StopId,
      isBarrierFree: stop.IsBarrierFree,
      latitude: stop.Placement.Latitude,
      longitude: stop.Placement.Longitude,
      markersCount: stop.Markers?.length || 1,
      availableVehicleTypes: Array.from(tractions),
      lines,
    };
  }

  /**
   * Pomocná metoda pro převod surových odjezdů z PMDP pro jednu zastávku.
   */
  mapRawDepartures(rawDepartures, stopSummary, distanceMeters) {
    return (rawDepartures || []).map((item) => {
      const traction = TRACTION_MAP[item.Line?.TractionType] || {
        type: 'other',
        label: 'Spoj',
        icon: '🚍',
      };

      const delayMin = item.DelayMin;
      const scheduledTime = item.DepartureTime;
      const scheduledHHMM = scheduledTime ? scheduledTime.slice(11, 16) : '';

      let estimatedHHMM = scheduledHHMM;
      if (scheduledTime && delayMin && delayMin > 0) {
        const dt = new Date(scheduledTime);
        if (!Number.isNaN(dt.getTime())) {
          dt.setMinutes(dt.getMinutes() + delayMin);
          const hh = String(dt.getHours()).padStart(2, '0');
          const mm = String(dt.getMinutes()).padStart(2, '0');
          estimatedHHMM = `${hh}:${mm}`;
        }
      }

      const catchability = evaluateCatchability(distanceMeters, item.LeavingInMin);

      return {
        stopName: stopSummary.name,
        stopCis: stopSummary.cisNumber,
        stopDistanceMeters: distanceMeters,
        stopMovementStatus: stopSummary.movementStatus || 'stationary',
        lineName: item.Line?.Name || '?',
        lineNumber: item.Line?.Number || null,
        tractionType: item.Line?.TractionType || null,
        vehicleType: traction.type,
        vehicleLabel: traction.label,
        vehicleIcon: traction.icon,
        isNight: Boolean(item.Line?.IsNightConnection),
        isBarrierFree: Boolean(item.Line?.IsBarrierFree),
        isAirConditioned: Boolean(item.IsAirConditioned),
        destination: item.LastStopName,
        departureTime: scheduledTime,
        scheduledHHMM,
        estimatedHHMM,
        leavingInMin: item.LeavingInMin,
        delayMin,
        inTraffic: Boolean(item.ConnectionId?.InTraffic),
        connectionId: item.ConnectionId?.ConnectionId || null,
        onRequest: Boolean(item.Notes?.OnRequest),
        catchability,
        nextStops: (item.Stops || [])
          .filter((st) => !st.Passed)
          .slice(0, 8)
          .map((st) => ({
            name: st.Name,
            time: st.Time ? st.Time.slice(11, 16) : null,
            highlight: Boolean(st.Highlight),
          })),
      };
    });
  }

  /**
   * Načte živé odjezdy.
   * Podporuje dva režimy:
   * 1) Konkrétní zastávka (`cisNumber` zadáno a `multiStop` === false)
   * 2) Chytrý okolní uzel (`multiStop` === true): sloučí odjezdy ze všech zastávek v okruhu `radiusMeters`
   *    (např. zastávka busu 40 m před křižovatkou + zastávka tramvaje 130 m za křižovatkou),
   *    zohlední směr chůze (`prevLat`, `prevLon`) a u každého spoje počítá "Stíhám to?" vůči jeho vlastní zastávce!
   */
  async getLiveDepartures({
    cisNumber = null,
    stopId = null,
    lat = null,
    lon = null,
    prevLat = null,
    prevLon = null,
    multiStop = false,
    radiusMeters = 240,
    maxResults = 16,
  }) {
    await this.ensureStopsLoaded();

    let nearbyStops = [];
    if (lat != null && lon != null) {
      nearbyStops = await this.findNearestStops(Number(lat), Number(lon), 6, {
        prevLat,
        prevLon,
      });
    }

    // Režim chytrého sloučení více zastávek v okolí (když jdu přes zastávku busu na tramvaj apod.)
    if (multiStop && nearbyStops.length > 0 && !cisNumber) {
      // Vybereme zastávky v okruhu radiusMeters (max 3 nejrelevantnější zastávky, ať je odpověď blesková)
      const clusterStops = nearbyStops.filter(
        (s, idx) => idx === 0 || (s.distanceMeters <= radiusMeters && idx < 3)
      );

      // Pokud nejbližší zastávka nemá tramvaj, ale o kousek dál (do 300 m) je tramvajová zastávka,
      // automaticky ji přidáme do klastru!
      const hasTramInCluster = clusterStops.some((s) => s.availableVehicleTypes.includes('tram'));
      if (!hasTramInCluster) {
        const nearbyTramStop = nearbyStops.find(
          (s) => s.availableVehicleTypes.includes('tram') && s.distanceMeters <= 320
        );
        if (nearbyTramStop && !clusterStops.some((c) => c.cisNumber === nearbyTramStop.cisNumber)) {
          clusterStops.push(nearbyTramStop);
        }
      }

      const perStopResults = await Promise.all(
        clusterStops.map(async (st) => {
          const payload = {
            Stop: {
              StopId: st.stopId,
              CISJRNumber: st.cisNumber,
              MarkerCode: null,
              Latitude: st.latitude,
              Longitude: st.longitude,
              MapyCzPoiType: null,
            },
            DateAndTime: null,
            MaxResults: Math.max(10, Math.ceil(maxResults / clusterStops.length) + 4),
            MaxResultsDateAndTime: null,
            FullResults: true,
          };
          const raw = await this.apiPost('/odjezdy/vyhledat', payload);
          return this.mapRawDepartures(raw, st, st.distanceMeters);
        })
      );

      const mergedDepartures = perStopResults.flat();
      // Seřadíme podle času do odjezdu (leavingInMin)
      mergedDepartures.sort((a, b) => a.leavingInMin - b.leavingInMin);

      return {
        mode: 'multi',
        stop: clusterStops[0],
        clusterStops,
        nearbyStops,
        fetchedAt: new Date().toISOString(),
        departures: mergedDepartures.slice(0, maxResults + 8),
      };
    }

    // Režim jedné konkrétní zastávky
    let matchedStop = null;
    if (cisNumber != null) {
      matchedStop = this.stops.find(
        (s) => s.Placement.CISJRNumber === Number(cisNumber) || s.Number === Number(cisNumber)
      );
    } else if (stopId != null) {
      matchedStop = this.stops.find((s) => s.Placement.StopId === Number(stopId));
    } else if (nearbyStops.length > 0) {
      matchedStop = this.stops.find((s) => s.Placement.CISJRNumber === nearbyStops[0].cisNumber);
    }

    const stopLat = matchedStop ? matchedStop.Placement.Latitude : Number(lat);
    const stopLon = matchedStop ? matchedStop.Placement.Longitude : Number(lon);
    const stopCis = matchedStop
      ? matchedStop.Placement.CISJRNumber
      : cisNumber
        ? Number(cisNumber)
        : null;
    const resolvedStopId = matchedStop
      ? matchedStop.Placement.StopId
      : stopId
        ? Number(stopId)
        : null;

    const distanceMeters = matchedStop ? this.calculateStopDistance(matchedStop, lat, lon) : null;
    const movement = matchedStop
      ? this.evaluateMovementTowardsStop(matchedStop, lat, lon, prevLat, prevLon)
      : { deltaMeters: 0, movementStatus: 'stationary' };

    const stopSummary = matchedStop
      ? {
          ...this.formatStopSummary(matchedStop),
          distanceMeters,
          walkingMinutes:
            distanceMeters != null ? Math.max(1, Math.round(distanceMeters / 80)) : null,
          movementStatus: movement.movementStatus,
          deltaMeters: movement.deltaMeters,
        }
      : {
          name: 'Neznámá zastávka',
          cisNumber: stopCis,
          latitude: stopLat,
          longitude: stopLon,
          distanceMeters,
        };

    const payload = {
      Stop: {
        StopId: resolvedStopId,
        CISJRNumber: stopCis,
        MarkerCode: null,
        Latitude: stopLat,
        Longitude: stopLon,
        MapyCzPoiType: null,
      },
      DateAndTime: null,
      MaxResults: maxResults,
      MaxResultsDateAndTime: null,
      FullResults: true,
    };

    const rawDepartures = await this.apiPost('/odjezdy/vyhledat', payload);
    const departures = this.mapRawDepartures(rawDepartures, stopSummary, distanceMeters);

    return {
      mode: 'single',
      stop: stopSummary,
      clusterStops: [stopSummary],
      nearbyStops,
      fetchedAt: new Date().toISOString(),
      departures,
    };
  }
}
