// Class strings for native form controls, matching shadcn's Input/Label so
// hand-written <input>/<label> elements look identical to the ui/ components.
// Quiet by design (1px control border, 40px tall on touch, 36px on desktop):
// forms are data entry, not a place for sticker shadows.
export const inputClass =
  "h-10 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm text-foreground transition-colors placeholder:text-subtle focus-visible:border-ring disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground aria-invalid:border-destructive md:h-9";

export const labelClass = "text-[13px] font-semibold text-muted-strong";

export const fieldClass = "flex flex-col gap-2";
