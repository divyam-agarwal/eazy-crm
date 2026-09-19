import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { FormAlert } from './FormAlert';
import { PasswordField } from './PasswordField';
import { SelectField } from './SelectField';
import { TextField } from './TextField';

describe('TextField', () => {
  it('links label, description and error to the input', () => {
    render(<TextField id="email" label="Email" description="Work email" error="Enter a valid email address." />);
    const input = screen.getByRole('textbox', { name: 'Email' });
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Work email Enter a valid email address.');
  });

  it('is not marked invalid without an error', () => {
    render(<TextField id="email" label="Email" />);
    expect(screen.getByRole('textbox', { name: 'Email' })).not.toHaveAttribute('aria-invalid');
  });
});

describe('PasswordField', () => {
  it('toggles visibility with a pressed-state button and never blocks paste', async () => {
    const user = userEvent.setup();
    render(<PasswordField id="pw" label="Password" showLabel="Show password" />);
    const input = screen.getByLabelText('Password', { selector: 'input' });
    const toggle = screen.getByRole('button', { name: 'Show password' });

    expect(input).toHaveAttribute('type', 'password');
    await user.click(toggle);
    expect(input).toHaveAttribute('type', 'text');
    expect(toggle).toHaveAttribute('aria-pressed', 'true');

    await user.click(input);
    await user.paste('pasted-secret');
    expect(input).toHaveValue('pasted-secret');
  });
});

describe('SelectField', () => {
  it('renders a native select with a disabled placeholder', () => {
    render(
      <SelectField id="state" label="State" placeholder="Choose your state" options={[{ value: '27', label: '27 – Maharashtra' }]} defaultValue="" />,
    );
    const select = screen.getByRole('combobox', { name: 'State' });
    expect(select.tagName).toBe('SELECT');
    expect(screen.getByRole('option', { name: 'Choose your state' })).toBeDisabled();
    expect(screen.getByRole('option', { name: '27 – Maharashtra' })).toBeInTheDocument();
  });
});

describe('FormAlert', () => {
  it('is always present as an alert region so screen readers announce changes', () => {
    const { rerender } = render(<FormAlert message={null} />);
    expect(screen.getByRole('alert')).toBeEmptyDOMElement();
    rerender(<FormAlert message="Workspace, email or password is incorrect." />);
    expect(screen.getByRole('alert')).toHaveTextContent('Workspace, email or password is incorrect.');
  });

  // A11y-1: on a phone the alert renders above the fold of a long form while the user's thumb is
  // on the submit button at the bottom. Announcing it is not the same as showing it.
  it('takes focus and scrolls into view when a message appears', () => {
    // R27: vi.spyOn is undone by vite.config.ts's `restoreMocks: true`; a direct prototype
    // assignment is not and would leak into every later test file in this worker.
    const scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {});
    const { rerender } = render(<FormAlert message={null} />);

    rerender(<FormAlert message="Can’t reach EasyCRM. Check your connection." />);

    expect(screen.getByRole('alert')).toHaveFocus();
    expect(scrollIntoView).toHaveBeenCalled();
  });

  it('does not steal focus while there is no message', () => {
    render(<FormAlert message={null} />);
    expect(screen.getByRole('alert')).not.toHaveFocus();
  });
});
