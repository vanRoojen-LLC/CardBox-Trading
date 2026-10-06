/** CardBox Trading plan states, as the platform owner sets them on Admin. */
export const PLANS = ['trial', 'active', 'past_due', 'canceled'] as const

export const planLabel = (plan: string) => plan.replace('_', ' ')

/** Canceling locks the store out of trades straight away, so it asks first. */
export function confirmPlan(store: string, plan: string) {
  return plan !== 'canceled' || confirm(`Cancel ${store}'s CardBox Trading plan? Everyone at ${store} loses trade-ins, history and inventory until it's turned back on.`)
}
