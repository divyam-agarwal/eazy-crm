/**
 * R25: the description/error `<p>` pair shared by TextField, PasswordField and SelectField — one
 * copy instead of three, so the A11y-2 contrast fix (and anything else that touches this markup)
 * only has to be made once.
 */
export function FieldFooter({
  descriptionId,
  description,
  errorId,
  error,
}: {
  descriptionId?: string;
  description?: string;
  errorId?: string;
  error?: string;
}) {
  return (
    <>
      {description && (
        <p id={descriptionId} className="text-muted-foreground text-sm">
          {description}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-destructive-strong text-sm">
          {error}
        </p>
      )}
    </>
  );
}
