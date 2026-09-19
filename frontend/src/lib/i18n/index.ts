import i18next, { type i18n } from 'i18next';
import resourcesToBackend from 'i18next-resources-to-backend';
import { initReactI18next } from 'react-i18next';

export const NAMESPACES = ['common', 'auth'] as const;

/** Loaded at start-up. Everything else is loaded by the route that needs it (Performance-4). */
export const BOOT_NAMESPACES = ['common'] as const;

/**
 * Spec §4.7. Each language/namespace is its own dynamic import, so a future `hi` adds nothing to the
 * English user's bundle.
 *
 * <p>Performance-4: `ns` is the PRELOAD list, not the list of namespaces that exist. Preloading all
 * of them means every visit downloads every feature's strings — in F3 a `/invite/:token` landing
 * would fetch catalog, quotation and import text before first render, and one failed chunk leaves
 * the page suspended or showing raw keys. Only `common` is preloaded; a page pulls its own namespace
 * (`useTranslation('auth')` suspends on it, or the route's `lazy` calls
 * `i18n.loadNamespaces('auth')`). `auth` still lands in the same round trip as the route chunk.
 */
export function initI18n(instance: i18n = i18next): Promise<unknown> {
  instance.on('languageChanged', (lng) => {
    document.documentElement.lang = lng;
  });
  return instance
    .use(initReactI18next)
    .use(resourcesToBackend((lng: string, ns: string) => import(`../../locales/${lng}/${ns}.json`)))
    .init({
      lng: 'en',
      supportedLngs: ['en'],
      fallbackLng: 'en',
      ns: [...BOOT_NAMESPACES],
      defaultNS: 'common',
      interpolation: { escapeValue: false },
    });
}
