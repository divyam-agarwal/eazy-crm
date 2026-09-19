import i18next, { type i18n } from 'i18next';
import resourcesToBackend from 'i18next-resources-to-backend';
import { initReactI18next } from 'react-i18next';
import { withImportRetry } from '@/app/lazyImport';

export const NAMESPACES = ['common', 'auth'] as const;

/**
 * Loaded at start-up. Everything else is loaded by the route that needs it (Performance-4) — but
 * `auth` is preloaded here too, not left lazy. See the note below `initI18n` for why.
 */
export const BOOT_NAMESPACES = ['common', 'auth'] as const;

/**
 * Spec §4.7. Each language/namespace is its own dynamic import, so a future `hi` adds nothing to the
 * English user's bundle.
 *
 * <p>Performance-4: `ns` is the PRELOAD list, not the list of namespaces that exist — a future
 * namespace (catalog, quotation, import, ...) stays lazy, pulled by the route that needs it
 * (`useTranslation(ns)` suspends on it, or the route's `lazy` calls `i18n.loadNamespaces(ns)`), so
 * visiting one feature never downloads another's strings.
 *
 * <p>`auth` is the one exception, preloaded alongside `common`. Under `i18next-resources-to-backend`,
 * a namespace fetched via `resourcesToBackend((lng, ns) => import(...))` becomes its own sibling
 * dynamic chunk in Vite's manifest — it is not a static dependency of the route chunk that requests
 * it. Left lazy, `/login` (and `/signup`, `/invite/:token`, every F0 route) would pay two sequential
 * network legs before the form paints: fetch route chunk → parse → render → suspend → fetch
 * `auth.json` → parse → re-render. Measured cost of preloading it instead is 833 B gzipped against a
 * 200 KB route budget (see `DEPENDENCIES.md`), and every F0 route needs `auth` — so the "don't
 * download what this route doesn't need" rationale that keeps other namespaces lazy does not apply
 * to it. Trading ~1 KB for a whole extra round trip before the first screen a user sees is the wrong
 * trade.
 */
export function initI18n(instance: i18n = i18next): Promise<unknown> {
  instance.on('languageChanged', (lng) => {
    document.documentElement.lang = lng;
  });
  return instance
    .use(initReactI18next)
    .use(
      resourcesToBackend((lng: string, ns: string) =>
        // R47(b): a namespace chunk is its own dynamic import, same as a route chunk — retry a
        // dropped connection instead of leaving `useTranslation`'s Suspense permanently rejected.
        withImportRetry(() => import(`../../locales/${lng}/${ns}.json`)),
      ),
    )
    .init({
      lng: 'en',
      supportedLngs: ['en'],
      fallbackLng: 'en',
      ns: [...BOOT_NAMESPACES],
      defaultNS: 'common',
      interpolation: { escapeValue: false },
    });
}
