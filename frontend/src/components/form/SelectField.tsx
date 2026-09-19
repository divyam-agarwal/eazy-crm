import type { ComponentProps } from 'react';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { FieldFooter } from './FieldFooter';
import { fieldIds } from './fieldIds';

export type SelectFieldProps = ComponentProps<'select'> & {
  id: string;
  label: string;
  placeholder: string;
  options: readonly { value: string; label: string }[];
  description?: string;
  error?: string;
};

// Native on purpose (plan decision P9): Radix Select injects a <style> tag the CSP refuses, and a
// native picker is the right control on low-end Android.
export function SelectField({ id, label, placeholder, options, description, error, className, ...select }: SelectFieldProps) {
  const ids = fieldIds(id, description, error);
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <select
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={ids.describedBy}
        className={cn(
          'border-input bg-background h-9 w-full rounded-md border px-3 text-base md:text-sm',
          'aria-invalid:border-destructive',
          className,
        )}
        {...select}
      >
        <option value="" disabled>
          {placeholder}
        </option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <FieldFooter descriptionId={ids.descriptionId} description={description} errorId={ids.errorId} error={error} />
    </div>
  );
}
