import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import auth from '@/locales/en/auth.json';
import common from '@/locales/en/common.json';

// Synchronous, bundled resources for tests; a missing key throws (spec §4.7) so a typo'd runtime
// key (e.g. from a server code) fails the test that renders it.
void i18next.use(initReactI18next).init({
  lng: 'en',
  fallbackLng: 'en',
  ns: ['common', 'auth'],
  defaultNS: 'common',
  resources: { en: { common, auth } },
  initAsync: false,
  interpolation: { escapeValue: false },
  saveMissing: true,
  missingKeyHandler: (_lngs, ns, key) => {
    throw new Error(`Missing i18n key: ${ns}:${key}`);
  },
});
