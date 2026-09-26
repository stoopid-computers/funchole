"use client";

import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import type { ProfileResponse } from "@/lib/types";
import { Button } from "@/components/Button";
import { CreatePanel } from "@/components/CreatePanel";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { UserIcon } from "@/components/icons";

export default function ProfilePage() {
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getProfile()
      .then((data) => {
        if (cancelled) return;
        setProfile(data);
        setFullName(data.fullName ?? "");
      })
      .catch(() => {
        if (!cancelled) setError("Failed to load profile");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setMessage(null);
    setBusy(true);
    try {
      const updated = await api.updateProfile({
        fullName: fullName.trim() || undefined,
        password: password.trim() || undefined,
      });
      setProfile(updated);
      setFullName(updated.fullName ?? "");
      setPassword("");
      setMessage("Profile updated");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to update profile");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow="Account"
        title="User Profile"
        description="Manage the profile fields used across your workspace."
      />

      {error && <p role="alert" className="rounded-xl border border-danger/30 bg-danger/10 px-4 py-3 text-sm text-danger">{error}</p>}
      {message && <p className="rounded-xl border border-success/30 bg-success/10 px-4 py-3 text-sm text-success">{message}</p>}

      <div className="grid gap-5 lg:grid-cols-[0.75fr_1.25fr]">
        <Panel className="p-5">
          <div className="grid h-14 w-14 place-items-center rounded-xl border border-border bg-secondary text-muted-strong">
            <UserIcon className="h-6 w-6" />
          </div>
          <h2 className="mt-5 text-lg font-semibold text-foreground">{profile?.fullName || profile?.username || "Loading..."}</h2>
          <dl className="mt-4 grid gap-3 text-sm">
            <div>
              <dt className="eyebrow">Username</dt>
              <dd className="mt-1 font-mono text-muted-strong">{profile?.username ?? "..."}</dd>
            </div>
            <div>
              <dt className="eyebrow">Email</dt>
              <dd className="mt-1 text-muted-strong">{profile?.email ?? "..."}</dd>
            </div>
          </dl>
        </Panel>

        <CreatePanel title="Profile details" description="Update display name or set a new password. Leave password blank to keep the current password.">
          <form onSubmit={handleSubmit} className="grid gap-4 sm:grid-cols-2">
            <label className={fieldClass}>
              <span className={labelClass}>Full name</span>
              <input
                value={fullName}
                onChange={(event) => setFullName(event.target.value)}
                className={inputClass}
                placeholder="Your name"
              />
            </label>
            <label className={fieldClass}>
              <span className={labelClass}>New password</span>
              <input
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                className={inputClass}
                placeholder="Leave blank to keep current"
                autoComplete="new-password"
              />
            </label>
            <div className="sm:col-span-2">
              <Button type="submit" variant="primary" disabled={busy || !profile}>
                Save profile
              </Button>
            </div>
          </form>
        </CreatePanel>
      </div>
    </div>
  );
}
