import type { ComponentProps } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { FieldFooter } from './FieldFooter';
import { fieldIds } from './fieldIds';

export type TextFieldProps = ComponentProps<'input'> & {
  id: string;
  label: string;
  description?: string;
  error?: string;
};

export function TextField({ id, label, description, error, ...input }: TextFieldProps) {
  const ids = fieldIds(id, description, error);
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} aria-invalid={error ? true : undefined} aria-describedby={ids.describedBy} {...input} />
      <FieldFooter descriptionId={ids.descriptionId} description={description} errorId={ids.errorId} error={error} />
    </div>
  );
}
