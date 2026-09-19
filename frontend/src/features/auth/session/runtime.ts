import type { AuthChannel } from './authChannel';
import type { LockProvider } from './lockProvider';

/** Everything the session needs from outside the feature, injected by app/bootstrap (or a test). */
export interface SessionRuntime {
  clearQueryCache(): void;
  channel: AuthChannel;
  locks: LockProvider;
  reload(): void;
  log(message: string): void;
}

let runtime: SessionRuntime | null = null;

export function configureSession(value: SessionRuntime): void {
  runtime = value;
}

export function sessionRuntime(): SessionRuntime {
  if (!runtime) throw new Error('configureSession() has not run');
  return runtime;
}
