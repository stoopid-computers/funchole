import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { StatusBadge } from "@/components/StatusBadge";

export default function AccountPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow="Account"
        title="Account"
        description="Workspace identity and account ownership settings will live here."
      />
      <Panel className="p-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold text-foreground">Account management</h2>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
              Team members, ownership transfer, connected OAuth identities, and audit controls are not wired yet. This page reserves the account-level surface so it is visible in navigation.
            </p>
          </div>
          <StatusBadge status="PENDING" />
        </div>
      </Panel>
    </div>
  );
}
