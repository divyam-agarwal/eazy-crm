export function describedBy(...ids: (string | undefined)[]): string | undefined {
  const joined = ids.filter(Boolean).join(' ');
  return joined || undefined;
}

export function fieldIds(id: string, description?: string, error?: string) {
  const descriptionId = description ? `${id}-description` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  return { descriptionId, errorId, describedBy: describedBy(descriptionId, errorId) };
}
