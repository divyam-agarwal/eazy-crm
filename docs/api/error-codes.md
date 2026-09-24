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
4. `SIZE` and `PATTERN` are **parameterised** — the same code means "too short" on one field and
   "too long" on another, and carries no min/max itself. A bare `errors.fields.SIZE` /
   `errors.fields.PATTERN` translation ("This value has the wrong length.") is a safe fallback but
   states no real limit, so the frontend resolves a **per-field** key first
   (`errors.fields.<field>.<CODE>`, e.g. `errors.fields.password.SIZE` → "Use at least 8
   characters.") before falling back to the bare code key (`frontend/src/lib/apiError.ts`,
   `applyApiError`). Adding a new `@Size`/`@Pattern` field that a form displays means adding its
   per-field key to `frontend/src/locales/en/common.json`'s `errors.fields.<field>` block, not just
   relying on the generic one.

## Domain codes

| Code | Field | Thrown by | Meaning |
|---|---|---|---|
| `GSTIN_REQUIRED` | `gstin` | `Gstin.parse` | null GSTIN where one is required |
| `GSTIN_LENGTH` | `gstin` | `Gstin.parse` | not 15 characters |
| `GSTIN_CHARSET` | `gstin` | `Gstin.parse` | a character outside 0-9 A-Z |
| `GSTIN_CHECKSUM` | `gstin` | `Gstin.parse` | check digit does not match |
| `STATE_CODE_INVALID` | `stateCode` | `StateCode.requireValid` | not a GST state code (also raised for a GSTIN whose first two characters are not one) |
| `STATE_CODE_GSTIN_MISMATCH` | `stateCode` | `AuthService.signup`, `CustomerService.resolveGstinAndState` | seller state code differs from the GSTIN's prefix |
| `STATE_CODE_REQUIRED` | `stateCode` | `CustomerService.resolveGstinAndState` | no GSTIN supplied and no state code to fall back on |
| `GSTIN_DUPLICATE` | `gstin` | `CustomerService.create` | another customer in this tenant already has this GSTIN |
| `SLUG_TAKEN` | `slug` | `AuthService.signup` | workspace slug already exists |
| `SORT_INVALID` | `sort` | `SortAllowlist.require` | a `sort` field the endpoint does not allow |
| `ASSIGNEE_INVALID` | `assignedTo` | `AssignableUsers.require` | assignee is not an active user of this tenant (checked by `CustomerService`, `EnquiryService`, `ActivityService`, `FollowUpService`) |
| `HSN_CODE_INVALID` | `hsnCode` | `ProductService.validate` | not 4, 6, or 8 digits |
| `GST_RATE_INVALID` | `gstRate` | `ProductService.validate` | not one of the allowed GST rates (0, 0.25, 3, 5, 12, 18, 28) |
| `BASE_RATE_NEGATIVE` | `baseRate` | `ProductService.validate` | base rate is negative |
| `SKU_DUPLICATE` | `sku` | `ProductService.create` | another product in this tenant already has this SKU |
| `NAME_DUPLICATE` | `name` | `PriceListService.create`, `PriceListService.rename` | another price list in this tenant already has this name |
| `RATE_RULE_XOR` | `overrideRate` | `PriceListItemService.validateXor` | neither or both of `overrideRate`/`discountPct` were set; exactly one is required |
| `OVERRIDE_RATE_NEGATIVE` | `overrideRate` | `PriceListItemService.validateRange` | override rate is negative |
| `DISCOUNT_PCT_RANGE` | `discountPct` | `PriceListItemService.validateRange` | discount percent is outside 0-100 |
| `PRODUCT_DUPLICATE` | `productId` | `PriceListItemService.add` | this product is already priced in this price list |
