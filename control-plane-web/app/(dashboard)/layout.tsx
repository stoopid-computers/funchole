"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { openConsentSettings } from "@/lib/consent";
import { ProfileContext } from "@/lib/profile";
import { ThemeToggle } from "@/components/ThemeToggle";
import { ModeSwitch } from "@/components/ModeSwitch";
import { ToastProvider } from "@/components/Toast";
import { ModeProvider, saveMode, useMode, useSavedMode } from "@/lib/mode";
import { useEffect, useState, type ComponentType, type ReactNode } from "react";
import { MenuIcon } from "lucide-react";
import { api } from "@/lib/api";
import { clearToken } from "@/lib/auth";
import type { ProfileResponse } from "@/lib/types";
import {
  GridIcon,
  FunctionIcon,
  WorkflowIcon,
  ServerIcon,
  GlobeIcon,
  DatabaseIcon,
  KeyIcon,
  TerminalIcon,
  LogOutIcon,
  UserIcon,
  SettingsIcon,
  ZapIcon,
} from "@/components/icons";
import { BrandMark } from "@/components/BrandMark";
import { ConfirmHost } from "@/components/ConfirmDialog";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetTrigger } from "@/components/ui/sheet";
import { cn } from "@/lib/utils";

interface NavItem {
  href: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
}

interface NavGroup {
  label: string | null;
  items: NavItem[];
}

// Simple: four places, in everyday words. Advanced: every technical screen.
const SIMPLE_NAV: NavGroup[] = [
  {
    label: null,
    items: [
      { href: "/", label: "Home", icon: GridIcon },
      { href: "/flows", label: "Pages & APIs", icon: WorkflowIcon },
      { href: "/activity", label: "Activity", icon: ZapIcon },
      { href: "/api-keys", label: "Agent", icon: TerminalIcon },
      { href: "/settings", label: "Settings", icon: SettingsIcon },
    ],
  },
];

const ADVANCED_NAV: NavGroup[] = [
  {
    label: "Build",
    items: [
      { href: "/", label: "Overview", icon: GridIcon },
      { href: "/functions", label: "Actions", icon: FunctionIcon },
      { href: "/flows", label: "Workflows", icon: WorkflowIcon },
    ],
  },
  {
    label: "Operate",
    items: [
      { href: "/gateways", label: "Entry Points", icon: ServerIcon },
      { href: "/activity", label: "Activity", icon: ZapIcon },
      { href: "/domains", label: "Custom Domains", icon: GlobeIcon },
    ],
  },
  {
    label: "Configure",
    items: [
      { href: "/environments", label: "Variables & Secrets", icon: KeyIcon },
      { href: "/databases", label: "Data Sources", icon: DatabaseIcon },
      { href: "/api-keys", label: "Agent Access", icon: TerminalIcon },
    ],
  },
  {
    label: "Account",
    items: [
      { href: "/profile", label: "User Profile", icon: UserIcon },
      { href: "/settings", label: "Settings", icon: SettingsIcon },
    ],
  },
];

// In Simple mode these screens live under one of the four menu items.
const SIMPLE_ALIASES: Record<string, string[]> = {
  "/flows": ["/flows", "/functions"],
  "/settings": ["/settings", "/environments", "/databases", "/profile", "/domains", "/gateways", "/package", "/account"],
};

const ACCOUNT_MENU_ITEMS: NavItem[] = [
  { href: "/profile", label: "User Profile", icon: UserIcon },
  { href: "/settings", label: "Settings", icon: SettingsIcon },
];

export default function DashboardLayout({ children }: { children: ReactNode }) {
  return (
    <ModeProvider>
      <ToastProvider>
        <Shell>{children}</Shell>
      </ToastProvider>
    </ModeProvider>
  );
}

function Shell({ children }: { children: ReactNode }) {
  const { mode } = useMode();
  const savedMode = useSavedMode();
  const router = useRouter();
  const pathname = usePathname();
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [profileKey, setProfileKey] = useState(0);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  useEffect(() => {
    let active = true;
    api
      .getProfile()
      .then((p) => {
        if (active) setProfile(p);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [profileKey]);

  // First time in the new workspace: people who already build things get
  // Advanced once; everyone else stays in Simple. After that it is their choice.
  useEffect(() => {
    if (savedMode !== null) return;
    let active = true;
    Promise.all([api.listFunctions(1, 1), api.listFlows(1, 1)])
      .then(([functions, flows]) => {
        if (active) saveMode(functions.totalElements + flows.totalElements > 0 ? "advanced" : "simple");
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [savedMode]);

  function handleLogout() {
    clearToken();
    router.replace("/login");
  }

  function isActive(href: string) {
    if (href === "/") return pathname === "/";
    const paths = mode === "simple" ? (SIMPLE_ALIASES[href] ?? [href]) : [href];
    return paths.some((path) => pathname.startsWith(path));
  }

  // The domain registry is admin-only on the hosted product: ordinary users
  // connect their own domain from their live address instead.
  const hideDomains = profile?.cloudMode === true && !profile.admin;
  const groups = (mode === "simple" ? SIMPLE_NAV : ADVANCED_NAV).map((group) => ({
    ...group,
    items: group.items.filter((item) => !(hideDomains && item.href === "/domains")),
  }));
  const currentSection = groups.flatMap((group) => group.items).find((item) => isActive(item.href))?.label ?? "FuncHole";
  const initial = (profile?.username || profile?.fullName || "U").slice(0, 1).toUpperCase();

  return (
    <ProfileContext.Provider value={{ profile, refresh: () => setProfileKey((key) => key + 1) }}>
    <div className="flex min-h-screen bg-background">
      <aside className="fixed inset-y-0 left-0 z-20 hidden w-64 flex-col border-r-2 border-edge bg-background lg:flex">
        <div className="flex h-14 items-center px-5">
          <BrandMark href="/" />
        </div>
        <div className="flex flex-1 flex-col overflow-y-auto px-3 pt-3 pb-4">
          <SidebarNav groups={groups} isActive={isActive} />
          {mode === "advanced" && <AgentSetupCard />}
        </div>
      </aside>

      <div className="flex min-h-screen min-w-0 flex-1 flex-col lg:pl-64">
        <header className="sticky top-0 z-10 flex h-14 items-center justify-between gap-3 border-b-2 border-edge bg-background px-4 sm:px-6">
          <div className="flex min-w-0 items-center gap-2 text-sm">
            <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
              <SheetTrigger asChild>
                <Button variant="ghost" size="icon" className="-ml-2 lg:hidden" aria-label="Open navigation">
                  <MenuIcon />
                </Button>
              </SheetTrigger>
              <SheetContent side="left" className="w-72 gap-0 p-0">
                <SheetHeader className="h-14 justify-center border-b-2 border-edge px-5">
                  <SheetTitle asChild>
                    <div>
                      <BrandMark />
                    </div>
                  </SheetTitle>
                </SheetHeader>
                <div className="flex flex-1 flex-col overflow-y-auto px-3 pt-3 pb-4">
                  <ModeSwitch className="mb-4 self-start" />
                  <SidebarNav groups={groups} isActive={isActive} onNavigate={() => setMobileNavOpen(false)} />
                  {mode === "advanced" && <AgentSetupCard onNavigate={() => setMobileNavOpen(false)} />}
                </div>
              </SheetContent>
            </Sheet>
            <span className="lg:hidden">
              <BrandMark href="/" showText={false} />
            </span>
            <span className="hidden font-heading font-extrabold tracking-tight text-foreground sm:inline">FuncHole</span>
            <span className="hidden text-faint sm:inline" aria-hidden="true">
              /
            </span>
            <span className="truncate text-muted-foreground">{currentSection}</span>
          </div>

          <div className="flex items-center gap-2">
          <ModeSwitch className="hidden sm:inline-flex" />
          <ThemeToggle />
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" className="h-9 gap-2 rounded-full pr-1 pl-3" aria-label="Open account menu">
                <span className="hidden max-w-32 truncate text-sm text-muted-strong sm:block">{profile?.username || "Account"}</span>
                <Avatar className="size-7">
                  <AvatarFallback className="border-2 border-edge bg-sun font-heading text-xs font-extrabold text-[var(--fh-on-sun)]">{initial}</AvatarFallback>
                </Avatar>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-64">
              <DropdownMenuLabel className="px-2 py-2 font-normal">
                <p className="truncate text-sm font-medium text-foreground">{profile?.fullName || profile?.username || "User"}</p>
                <p className="mt-0.5 truncate text-xs text-muted-foreground">{profile?.email || "Manage your workspace account"}</p>
              </DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuGroup>
                {ACCOUNT_MENU_ITEMS.map((item) => {
                  const Icon = item.icon;
                  return (
                    <DropdownMenuItem key={item.href} asChild>
                      <Link href={item.href}>
                        <Icon className="h-4 w-4" />
                        {item.label}
                      </Link>
                    </DropdownMenuItem>
                  );
                })}
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
              {process.env.NEXT_PUBLIC_GA_MEASUREMENT_ID && (
                <DropdownMenuItem onSelect={openConsentSettings}>
                  <SettingsIcon className="h-4 w-4" />
                  Cookie settings
                </DropdownMenuItem>
              )}
              <DropdownMenuItem variant="destructive" onSelect={handleLogout}>
                <LogOutIcon className="h-4 w-4" />
                Logout
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          </div>
        </header>

        <main data-density="quiet" className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 lg:px-10 lg:py-10">{children}</main>
      </div>

      <ConfirmHost />
    </div>
    </ProfileContext.Provider>
  );
}

function SidebarNav({ groups, isActive, onNavigate }: { groups: NavGroup[]; isActive: (href: string) => boolean; onNavigate?: () => void }) {
  return (
    <div className="flex flex-1 flex-col gap-6">
      {groups.map((group) => (
        <nav key={group.label ?? "main"} aria-label={group.label ?? "Main"}>
          {group.label && <p className="eyebrow px-2.5 pb-2">{group.label}</p>}
          <div className="space-y-0.5">
            {group.items.map((item) => {
              const active = isActive(item.href);
              const Icon = item.icon;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  onClick={onNavigate}
                  aria-current={active ? "page" : undefined}
                  className={cn(
                    "flex h-10 items-center gap-2.5 rounded-lg border-2 px-2.5 text-sm font-medium transition-colors",
                    active
                      ? "border-edge bg-sun text-[var(--fh-on-sun)]"
                      : "border-transparent text-muted-foreground hover:bg-ink/5 hover:text-foreground"
                  )}
                >
                  <Icon className={cn("h-4 w-4", active ? "text-[var(--fh-on-sun)]" : "text-subtle")} />
                  <span className="truncate">{item.label}</span>
                </Link>
              );
            })}
          </div>
        </nav>
      ))}
    </div>
  );
}

function AgentSetupCard({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <div className="sticker mt-6 overflow-hidden rounded-xl bg-sun-soft p-4">
      <p className="flex items-center gap-2 font-heading text-sm font-extrabold text-foreground">
        <span className="live-dot text-brand" aria-hidden="true" />
        Connect your agent first
      </p>
      <p className="mt-1.5 text-xs leading-relaxed text-muted-foreground">
        Let coding agents handle building. Use the dashboard for access, secrets, logs, and manual checks.
      </p>
      <Link
        href="/api-keys"
        onClick={onNavigate}
        className="mt-3 inline-flex items-center gap-1 text-xs font-semibold text-foreground underline underline-offset-4"
      >
        Configure agent access
        <span aria-hidden="true">→</span>
      </Link>
    </div>
  );
}
