/** Card details inventory filters on and storage rules test, with how their values read on the page. */

export interface FacetValue { value: string; label: string | null; cards: number | null }
export type Facets = Record<string, FacetValue[]>

/** The filters the page offers, in order. Each counts its values on the server. */
export const FACETS: { key: string; label: string; searchable?: boolean }[] = [
  { key: 'game', label: 'Game' }, { key: 'set', label: 'Set', searchable: true }, { key: 'year', label: 'Year' },
  { key: 'rarity', label: 'Rarity' }, { key: 'color', label: 'Color' }, { key: 'type', label: 'Type' },
  { key: 'finish', label: 'Finish' }, { key: 'treatment', label: 'Treatment' }, { key: 'condition', label: 'Condition' },
  { key: 'source', label: 'Came from' },
]

export const GAMES: Record<string, string> = { 'magic-the-gathering': 'Magic', 'star-wars-unlimited': 'Star Wars: Unlimited' }
export const COLORS: Record<string, string> = { W: 'White', U: 'Blue', B: 'Black', R: 'Red', G: 'Green', M: 'Multicolor', C: 'Colorless' }
export const TREATMENTS: Record<string, string> = {
  none: 'Plain', showcase: 'Showcase', 'extended-art': 'Extended art', borderless: 'Borderless', 'full-art': 'Full art',
  'retro-frame': 'Retro frame', textless: 'Textless', serialized: 'Serialized', promo: 'Promo',
}
export const titleCase = (s: string) => s.replace(/[-_]/g, ' ').replace(/\b\w/g, c => c.toUpperCase())

/** How a filter value reads on the page. */
export function valueLabel(facet: string, v: { value: string; label?: string | null }): string {
  switch (facet) {
    case 'game': return GAMES[v.value] ?? titleCase(v.value)
    case 'set': return v.label ? `${v.label} (${v.value})` : v.value
    case 'color': return COLORS[v.value] ?? v.value
    case 'treatment': return TREATMENTS[v.value] ?? titleCase(v.value)
    case 'source': return v.value === 'store' ? 'Store stock' : `CardBox: ${v.label ?? 'collection'}`
    case 'rarity': case 'finish': return titleCase(v.value)
    default: return v.value
  }
}

/** Values a rule may name even when nothing in stock has them yet. */
export const KNOWN: Record<string, string[]> = {
  game: ['magic-the-gathering', 'star-wars-unlimited'],
  rarity: ['common', 'uncommon', 'rare', 'mythic', 'special'],
  color: ['W', 'U', 'B', 'R', 'G', 'M', 'C'],
  type: ['Creature', 'Instant', 'Sorcery', 'Enchantment', 'Artifact', 'Planeswalker', 'Land', 'Battle', 'Legendary', 'Token'],
  finish: ['normal', 'foil', 'etched'],
  treatment: ['none', 'showcase', 'extended-art', 'borderless', 'full-art', 'retro-frame', 'textless', 'serialized', 'promo'],
  condition: ['NM', 'LP', 'MP', 'HP', 'DMG'],
}

export type Conditions = Record<string, string[]>

/** A rule as a short phrase: "Magic · Red · names A–L", or "Everything else" with no conditions. */
export function ruleText(conditions: Conditions, labels: Facets = {}): string {
  const parts: string[] = []
  for (const f of FACETS) {
    const values = conditions[f.key] ?? []
    if (values.length) parts.push(values.map(v => valueLabel(f.key, { value: v, label: labels[f.key]?.find(x => x.value === v)?.label })).join(' or '))
  }
  const [from, to] = [conditions.nameFrom?.[0], conditions.nameTo?.[0]]
  if (from || to) parts.push(`names ${from ?? 'A'}–${to ?? 'Z'}`)
  const [min, max] = [conditions.priceMin?.[0], conditions.priceMax?.[0]]
  if (min || max) parts.push(min && max ? `$${min}–$${max}` : min ? `$${min} and up` : `under $${max}`)
  return parts.length ? parts.join(' · ') : 'Everything else'
}

