import { useEffect } from 'react';
import { fetchSavings } from '../lib/api';
import { useAppStore } from '../lib/store';

export function useSavingsPoller() {
  const setSavings = useAppStore((s) => s.setSavings);

  useEffect(() => {
    let isMounted = true;

    const refresh = () => {
      if (!isMounted) return;

      fetchSavings()
        .then((data) => {
          if (!isMounted) return;
          setSavings(data);
        })
        .catch((err) => {
          if (isMounted && err.name !== 'AbortError') {
            console.warn('[App] Failed to fetch savings', err);
          }
        });
    };

    refresh();
    const interval = setInterval(refresh, 30000);

    return () => {
      isMounted = false;
      clearInterval(interval);
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
}
