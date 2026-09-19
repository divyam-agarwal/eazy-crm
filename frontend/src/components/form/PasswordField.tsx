import { Eye, EyeOff } from 'lucide-react';
import { useState } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { FieldFooter } from './FieldFooter';
import { fieldIds } from './fieldIds';
import type { TextFieldProps } from './TextField';

export type PasswordFieldProps = Omit<TextFieldProps, 'type'> & { showLabel: string };

export function PasswordField({ id, label, description, error, showLabel, ...input }: PasswordFieldProps) {
  const [visible, setVisible] = useState(false);
  const ids = fieldIds(id, description, error);
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <div className="relative">
        <Input
          id={id}
          type={visible ? 'text' : 'password'}
          className="pr-11"
          aria-invalid={error ? true : undefined}
          aria-describedby={ids.describedBy}
          {...input}
        />
        <button
          type="button"
          className="absolute inset-y-0 right-0 grid w-11 place-items-center"
          aria-label={showLabel}
          aria-pressed={visible}
          aria-controls={id}
          onClick={() => setVisible((v) => !v)}
        >
          {visible ? <EyeOff aria-hidden className="size-4" /> : <Eye aria-hidden className="size-4" />}
        </button>
      </div>
      <FieldFooter descriptionId={ids.descriptionId} description={description} errorId={ids.errorId} error={error} />
    </div>
  );
}
