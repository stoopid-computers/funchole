import { ApiError } from "@/lib/api";

const UUID = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;

// Backend messages are written for developers: UUIDs, internal terms, raw
// build output. Until the API returns a stable error `code`, this is the one
// place that turns them into something a person can act on.
export function friendlyError(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback;

  const { status, message, details } = error;
  if (status === 0) return "Can't reach FuncHole. Check your connection and try again.";
  if (status === 401) return "Your session ended. Please sign in again.";
  if (status === 403 && /package allows up to/i.test(message)) {
    return `You've reached your plan's limit. ${message.replace(/package/gi, "plan").replace(UUID, "").trim()}`;
  }
  if (status === 403 && /only the platform admin/i.test(message)) {
    return "Only the FuncHole team can add domains on this plan. You can connect your own domain to your live address instead.";
  }
  if (status === 404) return "We couldn't find that. It may have been deleted.";
  if (status === 409 && /only ready function versions/i.test(message)) {
    return "This update isn't published yet. Publish it first, then try again.";
  }
  if (status === 422 && details.length > 0) return details.join(". ");
  if (status >= 500) return "Something went wrong on our side. Please try again in a moment.";

  const cleaned = message.replace(UUID, "").replace(/\s{2,}/g, " ").trim();
  return cleaned || fallback;
}
