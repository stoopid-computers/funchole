import Script from "next/script";
import { AnalyticsTracker } from "@/components/AnalyticsTracker";

// Cloud-only analytics. NEXT_PUBLIC_GA_MEASUREMENT_ID is inlined at build time
// (see the web build ARG in the root Dockerfile) and is empty by default, so
// self-hosted installs render nothing and never send usage to anyone.
const MEASUREMENT_ID = process.env.NEXT_PUBLIC_GA_MEASUREMENT_ID;

export function GoogleAnalytics() {
  if (!MEASUREMENT_ID) return null;

  return (
    <>
      <Script src={`https://www.googletagmanager.com/gtag/js?id=${MEASUREMENT_ID}`} strategy="afterInteractive" />
      <AnalyticsTracker />
    </>
  );
}
