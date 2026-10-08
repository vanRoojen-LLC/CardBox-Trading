/** Where to land after signing in: a page on this site only, never another origin. */
export function safeReturnTo(value: string | null): string | null {
  return value && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\') ? value : null
}

