# API error reason codes

Every error uses one envelope: `{"error":{"code","message","fields"?,"fieldCodes"?}}`.

- `code` — the error class: `VALIDATION_FAILED` (400 bean validation, 422 domain validation),
  `CONFLICT` (409), `NOT_FOUND`, `UNAUTHORIZED`, `FORBIDDEN`, `RATE_LIMITED` (429).
- `fields` — field → English message, for display fallback only.
- `fieldCodes` — field → stable reason code. **Clients translate these; they never parse `message`
  or `fields` text.** Omitted when the thrower supplied none.

## Rules

1. Codes are `SCREAMING_SNAKE` and **never renamed or reused** once shipped. Retire by ceasing to emit.
2. Bean-validation failures get codes automatically: the constraint annotation name in
   SCREAMING_SNAKE (`@NotBlank` → `NOT_BLANK`, `@Size` → `SIZE`, `@Pattern` → `PATTERN`, `@Email` → `EMAIL`).
3. A hand-thrown `ValidationException` or `ConflictException` a frontend form will display **must**
   pass a code (`new ValidationException(field, message, CODE)`). Add the code to the table below in
   the same change.

## Domain codes

| Code | Field | Thrown by | Meaning |
|---|---|---|---|
| `GSTIN_REQUIRED` | `gstin` | `Gstin.parse` | null GSTIN where one is required |
| `GSTIN_LENGTH` | `gstin` | `Gstin.parse` | not 15 characters |
| `GSTIN_CHARSET` | `gstin` | `Gstin.parse` | a character outside 0-9 A-Z |
| `GSTIN_CHECKSUM` | `gstin` | `Gstin.parse` | check digit does not match |
| `STATE_CODE_INVALID` | `stateCode` | `StateCode.requireValid` | not a GST state code (also raised for a GSTIN whose first two characters are not one) |
| `STATE_CODE_GSTIN_MISMATCH` | `stateCode` | `AuthService.signup` | seller state code differs from the GSTIN's prefix |
| `SLUG_TAKEN` | `slug` | `AuthService.signup` | workspace slug already exists |
