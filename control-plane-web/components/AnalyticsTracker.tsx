"use client";

import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { clickLabel, normalizePath, track } from "@/lib/analytics";

const CLICKABLE = 'button, a[href], [role="button"], [role="tab"], [role="menuitem"]';

// Frontend analytics for the hosted dashboard: one page_view per route change
// and one event per click, so reports show where people go and where they stop.
export function AnalyticsTracker() {
  const pathname = usePathname();

  useEffect(() => {
    track("page_view", { page_location: `${window.location.origin}${normalizePath(pathname)}` });
  }, [pathname]);

  useEffect(() => {
    function onClick(event: MouseEvent) {
      const element = (event.target as Element | null)?.closest<HTMLElement>(CLICKABLE);
      if (!element || (element as HTMLButtonElement).disabled) return;

      const page = normalizePath(window.location.pathname);
      if (element instanceof HTMLAnchorElement) {
        const url = new URL(element.href, window.location.href);
        if (url.origin === window.location.origin) track("nav_click", { to: normalizePath(url.pathname), page });
        else if (url.protocol.startsWith("http")) track("outbound_click", { host: url.hostname, page });
        return;
      }

      const label = clickLabel(element);
      const section = page.split("/")[1] || "home";
      track(`click_${section}_${label}`, { label, page });
    }

    document.addEventListener("click", onClick, true);
    return () => document.removeEventListener("click", onClick, true);
  }, []);

  return null;
}
