/**
 * A starting suggestion for the workspace slug; the user can edit it. Non-Latin scripts have no ASCII
 * form, so a Devanagari-only name suggests nothing rather than something wrong.
 */
export function suggestSlug(businessName: string): string {
  const ascii = businessName.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase();
  const slug = ascii
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 64)
    .replace(/-+$/, '');
  return slug.length >= 3 ? slug : '';
}
