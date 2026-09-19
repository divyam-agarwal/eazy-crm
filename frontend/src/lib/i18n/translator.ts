import { useMemo } from 'react';
import type { FieldError } from 'react-hook-form';
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

/**
 * Zod messages are i18n keys (spec §4.7); server field messages are already final text from
 * applyApiError. A message that is a known key is translated; anything else is shown as is.
 */
export function useFieldError(): (error: FieldError | undefined) => string | undefined {
  const tr = useTranslator();
  return (error) => {
    const message = error?.message;
    if (!message) return undefined;
    return tr.exists(message) ? tr.t(message) : message;
  };
}
