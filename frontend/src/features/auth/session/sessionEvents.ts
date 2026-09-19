// The HTTP layer never calls the router (spec §4.4): it raises this event, and the router listens, so
// F2 can swap navigation for a re-login dialog that preserves an in-progress quotation.
const target = new EventTarget();
const EXPIRED = 'session-expired';

export function emitSessionExpired(): void {
  target.dispatchEvent(new Event(EXPIRED));
}

export function onSessionExpired(listener: () => void): () => void {
  target.addEventListener(EXPIRED, listener);
  return () => target.removeEventListener(EXPIRED, listener);
}
