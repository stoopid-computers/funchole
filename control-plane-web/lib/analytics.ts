// Dashboard analytics. Everything here is a no-op unless the Google tag is
// loaded (cloud builds only, see components/GoogleAnalytics.tsx), so
// self-hosted installs never send anything.

type GtagParams = Record<string, string | number | boolean>;
type Gtag = (command: "event", name: string, params?: GtagParams) => void;

// Path segments that are fixed resource/action words. Anything else (UUIDs,
// env var keys, user-chosen names) is dropped so no identifiers or user data
// ever reach the analytics event.
const KNOWN_SEGMENTS = new Set([
  "adopt", "api-keys", "archive", "auth", "config", "custom-domains", "databases",
  "deploy", "domains", "env", "environments", "files", "flows", "functions",
  "gateways", "google", "invocations", "invoke", "me", "password", "profile",
  "reveal", "secrets", "source", "steps", "token", "verification", "versions",
]);

const PAGE_SEGMENTS = new Set([...KNOWN_SEGMENTS, "account", "activity", "login", "package", "settings"]);
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
// Elements whose text is a whole card or row (names, paths, ...) rather than a label.
const CONTENT_ELEMENTS = "p, div, code, pre, li, table, h1, h2, h3, h4, h5, h6";

// "/functions/<uuid>/versions/<uuid>" -> "/functions/:id/versions/:id", so every
// page groups together in reports and no identifiers leave the browser.
export function normalizePath(path: string) {
  const segments = path
    .split(/[?#]/)[0]
    .split("/")
    .filter(Boolean)
    .map((segment) => (PAGE_SEGMENTS.has(segment) ? segment : ":id"));
  return `/${segments.join("/")}`;
}

export function slug(text: string, max = 30) {
  return text
    .toLowerCase()
    .replace(UUID, "")
    .replace(/["'`][^"'`]*["'`]/g, "")
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, max);
}

// Safe-by-default click label: an explicit data-track wins; otherwise the
// element's own text (or aria-label for icon buttons). Cards and rows that
// contain user data are labelled "card" instead of leaking their text.
export function clickLabel(element: HTMLElement) {
  if (element.dataset.track) return slug(element.dataset.track);
  if (element.querySelector(CONTENT_ELEMENTS)) return "card";
  return slug(element.textContent || element.getAttribute("aria-label") || element.title || "") || "icon";
}

export function track(name: string, params?: GtagParams) {
  if (typeof window === "undefined") return;
  (window as unknown as { gtag?: Gtag }).gtag?.("event", name.slice(0, 40), params);
}

// Funnel steps (e.g. onboarding) should count once per browser session, not once per render or revisit.
export function trackOnce(name: string, params?: GtagParams) {
  try {
    const key = `fh_tracked_${name}`;
    if (window.sessionStorage.getItem(key)) return;
    window.sessionStorage.setItem(key, "1");
  } catch {
    // Storage blocked: fall through and send, a duplicate beats a lost step.
  }
  track(name, params);
}

// One event per write call, e.g. POST /functions/<id>/versions/<id>/deploy
// becomes "post_functions_versions_deploy" with result "success" or "error".
export function trackApiCall(method: string, path: string, result: "success" | "error", status = 200) {
  const resource = path
    .split("?")[0]
    .split("/")
    .filter((segment) => KNOWN_SEGMENTS.has(segment))
    .join("_")
    .replace(/-/g, "_");
  track(`${method.toLowerCase()}_${resource}`, { result, status });
}
