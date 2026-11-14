/** Sets process.env.TZ, checks that the offset really changed, returns a function that restores it (also when TZ was unset). */
export function withTimeZone(zone) {
  const had = Object.prototype.hasOwnProperty.call(process.env, 'TZ');
  const original = process.env.TZ;
  const machineZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const probe = Date.UTC(2026, 6, 1, 12);
  const offsetBefore = new Date(probe).getTimezoneOffset();
  process.env.TZ = zone;
  if (Intl.DateTimeFormat().resolvedOptions().timeZone !== zone) {
    throw new Error(`TZ ${zone} not applied (still ${Intl.DateTimeFormat().resolvedOptions().timeZone}); does the worker share process.env?`);
  }
  return () => {
    // Node does not fall back to the machine zone after `delete process.env.TZ`, so an unset TZ is restored as the machine zone
    process.env.TZ = had ? original : machineZone;
    const zoneNow = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (new Date(probe).getTimezoneOffset() !== offsetBefore || zoneNow !== machineZone) {
      throw new Error(`TZ not restored: ${zoneNow} instead of ${machineZone}`);
    }
  };
}
