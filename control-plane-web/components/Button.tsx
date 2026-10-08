import type { ButtonHTMLAttributes } from "react";
import { Button as UIButton, buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

// App-level button vocabulary on top of shadcn's Button. primary, sun and card
// are the landing page's pressed "sticker" buttons, used for the one main
// action on a screen; secondary, ghost and danger are quiet, for rows and
// toolbars.
export type ButtonVariant = "primary" | "secondary" | "danger" | "ghost" | "sun" | "card";
export type ButtonSize = "md" | "sm" | "lg" | "icon";

const VARIANT = {
  primary: "default",
  secondary: "secondary",
  danger: "destructive",
  ghost: "ghost",
  sun: "sun",
  card: "card",
} as const;

const SIZE = {
  md: "default",
  sm: "sm",
  lg: "lg",
  icon: "icon",
} as const;

const SIZE_EXTRA: Record<ButtonSize, string> = {
  md: "",
  sm: "",
  lg: "",
  icon: "",
};

export function buttonClasses(variant: ButtonVariant = "secondary", size: ButtonSize = "md") {
  return cn(buttonVariants({ variant: VARIANT[variant], size: SIZE[size] }), SIZE_EXTRA[size]);
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
      className={cn(SIZE_EXTRA[size], className)}
      {...props}
    />
  );
}
