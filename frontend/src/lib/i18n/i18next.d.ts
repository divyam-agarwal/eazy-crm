import 'i18next';
import type auth from '../../locales/en/auth.json';
import type common from '../../locales/en/common.json';

// Spec §4.7: an unknown key fails `tsc`. `en` is the source of truth for key shape.
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: { common: typeof common; auth: typeof auth };
  }
}
