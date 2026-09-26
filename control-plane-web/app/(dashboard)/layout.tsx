"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
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
  PackageIcon,
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

const NAV_GROUPS: { label: string; items: NavItem[] }[] = [
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
      { href: "/account", label: "Account", icon: UserIcon },
      { href: "/settings", label: "Settings", icon: SettingsIcon },
      { href: "/package", label: "Package", icon: PackageIcon },
    ],
  },
];

const NAV_ITEMS = NAV_GROUPS.flatMap((group) => group.items);

const ACCOUNT_MENU_ITEMS: NavItem[] = [
  { href: "/profile", label: "User Profile", icon: UserIcon },
  { href: "/account", label: "Account", icon: UserIcon },
  { href: "/settings", label: "Settings", icon: SettingsIcon },
  { href: "/package", label: "Package", icon: PackageIcon },
];

export default function DashboardLayout({ children }: { children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
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
  }, []);

  function handleLogout() {
    clearToken();
    router.replace("/login");
  }

  function isActive(href: string) {
    return href === "/" ? pathname === "/" : pathname.startsWith(href);
  }

  const currentSection = NAV_ITEMS.find((item) => isActive(item.href))?.label ?? "FuncHole";
  const initial = (profile?.username || profile?.fullName || "U").slice(0, 1).toUpperCase();

  return (
    <div className="flex min-h-screen bg-background">
      <aside className="fixed inset-y-0 left-0 z-20 hidden w-64 flex-col border-r border-border bg-background lg:flex">
        <div className="flex h-14 items-center px-5">
          <BrandMark href="/" />
        </div>
        <div className="flex flex-1 flex-col overflow-y-auto px-3 pt-3 pb-4">
          <SidebarNav isActive={isActive} />
          <AgentSetupCard />
        </div>
      </aside>

      <div className="flex min-h-screen min-w-0 flex-1 flex-col lg:pl-64">
        <header className="sticky top-0 z-10 flex h-14 items-center justify-between gap-3 border-b border-border bg-background/80 px-4 backdrop-blur-xl sm:px-6">
          <div className="flex min-w-0 items-center gap-2 text-sm">
            <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
              <SheetTrigger asChild>
                <Button variant="ghost" size="icon" className="-ml-2 lg:hidden" aria-label="Open navigation">
                  <MenuIcon />
                </Button>
              </SheetTrigger>
              <SheetContent side="left" className="w-72 gap-0 border-border bg-background p-0">
                <SheetHeader className="h-14 justify-center border-b border-border px-5">
                  <SheetTitle asChild>
                    <div>
                      <BrandMark />
                    </div>
                  </SheetTitle>
                </SheetHeader>
                <div className="flex flex-1 flex-col overflow-y-auto px-3 pt-3 pb-4">
                  <SidebarNav isActive={isActive} onNavigate={() => setMobileNavOpen(false)} />
                  <AgentSetupCard onNavigate={() => setMobileNavOpen(false)} />
                </div>
              </SheetContent>
            </Sheet>
            <span className="lg:hidden">
              <BrandMark href="/" showText={false} />
            </span>
            <span className="hidden font-medium tracking-tight text-foreground sm:inline">FuncHole</span>
            <span className="hidden text-faint sm:inline" aria-hidden="true">
              /
            </span>
            <span className="truncate text-muted-foreground">{currentSection}</span>
          </div>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" className="h-9 gap-2 rounded-full pr-1 pl-3" aria-label="Open account menu">
                <span className="hidden max-w-32 truncate text-sm text-muted-strong sm:block">{profile?.username || "Account"}</span>
                <Avatar className="size-7">
                  <AvatarFallback className="bg-secondary text-xs font-medium text-foreground">{initial}</AvatarFallback>
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
              <DropdownMenuItem variant="destructive" onSelect={handleLogout}>
                <LogOutIcon className="h-4 w-4" />
                Logout
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </header>

        <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 lg:px-10 lg:py-10">{children}</main>
      </div>

      <ConfirmHost />
    </div>
  );
}

function SidebarNav({ isActive, onNavigate }: { isActive: (href: string) => boolean; onNavigate?: () => void }) {
  return (
    <div className="flex flex-1 flex-col gap-6">
      {NAV_GROUPS.map((group) => (
        <nav key={group.label} aria-label={group.label}>
          <p className="eyebrow px-2.5 pb-2">{group.label}</p>
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
                    "flex h-9 items-center gap-2.5 rounded-lg px-2.5 text-sm transition-colors",
                    active
                      ? "bg-white/[0.07] text-foreground"
                      : "text-muted-foreground hover:bg-white/[0.04] hover:text-foreground"
                  )}
                >
                  <Icon className={cn("h-4 w-4", active ? "text-foreground" : "text-subtle")} />
                  <span className="truncate">{item.label}</span>
                  {active && <span className="ml-auto size-1.5 rounded-full bg-brand" aria-hidden="true" />}
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
    <div className="mt-6 overflow-hidden rounded-xl border border-border bg-card p-4">
      <p className="flex items-center gap-2 text-sm font-medium text-foreground">
        <span className="live-dot text-brand" aria-hidden="true" />
        Connect your agent first
      </p>
      <p className="mt-1.5 text-xs leading-relaxed text-muted-foreground">
        Let coding agents handle building. Use the dashboard for access, secrets, logs, and manual checks.
      </p>
      <Link
        href="/api-keys"
        onClick={onNavigate}
        className="mt-3 inline-flex items-center gap-1 text-xs font-medium text-foreground hover:text-muted-strong"
      >
        Configure agent access
        <span aria-hidden="true">→</span>
      </Link>
    </div>
  );
}
