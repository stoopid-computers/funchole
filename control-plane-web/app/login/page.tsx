"use client";

import Script from "next/script";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Loader2 } from "lucide-react";
import { api } from "@/lib/api";
import { setToken } from "@/lib/auth";
import { cn } from "@/lib/utils";
import { Button } from "@/components/Button";
import { BrandMark } from "@/components/BrandMark";
import { FormError } from "@/components/FormError";
import { ThemeToggle } from "@/components/ThemeToggle";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { friendlyError } from "@/lib/errors";

const GOOGLE_CLIENT_ID = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  // "google" gets its own state (not just a boolean) because a brand-new
  // Google sign-in provisions a default gateway and database synchronously
  // before the request resolves - that can take noticeably longer than a
  // plain password login, so it earns its own reassuring loading copy.
  const [pendingMethod, setPendingMethod] = useState<"password" | "google" | null>(null);
  const pending = pendingMethod !== null;
  const [googleScriptLoaded, setGoogleScriptLoaded] = useState(false);
  const googleButtonRef = useRef<HTMLDivElement>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setPendingMethod("password");
    try {
      const token = await api.login(username, password);
      setToken(token.accessToken, token.expiresAt);
      router.replace(token.passwordChangeRequired ? "/profile?password=required" : "/");
    } catch (err) {
      setError(friendlyError(err, "Login failed"));
      setPendingMethod(null);
    }
  }

  const handleGoogleCredential = useCallback(
    async (response: { credential: string }) => {
      setError(null);
      setPendingMethod("google");
      try {
        const token = await api.loginWithGoogle(response.credential);
        setToken(token.accessToken, token.expiresAt);
        router.replace(token.passwordChangeRequired ? "/profile?password=required" : "/");
      } catch (err) {
        setError(friendlyError(err, "Google sign-in failed"));
        setPendingMethod(null);
      }
    },
    [router]
  );

  // Google's own button only renders once its script has loaded and a real
  // container element exists - both happen asynchronously and independently
  // (the script tag firing onLoad, React committing the ref), so this waits
  // on whichever finishes last rather than assuming an order.
  useEffect(() => {
    if (!GOOGLE_CLIENT_ID || !googleScriptLoaded || !googleButtonRef.current || !window.google) {
      return;
    }
    window.google.accounts.id.initialize({
      client_id: GOOGLE_CLIENT_ID,
      callback: handleGoogleCredential,
    });
    window.google.accounts.id.renderButton(googleButtonRef.current, {
      theme: document.documentElement.dataset.theme === "dark" ? "filled_black" : "outline",
      size: "large",
      width: 320,
      text: "signin_with",
    });
  }, [googleScriptLoaded, handleGoogleCredential]);

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4 py-10">
      <div className="absolute top-4 right-4">
        <ThemeToggle />
      </div>
      <div className="fh-reveal w-full max-w-md">
        <div className="flex justify-center">
          <BrandMark />
        </div>

        {/* The landing page's browser-window frame, on paper. */}
        <div className="sticker mt-8 overflow-hidden rounded-2xl bg-card">
          <div className="flex items-center gap-1.5 border-b-2 border-edge bg-muted px-3 py-2" aria-hidden="true">
            <span className="size-2.5 rounded-full border-[1.5px] border-edge bg-coral" />
            <span className="size-2.5 rounded-full border-[1.5px] border-edge bg-sun" />
            <span className="size-2.5 rounded-full border-[1.5px] border-edge bg-live" />
            <span className="ml-2 truncate rounded-full border-[1.5px] border-edge bg-card px-3 py-0.5 font-mono text-[11px] text-foreground">
              app.funchole.dev
            </span>
          </div>

          <div className="px-6 pt-6 pb-6 sm:px-7 sm:pb-7">
            <h1 className="display text-3xl text-foreground">
              {pendingMethod === "google" ? "Setting up your workspace" : "Sign in"}
            </h1>
            <p className="mt-2 text-sm text-muted-foreground">
              {pendingMethod === "google"
                ? "First time here? We're setting up your live address and database. This can take a few extra seconds."
                : "Continue with Google to get started. If you're new, this creates your account."}
            </p>

            {GOOGLE_CLIENT_ID && (
              <Script
                src="https://accounts.google.com/gsi/client"
                async
                defer
                onLoad={() => setGoogleScriptLoaded(true)}
              />
            )}

            {/* The Google button mount and the form stay mounted the whole
                time (just hidden) rather than being swapped out - unmounting
                googleButtonRef would destroy Google's injected button and it
                would never come back, since the render effect above only
                re-fires when the script/callback identity changes, not when
                this div remounts. */}
            {pendingMethod === "google" && (
              <div className="flex flex-col items-center gap-4 py-8 text-center">
                <Loader2 className="size-7 animate-spin text-brand" aria-hidden="true" />
                <p className="max-w-[26ch] text-sm text-muted-foreground">
                  Hang tight. You&apos;ll land in your workspace in a moment.
                </p>
              </div>
            )}

            {GOOGLE_CLIENT_ID && (
              <>
                <div className={cn("mt-6 mb-5 flex justify-center", pendingMethod === "google" && "hidden")}>
                  <div className="overflow-hidden rounded-lg" ref={googleButtonRef} />
                </div>
                <div
                  className={cn(
                    "mb-5 flex items-center gap-3 text-xs text-subtle",
                    pendingMethod === "google" && "hidden"
                  )}
                >
                  <Separator className="flex-1" />
                  or sign in with a password
                  <Separator className="flex-1" />
                </div>
              </>
            )}

            <form
              className={cn("mt-6 flex flex-col gap-4", GOOGLE_CLIENT_ID && "mt-0", pendingMethod === "google" && "hidden")}
              onSubmit={handleSubmit}
            >
              <div className="flex flex-col gap-2">
                <Label htmlFor="login-username">Username</Label>
                <Input
                  id="login-username"
                  type="text"
                  required
                  autoComplete="username"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                />
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor="login-password">Password</Label>
                <Input
                  id="login-password"
                  type="password"
                  required
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </div>

              {error && <FormError>{error}</FormError>}

              <Button type="submit" variant="primary" size="lg" disabled={pending} className="mt-2 w-full">
                {pendingMethod === "password" ? "Signing in…" : "Sign in"}
              </Button>
            </form>
          </div>
        </div>

        <p className="mt-8 flex items-center justify-center gap-2 text-xs text-muted-foreground">
          <span className="live-dot text-success" aria-hidden="true" />
          Licensed under FSL 1.1
        </p>
      </div>
    </div>
  );
}
