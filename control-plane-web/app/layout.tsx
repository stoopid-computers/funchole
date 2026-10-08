import type { Metadata } from "next";
import { Bricolage_Grotesque, Caveat, Figtree } from "next/font/google";
import { GoogleAnalytics } from "@/components/GoogleAnalytics";
import { TooltipProvider } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import "./globals.css";

// The landing page's type system: Bricolage Grotesque for headings, Figtree
// for text, Caveat only for the occasional hand-written note.
const display = Bricolage_Grotesque({ subsets: ["latin"], axes: ["opsz"], variable: "--font-display-src" });
const body = Figtree({ subsets: ["latin"], variable: "--font-body-src" });
const hand = Caveat({ subsets: ["latin"], variable: "--font-hand-src", preload: false });

export const metadata: Metadata = {
  title: "FuncHole Workspace",
  description: "Manage FuncHole workspaces, live URLs, agent access, and data connections",
};

// Runs before first paint so a saved dark choice never flashes light. Light is
// the default; the choice lives in localStorage ("fh_theme").
const THEME_SCRIPT = `try{var t=localStorage.getItem("fh_theme");document.documentElement.dataset.theme=t==="dark"?"dark":"light"}catch(e){}`;

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      data-theme="light"
      suppressHydrationWarning
      className={cn("h-full antialiased", display.variable, body.variable, hand.variable)}
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_SCRIPT }} />
      </head>
      <body className="flex min-h-full flex-col">
        <TooltipProvider>{children}</TooltipProvider>
        <GoogleAnalytics />
      </body>
    </html>
  );
}
