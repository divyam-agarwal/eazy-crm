import i18next from 'i18next';
import { describe, expect, it } from 'vitest';
import { initI18n } from './index';

describe('initI18n', () => {
  it('lazy-loads English resources per namespace and sets <html lang>', async () => {
    const instance = i18next.createInstance();
    document.documentElement.lang = '';

    await initI18n(instance);

    expect(instance.t('app.name')).toBe('EasyCRM');
    // R4: `auth` is not in BOOT_NAMESPACES (Performance-4 — only `common` preloads), so it must be
    // loaded explicitly before asserting on one of its keys.
    await instance.loadNamespaces('auth');
    expect(instance.t('login.heading', { ns: 'auth' })).toBe('Sign in to EasyCRM');
    expect(document.documentElement.lang).toBe('en');
  });

  it('preloads only the boot namespace, leaving auth to load lazily', async () => {
    const instance = i18next.createInstance();

    await initI18n(instance);

    expect(instance.options.ns).toEqual(['common']);
    expect(instance.hasResourceBundle('en', 'auth')).toBe(false);

    await instance.loadNamespaces('auth');

    expect(instance.hasResourceBundle('en', 'auth')).toBe(true);
  });
});

describe('test i18n instance', () => {
  it('throws on a missing key instead of rendering the key', () => {
    expect(() => i18next.t('actions.nope' as never)).toThrow(/Missing i18n key/);
  });
});
