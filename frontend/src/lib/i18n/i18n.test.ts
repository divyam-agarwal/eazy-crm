import i18next from 'i18next';
import { describe, expect, it } from 'vitest';
import { initI18n } from './index';

describe('initI18n', () => {
  it('lazy-loads English resources per namespace and sets <html lang>', async () => {
    const instance = i18next.createInstance();
    document.documentElement.lang = '';

    await initI18n(instance);

    expect(instance.t('app.name')).toBe('EasyCRM');
    // `auth` is preloaded (BOOT_NAMESPACES), so its keys resolve immediately after initI18n with no
    // separate loadNamespaces call — see the perf note on BOOT_NAMESPACES for why.
    expect(instance.t('login.heading', { ns: 'auth' })).toBe('Sign in to EasyCRM');
    expect(document.documentElement.lang).toBe('en');
  });

  it('preloads both boot namespaces so no F0 route pays a second network round trip', async () => {
    const instance = i18next.createInstance();

    await initI18n(instance);

    expect(instance.options.ns).toEqual(['common', 'auth']);
    expect(instance.hasResourceBundle('en', 'common')).toBe(true);
    expect(instance.hasResourceBundle('en', 'auth')).toBe(true);
  });
});

describe('test i18n instance', () => {
  it('throws on a missing key instead of rendering the key', () => {
    expect(() => i18next.t('actions.nope' as never)).toThrow(/Missing i18n key/);
  });
});
