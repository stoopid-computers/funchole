import type { Mode } from "@/lib/copy";

// Page headings, list titles and empty states for each mode. Simple speaks to
// someone who has never heard of workflows or gateways; Advanced keeps the
// technical names.
export interface PageCopy {
  eyebrow?: string;
  title: string;
  description: string;
  listTitle: string;
  listDescription: string;
  loading: string;
  emptyTitle: string;
  emptyDescription: string;
  open: string;
  /** Label of the "create one" button; null hides the button in this mode. */
  create: string | null;
}

export type PageKey = "flows" | "functions" | "gateways" | "environments" | "databases" | "apiKeys" | "profile";

export const PAGE_COPY: Record<PageKey, Record<Mode, PageCopy>> = {
  flows: {
    simple: {
      title: "Pages & APIs",
      description: "What visitors can open or call on your site. Your coding agent creates these.",
      listTitle: "Your pages & APIs",
      listDescription: "Each one has its own web address.",
      loading: "Loading your pages…",
      emptyTitle: "No pages yet",
      emptyDescription: "Ask your coding agent to build one. For example: “Build me a page where customers can book a consultation.”",
      open: "Details",
      create: null,
    },
    advanced: {
      eyebrow: "Build",
      title: "Workflows",
      description: "Customer-facing paths that connect a request to the right actions.",
      listTitle: "Workflow catalog",
      listDescription: "Each workflow owns one public path and becomes live after publishing.",
      loading: "Loading workflows…",
      emptyTitle: "No workflows yet",
      emptyDescription: "Choose a public method and path on one of your entry points, then connect it to actions.",
      open: "Open workflow",
      create: "New workflow",
    },
  },
  functions: {
    simple: {
      title: "Features",
      description: "The building blocks your agent has made for your site. You can see whether each one is ready.",
      listTitle: "Your features",
      listDescription: "Open one to see its updates.",
      loading: "Loading your features…",
      emptyTitle: "No features yet",
      emptyDescription: "They appear here as your coding agent builds your site.",
      open: "Details",
      create: null,
    },
    advanced: {
      eyebrow: "Build",
      title: "Actions",
      description: "Reusable pieces of work your agent can prepare, test, and connect to customer-facing workflows.",
      listTitle: "Action catalog",
      listDescription: "Open an action to inspect versions, source, and test runs.",
      loading: "Loading actions…",
      emptyTitle: "No actions yet",
      emptyDescription: "Create one manually or let a connected agent prepare the first capability.",
      open: "Open action",
      create: "New action",
    },
  },
  gateways: {
    simple: {
      title: "Your live address",
      description: "The web address your site is available at. It is set up for you.",
      listTitle: "Your live address",
      listDescription: "This is where visitors reach your site.",
      loading: "Loading…",
      emptyTitle: "No live address yet",
      emptyDescription: "A live address is created when you sign up. If yours is missing, contact support.",
      open: "Details",
      create: null,
    },
    advanced: {
      eyebrow: "Operate",
      title: "Entry Points",
      description: "Public hosts with certificates. An entry point becomes the stable hostname for customer-facing workflows.",
      listTitle: "Entry point registry",
      listDescription: "Hosts available for live workflows and certificate-backed traffic.",
      loading: "Loading entry points…",
      emptyTitle: "No entry points yet",
      emptyDescription: "Create a public host on one of your verified domains, then route workflows to it.",
      open: "Open",
      create: "New entry point",
    },
  },
  environments: {
    simple: {
      title: "Private keys and settings",
      description: "Passwords and keys your pages use. Your agent tells you when it needs one.",
      listTitle: "Your settings",
      listDescription: "Pick a group to see what is in it. Secret values stay hidden.",
      loading: "Loading your settings…",
      emptyTitle: "Nothing here yet",
      emptyDescription: "When your agent needs a password or key, add it here.",
      open: "Open",
      create: "Add settings",
    },
    advanced: {
      eyebrow: "Configure",
      title: "Variables & Secrets",
      description: "Shared configuration for production, staging, testing, and agent-created work.",
      listTitle: "Variable sets",
      listDescription: "Select a set to manage variables and secrets.",
      loading: "Loading variable sets…",
      emptyTitle: "No variable sets yet",
      emptyDescription: "Create a set to share configuration.",
      open: "Open",
      create: "New variable set",
    },
  },
  databases: {
    simple: {
      title: "Database",
      description: "Where your pages keep their data, like bookings or orders.",
      listTitle: "Your databases",
      listDescription: "Connection details stay hidden until you ask to see them.",
      loading: "Loading your databases…",
      emptyTitle: "No database yet",
      emptyDescription: "Add one when your pages need to remember things.",
      open: "Open",
      create: "Add a database",
    },
    advanced: {
      eyebrow: "Configure",
      title: "Data Sources",
      description: "Managed connection definitions for the databases your actions and workflows need.",
      listTitle: "Data source registry",
      listDescription: "Reusable connection profiles.",
      loading: "Loading data sources…",
      emptyTitle: "No data sources yet",
      emptyDescription: "Create one before attaching data access to customer-facing work.",
      open: "Open",
      create: "New data source",
    },
  },
  apiKeys: {
    simple: {
      title: "Connect your coding agent",
      description: "Your agent builds your site. Create a key, then paste one command into your agent.",
      listTitle: "Your agent keys",
      listDescription: "Each key lets one agent work in your workspace.",
      loading: "Loading your agent keys…",
      emptyTitle: "No agent connected yet",
      emptyDescription: "Create a key to get started.",
      open: "Open",
      create: "Create agent key",
    },
    advanced: {
      eyebrow: "Configure",
      title: "Agent Access",
      description: "Credentials for coding agents. Create one key, copy the generated command, and keep the dashboard for manual oversight.",
      listTitle: "Agent credentials",
      listDescription: "",
      loading: "Loading agent keys…",
      emptyTitle: "No agent keys yet",
      emptyDescription: "Create one to connect a coding agent.",
      open: "Open",
      create: "New API key",
    },
  },
  profile: {
    simple: {
      title: "Your profile",
      description: "Your name and password.",
      listTitle: "",
      listDescription: "",
      loading: "Loading…",
      emptyTitle: "",
      emptyDescription: "",
      open: "Open",
      create: null,
    },
    advanced: {
      eyebrow: "Account",
      title: "User Profile",
      description: "Manage the profile fields used across your workspace.",
      listTitle: "",
      listDescription: "",
      loading: "Loading…",
      emptyTitle: "",
      emptyDescription: "",
      open: "Open",
      create: null,
    },
  },
};
