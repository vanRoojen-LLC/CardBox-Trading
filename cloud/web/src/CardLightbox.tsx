import { useCallback, useEffect, useRef, useState, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from 'react'

/**
 * Scryfall serves every size from the same path, so the small thumbnail URL also names the large JPG and full PNG.
 * Any other image (a CardBox scan) is shown as it is.
 */
function scryfallSizes(small: string) {
  if (!small.includes('/small/')) return { large: small, png: small }
  return {
    large: small.replace('/small/', '/large/'),
    png: small.replace('/small/', '/png/').replace(/\.jpg(\?|$)/, '.png$1'),
  }
}

const MAX_ZOOM = 8
const LOUPE_SIZE = 180
const LOUPE_POWER = 3
/** How far a finger has to slide sideways, unzoomed, to move to the next image. */
const SWIPE = 60

interface View { scale: number; x: number; y: number }
interface Loupe { left: number; top: number; bgSize: string; bgPos: string }
const FIT: View = { scale: 1, x: 0, y: 0 }

/** One image the lightbox can show; several are stepped through with the arrows. */
export interface Slide { image: string; caption: string }

/**
 * Full-size card viewer: fits the window by default, zooms with the wheel, pinch, double-click or the buttons,
 * pans by dragging, and has a loupe mode for checking condition. With several images, the side arrows, the arrow
 * keys or a swipe move between them. Esc or a click outside the card closes it.
 */
export default function CardLightbox({ slides, start = 0, onClose }: { slides: Slide[]; start?: number; onClose: () => void }) {
  const [index, setIndex] = useState(Math.min(Math.max(0, start), slides.length - 1))
  const { image, caption } = slides[index]
  const many = slides.length > 1
  const sizes = scryfallSizes(image)
  /** The full-resolution PNG once it has loaded; until then the large JPG shows. */
  const [loaded, setLoaded] = useState<string | null>(null)
  const src = loaded === sizes.png ? sizes.png : sizes.large
  const [view, setView] = useState<View>(FIT)
  const [loupeMode, setLoupeMode] = useState(false)
  const [loupe, setLoupe] = useState<Loupe | null>(null)
  const stageRef = useRef<HTMLDivElement>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const pointers = useRef(new Map<number, { x: number; y: number }>())
  const dragged = useRef(false)
  const swipe = useRef<{ x: number; y: number } | null>(null)
  const closeHandler = useRef(onClose)
  useEffect(() => { closeHandler.current = onClose }, [onClose])

  /** Moves `by` images along, wrapping round at the ends; the new image starts fitted to the window. */
  const step = useCallback((by: number) => {
    setIndex(i => (i + by + slides.length) % slides.length)
    setView(FIT)
    setLoupe(null)
  }, [slides.length])

  // Show the large JPG at once, then swap in the full-resolution PNG when it arrives.
  useEffect(() => {
    const png = new Image()
    png.onload = () => setLoaded(sizes.png)
    png.src = sizes.png
    return () => { png.onload = null }
  }, [sizes.png])

  /** Keeps the card's edges from being dragged past the stage edges. */
  const clamp = useCallback((v: View): View => {
    const stage = stageRef.current, img = imgRef.current
    if (!stage || !img || v.scale <= 1) return FIT
    const maxX = Math.max(0, (img.offsetWidth * v.scale - stage.clientWidth) / 2)
    const maxY = Math.max(0, (img.offsetHeight * v.scale - stage.clientHeight) / 2)
    return { scale: v.scale, x: Math.min(maxX, Math.max(-maxX, v.x)), y: Math.min(maxY, Math.max(-maxY, v.y)) }
  }, [])

  /** Zooms by `factor`, keeping the point under (clientX, clientY) still; defaults to the stage centre. */
  const zoomAt = useCallback((factor: number, clientX?: number, clientY?: number) => {
    const rect = stageRef.current?.getBoundingClientRect()
    if (!rect) return
    const cx = clientX === undefined ? 0 : clientX - (rect.left + rect.width / 2)
    const cy = clientY === undefined ? 0 : clientY - (rect.top + rect.height / 2)
    setView(v => {
      const scale = Math.min(MAX_ZOOM, Math.max(1, v.scale * factor))
      const k = scale / v.scale
      return clamp({ scale, x: cx - k * (cx - v.x), y: cy - k * (cy - v.y) })
    })
  }, [clamp])

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeRef.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeHandler.current()
      else if (e.key === '+' || e.key === '=') zoomAt(1.5)
      else if (e.key === '-') zoomAt(1 / 1.5)
      else if (e.key === '0') setView(FIT)
      else if (e.key === 'ArrowLeft') step(-1)
      else if (e.key === 'ArrowRight') step(1)
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = overflow
      previous?.focus()
    }
  }, [zoomAt, step])

  // React's onWheel is passive, so the page would scroll too; listen directly to cancel it.
  useEffect(() => {
    const stage = stageRef.current
    if (!stage) return
    const onWheel = (e: WheelEvent) => { e.preventDefault(); zoomAt(Math.exp(-e.deltaY * 0.0015), e.clientX, e.clientY) }
    stage.addEventListener('wheel', onWheel, { passive: false })
    return () => stage.removeEventListener('wheel', onWheel)
  }, [zoomAt])

  function moveLoupe(e: ReactPointerEvent) {
    const r = imgRef.current?.getBoundingClientRect()
    if (!r) return
    const fx = (e.clientX - r.left) / r.width, fy = (e.clientY - r.top) / r.height
    if (fx < 0 || fx > 1 || fy < 0 || fy > 1) { setLoupe(null); return }
    const w = r.width * LOUPE_POWER, h = r.height * LOUPE_POWER
    // On touch, lift the loupe above the finger so it isn't hidden under it.
    const lift = e.pointerType === 'touch' ? LOUPE_SIZE * 0.75 : 0
    setLoupe({
      left: e.clientX - LOUPE_SIZE / 2,
      top: e.clientY - LOUPE_SIZE / 2 - lift,
      bgSize: `${w}px ${h}px`,
      bgPos: `${LOUPE_SIZE / 2 - fx * w}px ${LOUPE_SIZE / 2 - fy * h}px`,
    })
  }

  function onPointerDown(e: ReactPointerEvent<HTMLDivElement>) {
    if (e.target !== imgRef.current) return
    try { e.currentTarget.setPointerCapture(e.pointerId) } catch { /* pointer already gone */ }
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    dragged.current = false
    swipe.current = pointers.current.size === 1 ? { x: e.clientX, y: e.clientY } : null
    if (loupeMode) moveLoupe(e)
  }

  function onPointerMove(e: ReactPointerEvent<HTMLDivElement>) {
    const last = pointers.current.get(e.pointerId)
    if (loupeMode) {
      if (last || e.pointerType === 'mouse') moveLoupe(e)
      return
    }
    if (!last) return
    const now = { x: e.clientX, y: e.clientY }
    const others = [...pointers.current].filter(([id]) => id !== e.pointerId).map(([, p]) => p)
    pointers.current.set(e.pointerId, now)
    if (Math.hypot(now.x - last.x, now.y - last.y) > 2) dragged.current = true
    if (others.length === 1) {
      // Pinch: zoom by how much the finger gap changed, around the midpoint of the two fingers.
      const other = others[0]
      const before = Math.hypot(last.x - other.x, last.y - other.y)
      const after = Math.hypot(now.x - other.x, now.y - other.y)
      if (before > 0) zoomAt(after / before, (now.x + other.x) / 2, (now.y + other.y) / 2)
    } else if (others.length === 0) {
      setView(v => clamp({ ...v, x: v.x + now.x - last.x, y: v.y + now.y - last.y }))
    }
  }

  function onPointerUp(e: ReactPointerEvent<HTMLDivElement>) {
    const from = swipe.current
    swipe.current = null
    // One finger sliding sideways across an unzoomed card turns to the next image.
    if (from && many && !loupeMode && view.scale <= 1 && pointers.current.size === 1 && e.type === 'pointerup') {
      const dx = e.clientX - from.x, dy = e.clientY - from.y
      if (Math.abs(dx) > SWIPE && Math.abs(dx) > Math.abs(dy)) { dragged.current = true; step(dx < 0 ? 1 : -1) }
    }
    pointers.current.delete(e.pointerId)
    if (loupeMode && e.pointerType !== 'mouse') setLoupe(null)
  }

  function onBackdropClick(e: ReactMouseEvent) {
    if (dragged.current) { dragged.current = false; return }
    const target = e.target as HTMLElement
    if (target === imgRef.current || target.closest('.lb-toolbar, .lb-nav')) return
    onClose()
  }

  function onDoubleClick(e: ReactMouseEvent) {
    if (e.target !== imgRef.current || loupeMode) return
    if (view.scale > 1) setView(FIT)
    else zoomAt(2.5, e.clientX, e.clientY)
  }

  const percent = Math.round(view.scale * 100)
  return (
    <div className="lightbox" role="dialog" aria-modal="true" aria-label={caption} onClick={onBackdropClick}>
      <div className="lb-toolbar">
        <button type="button" onClick={() => zoomAt(1 / 1.5)} disabled={view.scale <= 1} aria-label="Zoom out">−</button>
        <button type="button" onClick={() => setView(FIT)} aria-label="Fit to window">{view.scale > 1 ? `${percent}%` : 'Fit'}</button>
        <button type="button" onClick={() => zoomAt(1.5)} disabled={view.scale >= MAX_ZOOM} aria-label="Zoom in">+</button>
        <button type="button" className={loupeMode ? 'on' : ''} aria-pressed={loupeMode}
                onClick={() => { setLoupeMode(m => !m); setLoupe(null) }}>Loupe</button>
        <button type="button" ref={closeRef} onClick={onClose} aria-label="Close">✕</button>
      </div>
      <div ref={stageRef} className={`lb-stage${loupeMode ? ' loupe-mode' : view.scale > 1 ? ' zoomed' : ''}`}
           onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}
           onPointerLeave={() => { if (loupeMode) setLoupe(null) }} onDoubleClick={onDoubleClick}>
        <img ref={imgRef} src={src} alt={caption} draggable={false}
             style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})` }} />
      </div>
      {many && <>
        <button type="button" className="lb-nav prev" onClick={() => step(-1)} aria-label="Previous image">‹</button>
        <button type="button" className="lb-nav next" onClick={() => step(1)} aria-label="Next image">›</button>
      </>}
      {loupe && <div className="loupe" aria-hidden="true" style={{
        left: loupe.left, top: loupe.top, width: LOUPE_SIZE, height: LOUPE_SIZE,
        backgroundImage: `url("${src}")`, backgroundSize: loupe.bgSize, backgroundPosition: loupe.bgPos,
      }} />}
      <p className="lb-caption">{caption}{many && <span className="lb-count"> · {index + 1} of {slides.length}</span>}</p>
    </div>
  )
}
