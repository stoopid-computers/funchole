import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { StatusBadge } from "@/components/StatusBadge";
import { PackageIcon } from "@/components/icons";

export default function PackagePage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        eyebrow="Account"
        title="Package"
        description="Current plan, package limits, and billing-facing usage."
      />
      <Panel className="p-5">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <div className="grid h-12 w-12 place-items-center rounded-xl border border-border bg-secondary text-muted-strong">
              <PackageIcon className="h-5 w-5" />
            </div>
            <h2 className="mt-5 text-lg font-semibold text-foreground">Developer preview</h2>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
              Package and billing APIs are not implemented yet. This page keeps the product surface ready for plan limits, usage, and upgrade paths.
            </p>
          </div>
          <StatusBadge status="ACTIVE" />
        </div>
      </Panel>
    </div>
  );
}
