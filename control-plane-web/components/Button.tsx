import type { ButtonHTMLAttributes } from "react";
import { Button as UIButton, buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

// App-level button vocabulary on top of shadcn's Button. Primary is the
// landing page's white CTA; secondary is its dark "btn-dark"; the electric
// blue is reserved for focus and live states, never for buttons.
export type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";
export type ButtonSize = "md" | "sm" | "icon";

const VARIANT = {
  primary: "default",
  secondary: "secondary",
  danger: "destructive",
  ghost: "ghost",
} as const;

const SIZE = {
  md: "lg",
  sm: "default",
  icon: "icon",
} as const;

const SIZE_EXTRA: Record<ButtonSize, string> = {
  md: "px-3.5",
  sm: "px-3 text-[13px]",
  icon: "",
};

export function buttonClasses(variant: ButtonVariant = "secondary", size: ButtonSize = "md") {
  return cn(buttonVariants({ variant: VARIANT[variant], size: SIZE[size] }), SIZE_EXTRA[size], "active:scale-[0.97]");
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  asChild?: boolean;
}

export function Button({ variant = "secondary", size = "md", className, type = "button", asChild, ...props }: ButtonProps) {
  return (
    <UIButton
      type={asChild ? undefined : type}
      asChild={asChild}
      variant={VARIANT[variant]}
      size={SIZE[size]}
      className={cn(SIZE_EXTRA[size], "active:scale-[0.97]", className)}
      {...props}
    />
  );
}
