"use client";

import Link from "next/link";
import { useEffect, useState, type ComponentType } from "react";
import { DatabaseIcon, GlobeIcon, KeyIcon, PackageIcon, SettingsIcon, UserIcon } from "@/components/icons";
import { ModeSwitch } from "@/components/ModeSwitch";
import { ADVANCED_ENABLED } from "@/lib/mode";
import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { ThemeToggle } from "@/components/ThemeToggle";
import { api } from "@/lib/api";
import { openConsentSettings } from "@/lib/consent";
import { useProfile } from "@/lib/profile";

interface SettingsLink {
  href: string;
  title: string;
  description: string;
  icon: ComponentType<{ className?: string }>;
}

export default function SettingsPage() {
  const { profile } = useProfile();
  const [gatewayId, setGatewayId] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api
      .listGateways(1, 1)
      .then((result) => {
        if (active) setGatewayId(result.items[0]?.id ?? null);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  const links: SettingsLink[] = [
    {
      href: "/environments",
      title: "Private keys and settings",
      description: "Passwords and keys your pages use. Your agent tells you when it needs one.",
      icon: KeyIcon,
    },
    {
      href: "/databases",
      title: "Database",
      description: "Where your pages keep their data, such as bookings or orders.",
      icon: DatabaseIcon,
    },
    {
      href: gatewayId ? `/gateways/${gatewayId}` : "/gateways",
      title: "Your own domain",
      description: "Use a name like shop.yourbusiness.com instead of the FuncHole address.",
      icon: GlobeIcon,
    },
    ...(profile?.cloudMode
      ? [
          {
            href: "/package",
            title: "Your plan",
            description: "What your plan includes and how much you have used.",
            icon: PackageIcon,
          },
        ]
      : []),
    {
      href: "/profile",
      title: "Your profile",
      description: profile?.email ? `Signed in as ${profile.email}. Change your name or password.` : "Change your name or password.",
      icon: UserIcon,
    },
  ];

  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Settings" description="Everything about your account and what your pages use." />

      <Panel className="divide-y divide-border overflow-hidden">
        {links.map((item) => {
          const Icon = item.icon;
          return (
            <Link key={item.title} href={item.href} className="group flex items-center gap-4 px-5 py-4 transition-colors hover:bg-sun-soft">
              <span className="grid size-10 shrink-0 place-items-center rounded-lg border border-border bg-muted">
                <Icon className="h-5 w-5 text-foreground" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block font-heading text-base font-extrabold tracking-tight text-foreground">{item.title}</span>
                <span className="mt-0.5 block text-sm text-muted-foreground">{item.description}</span>
              </span>
              <span aria-hidden="true" className="text-subtle transition-transform group-hover:translate-x-0.5 group-hover:text-foreground">
                →
              </span>
            </Link>
          );
        })}
      </Panel>

      <Panel className="divide-y divide-border overflow-hidden">
        {ADVANCED_ENABLED && (
          <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
            <div>
              <p className="font-heading text-base font-extrabold tracking-tight text-foreground">How much detail to show</p>
              <p className="mt-0.5 text-sm text-muted-foreground">Simple hides the technical screens. Advanced shows everything.</p>
            </div>
            <ModeSwitch />
          </div>
        )}
        <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
          <div>
            <p className="font-heading text-base font-extrabold tracking-tight text-foreground">Appearance</p>
            <p className="mt-0.5 text-sm text-muted-foreground">Switch between light and dark.</p>
          </div>
          <ThemeToggle />
        </div>
        {process.env.NEXT_PUBLIC_GA_MEASUREMENT_ID && (
          <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
            <div>
              <p className="flex items-center gap-2 font-heading text-base font-extrabold tracking-tight text-foreground">
                <SettingsIcon className="h-4 w-4" /> Cookies
              </p>
              <p className="mt-0.5 text-sm text-muted-foreground">Choose whether analytics cookies are allowed.</p>
            </div>
            <button
              type="button"
              onClick={openConsentSettings}
              className="cursor-pointer rounded-lg border border-input bg-card px-3 py-1.5 text-sm font-semibold hover:bg-muted"
            >
              Cookie settings
            </button>
          </div>
        )}
      </Panel>
    </div>
  );
}
