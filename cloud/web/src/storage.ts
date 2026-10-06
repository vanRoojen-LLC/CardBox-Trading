/** A storage spot inside a location: a tier label the store chose ("Shelf") and a name ("A"). */
export interface Spot {
  id: string; locationId: string; parentId: string | null; label: string; name: string; cards: number
  /** Most cards it holds, counting spots inside it; null or missing for no limit. */
  capacity?: number | null
}

export interface PathPart { label: string; name: string }

/** Spots of one location in tree order, each with its depth, for indented lists and pickers. */
export function flatTree(spots: Spot[], locationId: string): (Spot & { depth: number })[] {
  const children = new Map<string | null, Spot[]>()
  for (const s of spots) if (s.locationId === locationId) children.set(s.parentId, [...(children.get(s.parentId) ?? []), s])
  const out: (Spot & { depth: number })[] = []
  const walk = (parent: string | null, depth: number) => {
    for (const s of children.get(parent) ?? []) { out.push({ ...s, depth }); walk(s.id, depth + 1) }
  }
  walk(null, 0)
  return out
}

export const spotText = (s: PathPart) => `${s.label} ${s.name}`

export function pathText(path: PathPart[]): string {
  return path.map(spotText).join(' › ')
}

/** Where a card sits, as just the spot names ("Bulk › Card Display › MTG Black"); stores name tiers in the value when they want them. */
export function placeText(path: PathPart[]): string {
  return path.map(p => p.name).join(' › ')
}

/** Cards in each spot counting every spot inside it, from counts of cards sitting in each spot itself. */
export function subtreeCounts(spots: Spot[], direct: Map<string, number>): Map<string, number> {
  const parent = new Map(spots.map(s => [s.id, s.parentId]))
  const out = new Map<string, number>()
  for (const [id, n] of direct) for (let at: string | null | undefined = id; at; at = parent.get(at)) out.set(at, (out.get(at) ?? 0) + n)
  return out
}

/** Path of a spot from the top of its location. */
export function pathOf(spots: Spot[], id: string): PathPart[] {
  const byId = new Map(spots.map(s => [s.id, s]))
  const path: PathPart[] = []
  for (let at = byId.get(id); at; at = at.parentId ? byId.get(at.parentId) : undefined) path.unshift(at)
  return path
}

/**
 * Turns what an owner typed into the names to create: commas or new lines separate names, and a range
 * like "1-20" or "A-F" expands to every name in it. "Top, Bottom" stays two names.
 */
export function expandNames(text: string): string[] {
  const names: string[] = []
  for (const part of text.split(/[,\n]/).map(p => p.trim()).filter(Boolean)) {
    const numbers = part.match(/^(\d+)\s*-\s*(\d+)$/)
    const letters = part.match(/^([A-Za-z])\s*-\s*([A-Za-z])$/)
    if (numbers && Number(numbers[2]) >= Number(numbers[1]) && Number(numbers[2]) - Number(numbers[1]) < 200) {
      // Keep zero padding when the range was typed with it ("01-12").
      const width = numbers[1].startsWith('0') ? numbers[1].length : 0
      for (let n = Number(numbers[1]); n <= Number(numbers[2]); n++) names.push(String(n).padStart(width, '0'))
    } else if (letters && letters[1].charCodeAt(0) <= letters[2].charCodeAt(0)) {
      for (let c = letters[1].charCodeAt(0); c <= letters[2].charCodeAt(0); c++) names.push(String.fromCharCode(c))
    } else names.push(part)
  }
  return [...new Set(names)]
}
