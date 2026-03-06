import { useState, useEffect, useCallback } from 'react'

/**
 * Generic hook for API calls with loading / error / data state.
 * Falls back to `fallbackData` when the API is unreachable (dev mode).
 *
 * @param {() => Promise<T>} fetcher   — service call, e.g. () => portalService.getAppointments()
 * @param {T}                fallback  — mock data used when API fails in DEV
 * @param {any[]}            deps      — extra dependency array for re-fetch
 */
export default function useApiData(fetcher, fallback = null, deps = []) {
  const [data, setData] = useState(fallback)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const result = await fetcher()
      setData(result)
    } catch (err) {
      console.warn('API fetch failed, using fallback:', err.message)
      setError(err)
      if (fallback !== null) setData(fallback)
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    load()
  }, [load])

  return { data, loading, error, refetch: load, setData }
}

