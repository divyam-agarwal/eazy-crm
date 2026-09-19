import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * A deliberately untyped view of i18next for keys built at runtime from server codes
 * (`errors.fields.${code}`). Static UI text uses the typed `t` from useTranslation instead.
 */
export interface Translator {
  t(key: string, options?: Record<string, unknown>): string;
  exists(key: string): boolean;
}

export function useTranslator(): Translator {
  const { i18n } = useTranslation();
  return useMemo(() => {
    const untypedT = i18n.t as unknown as (key: string, options?: Record<string, unknown>) => string;
    return { t: (key, options) => untypedT(key, options), exists: (key) => i18n.exists(key) };
  }, [i18n]);
}
