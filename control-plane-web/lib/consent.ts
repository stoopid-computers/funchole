// Analytics consent, shared with the landing page: one first-party cookie on
// the parent domain, so a visitor is asked once for funchole.dev and
// app.funchole.dev. It only records the choice; nothing is tracked unless
// the value is "granted".

export type Consent = "granted" | "denied";

export const CONSENT_COOKIE = "fh_consent";
export const CONSENT_OPEN_EVENT = "fh-consent-open";

const SHARED_DOMAIN = /(^|\.)funchole\.dev$/;

function domainAttribute() {
  return SHARED_DOMAIN.test(window.location.hostname) ? "; domain=.funchole.dev" : "";
}

const listeners = new Set<() => void>();

// For useSyncExternalStore: lets React re-render when the choice changes.
export function subscribeConsent(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function readConsent(): Consent | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${CONSENT_COOKIE}=(granted|denied)`));
  return match ? (match[1] as Consent) : null;
}

export function writeConsent(value: Consent) {
  const secure = window.location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `${CONSENT_COOKIE}=${value}; max-age=31536000; path=/; SameSite=Lax${secure}${domainAttribute()}`;
  listeners.forEach((listener) => listener());
}

// Withdrawing consent also removes the Google Analytics cookies already set.
export function clearAnalyticsCookies() {
  const host = window.location.hostname;
  const names = document.cookie
    .split("; ")
    .map((cookie) => cookie.split("=")[0])
    .filter((name) => name === "_ga" || name.startsWith("_ga_"));
  for (const name of names) {
    for (const domain of ["", `; domain=${host}`, `; domain=.${host}`, "; domain=.funchole.dev"]) {
      document.cookie = `${name}=; max-age=0; path=/${domain}`;
    }
  }
}

export function openConsentSettings() {
  window.dispatchEvent(new Event(CONSENT_OPEN_EVENT));
}
