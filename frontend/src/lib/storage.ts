const LAST_WORKSPACE = 'easycrm.lastWorkspace';

// Every access is wrapped (spec §4.8): private modes, blocked site data and full quotas all throw, and
// any of them means "nothing remembered", never a crash.
export function readLastWorkspace(): string | null {
  try {
    return globalThis.localStorage.getItem(LAST_WORKSPACE);
  } catch {
    return null;
  }
}

export function writeLastWorkspace(slug: string): void {
  try {
    globalThis.localStorage.setItem(LAST_WORKSPACE, slug);
  } catch {
    // nothing remembered
  }
}
