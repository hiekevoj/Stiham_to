import { PmdpClient } from './pmdpClient.mjs';

const client = new PmdpClient();
const near = await client.findNearestStops(49.7478, 13.3746, 3);
console.log(
  'Nejbližší zastávky:',
  near.map((n) => `${n.name} (${n.distanceMeters} m, CIS: ${n.cisNumber})`)
);

const deps = await client.getLiveDepartures({
  cisNumber: near[0].cisNumber,
  maxResults: 8,
});

console.log(`\nŽivé odjezdy ze zastávky ${deps.stop.name} (${deps.fetchedAt}):`);
for (const d of deps.departures) {
  const delayStr =
    d.delayMin === null
      ? 'nevyjel'
      : d.delayMin === 0
        ? 'včas'
        : `+${d.delayMin} min`;
  console.log(
    `${d.vehicleIcon} Linka ${d.lineName.padEnd(3)} -> ${d.destination.padEnd(22)} | za ${String(d.leavingInMin).padStart(2)} min (${d.departureTime.slice(11, 16)}, ${delayStr}, online: ${d.inTraffic})`
  );
}
