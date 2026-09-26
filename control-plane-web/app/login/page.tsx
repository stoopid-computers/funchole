"use client";

import Script from "next/script";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { setToken } from "@/lib/auth";
import { Button } from "@/components/Button";
import { BrandMark } from "@/components/BrandMark";
import { FormError } from "@/components/FormError";
import { HeroField } from "@/components/HeroField";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";

const GOOGLE_CLIENT_ID = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [googleScriptLoaded, setGoogleScriptLoaded] = useState(false);
  const googleButtonRef = useRef<HTMLDivElement>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setPending(true);
    try {
      const token = await api.login(username, password);
      setToken(token.accessToken, token.expiresAt);
      router.replace("/");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Login failed");
      setPending(false);
    }
  }

  const handleGoogleCredential = useCallback(
    async (response: { credential: string }) => {
      setError(null);
      setPending(true);
      try {
        const token = await api.loginWithGoogle(response.credential);
        setToken(token.accessToken, token.expiresAt);
        router.replace("/");
      } catch (err) {
        setError(err instanceof ApiError ? err.message : "Google sign-in failed");
        setPending(false);
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
      theme: "filled_black",
      size: "large",
      width: 320,
      text: "signin_with",
    });
  }, [googleScriptLoaded, handleGoogleCredential]);

  return (
    <div className="hero-stage relative isolate flex min-h-screen items-center justify-center overflow-hidden px-4 py-10">
      {/* The landing hero: a blue bloom plus the interactive dot field. */}
      <HeroField className="-z-10" />
      <div className="fh-reveal w-full max-w-sm">
        <div className="flex justify-center">
          <BrandMark />
        </div>
        {/* The glow lives on a wrapper: Card's own ring is also a box-shadow and would override it. */}
        <div className="neon-card mt-8 rounded-[1.35rem]">
        <Card className="gap-0 rounded-[inherit] bg-transparent py-0 ring-0">
          <CardHeader className="px-6 pt-6 pb-0 sm:px-7 sm:pt-7">
            <CardTitle className="display text-2xl">Sign in</CardTitle>
            <CardDescription className="mt-1 text-white/70">Access your FuncHole workspace.</CardDescription>
          </CardHeader>
          <CardContent className="px-6 pt-6 pb-6 sm:px-7 sm:pb-7">
            {GOOGLE_CLIENT_ID && (
              <>
                <Script
                  src="https://accounts.google.com/gsi/client"
                  async
                  defer
                  onLoad={() => setGoogleScriptLoaded(true)}
                />
                <div className="mb-5 flex justify-center" ref={googleButtonRef} />
                <div className="mb-5 flex items-center gap-3 text-xs text-subtle">
                  <Separator className="flex-1" />
                  or sign in with a password
                  <Separator className="flex-1" />
                </div>
              </>
            )}

            <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
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

              <Button type="submit" variant="primary" disabled={pending} className="mt-2 h-10 w-full">
                {pending ? "Signing in…" : "Sign in"}
              </Button>
            </form>
          </CardContent>
        </Card>
        </div>
        <p className="mt-8 flex items-center justify-center gap-2 font-mono text-[11px] text-white/60">
          <span className="live-dot text-success" aria-hidden="true" />
          Open source · Apache 2.0
        </p>
      </div>
    </div>
  );
}
