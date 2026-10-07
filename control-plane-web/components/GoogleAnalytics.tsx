"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import { AnalyticsTracker } from "@/components/AnalyticsTracker";
import { Button } from "@/components/Button";
import {
  clearAnalyticsCookies,
  CONSENT_OPEN_EVENT,
  readConsent,
  subscribeConsent,
  writeConsent,
  type Consent,
} from "@/lib/consent";

// Cloud-only analytics. NEXT_PUBLIC_GA_MEASUREMENT_ID is inlined at build time
// (see the web build ARG in the root Dockerfile) and is empty by default, so
// self-hosted installs render nothing: no banner, no script, no tracking.
const MEASUREMENT_ID = process.env.NEXT_PUBLIC_GA_MEASUREMENT_ID;

type AnalyticsWindow = Window & { dataLayer?: unknown[]; gtag?: (...args: unknown[]) => void } & Record<string, unknown>;

// Google's script is only added after the visitor accepts. send_page_view is
// off: <AnalyticsTracker /> sends page_view itself, with identifiers stripped.
function startAnalytics(id: string) {
  const w = window as unknown as AnalyticsWindow;
  w[`ga-disable-${id}`] = false;
  if (w.gtag) return;

  w.dataLayer = w.dataLayer ?? [];
  w.gtag = function () {
    // gtag.js only understands the `arguments` object, not a rest-args array.
    // eslint-disable-next-line prefer-rest-params
    w.dataLayer!.push(arguments);
  };
  w.gtag("js", new Date());
  w.gtag("config", id, { send_page_view: false });

  const script = document.createElement("script");
  script.async = true;
  script.src = `https://www.googletagmanager.com/gtag/js?id=${id}`;
  document.head.appendChild(script);
}

function stopAnalytics(id: string) {
  (window as unknown as AnalyticsWindow)[`ga-disable-${id}`] = true;
  clearAnalyticsCookies();
}

function Analytics({ id }: { id: string }) {
  // "unknown" only on the server, so the banner never flashes into the static HTML.
  const consent = useSyncExternalStore<Consent | null | "unknown">(subscribeConsent, readConsent, () => "unknown");
  const [reopened, setReopened] = useState(false);
  const bannerOpen = consent === null || reopened;

  useEffect(() => {
    if (consent === "granted") startAnalytics(id);
  }, [consent, id]);

  useEffect(() => {
    const reopen = () => setReopened(true);
    window.addEventListener(CONSENT_OPEN_EVENT, reopen);
    return () => window.removeEventListener(CONSENT_OPEN_EVENT, reopen);
  }, []);

  function choose(value: Consent) {
    if (value === "denied") stopAnalytics(id);
    writeConsent(value);
    setReopened(false);
  }

  return (
    <>
      {consent === "granted" && <AnalyticsTracker />}
      {bannerOpen && (
        <div
          role="dialog"
          aria-label="Cookie consent"
          className="fixed inset-x-4 bottom-4 z-50 rounded-xl border border-border-strong bg-background p-4 shadow-2xl sm:left-auto sm:max-w-sm"
        >
          <p className="text-sm text-foreground">Help us improve FuncHole?</p>
          <p className="mt-1.5 text-[13px] leading-relaxed text-muted-foreground">
            We use Google Analytics cookies to see which pages are used and where people get stuck. Nothing is
            loaded unless you accept.{" "}
            <a
              href="https://funchole.dev/cookies/"
              target="_blank"
              rel="noreferrer"
              className="underline underline-offset-2 hover:text-foreground"
            >
              Cookie Policy
            </a>
          </p>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <Button variant="secondary" size="sm" onClick={() => choose("denied")}>
              Decline
            </Button>
            <Button variant="primary" size="sm" onClick={() => choose("granted")}>
              Accept
            </Button>
          </div>
        </div>
      )}
    </>
  );
}

export function GoogleAnalytics() {
  return MEASUREMENT_ID ? <Analytics id={MEASUREMENT_ID} /> : null;
}
