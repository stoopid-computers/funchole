import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "cn";
import { Slot } from "radix-ui";

// Sticker buttons (default, sun, card) are the landing page's pressed-button
// look: 2px edge, hard shadow, 3px press. Everything else is quiet, for rows
// of data and dense toolbars.
const STICKER =
  "border-edge shadow-press hover:-translate-y-px hover:shadow-press-hover active:translate-y-[3px] active:shadow-press-down";

const buttonVariants = cva(
  "cursor-pointer group/button inline-flex shrink-0 items-center justify-center rounded-lg border-2 border-transparent text-sm font-semibold whitespace-nowrap transition-[transform,box-shadow,background-color] duration-100 select-none disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: `${STICKER} min-h-10 bg-primary text-primary-foreground`,
        sun: `${STICKER} min-h-10 bg-sun text-[var(--fh-on-sun)]`,
        card: `${STICKER} min-h-10 bg-card text-card-foreground`,
        outline:
          "border border-input bg-card text-foreground hover:bg-muted aria-expanded:bg-muted",
        secondary:
          "border border-input bg-card text-foreground hover:bg-muted aria-expanded:bg-muted",
        ghost: "text-foreground hover:bg-ink/5 aria-expanded:bg-ink/5",
        destructive:
          "border border-danger/40 bg-coral-soft text-danger hover:bg-coral-soft/70",
        link: "text-brand underline-offset-4 hover:underline",
      },
      size: {
        default:
          "h-9 gap-1.5 px-3 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
        xs: "h-6 gap-1 rounded-md px-2 text-xs has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3",
        sm: "h-8 gap-1 rounded-md px-2.5 text-[13px] has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3.5",
        lg: "h-11 gap-2 px-5 text-base has-data-[icon=inline-end]:pr-3.5 has-data-[icon=inline-start]:pl-3.5",
        icon: "size-9",
        "icon-xs": "size-6 rounded-md [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-8 rounded-md",
        "icon-lg": "size-11",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);

function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean;
  }) {
  const Comp = asChild ? Slot.Root : "button";

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  );
}

export { Button, buttonVariants };
