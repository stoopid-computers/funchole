import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { StatusBadge } from "@/components/StatusBadge";

export default function SettingsPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow="Account"
        title="Settings"
        description="Control-plane preferences, notification behavior, and workspace defaults."
      />
      <Panel className="p-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold text-foreground">Workspace settings</h2>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
              Settings are intentionally read-only for now because the backend does not expose workspace preferences yet. We can wire this page once those APIs exist.
            </p>
          </div>
          <StatusBadge status="DRAFT" />
        </div>
      </Panel>
    </div>
  );
}
