import { FormError } from "@/components/FormError";

// Placeholder for a detail page whose data hasn't arrived. If the load failed
// it shows why instead of "Loading…" forever.
export function PageLoading({ error }: { error: string | null }) {
  if (error) return <FormError>{error}</FormError>;
  return <p className="text-sm text-muted-foreground">Loading…</p>;
}
