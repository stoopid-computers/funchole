"use client";

import "@xyflow/react/dist/style.css";
import { confirmAction } from "@/components/ConfirmDialog";
import { NativeSelect } from "@/components/ui/native-select";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState, type DragEvent, type MouseEvent } from "react";
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  Position,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type NodeProps,
} from "@xyflow/react";
import { StatusBadge } from "@/components/StatusBadge";
import { Button } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { JsonEditor } from "@/components/CodeEditor";
import { OutputLog } from "@/components/OutputLog";
import {
  ArrowLeftIcon,
  ChevronRightIcon,
  XIcon,
  TrashIcon,
  PlayIcon,
  ArchiveIcon,
  WorkflowIcon,
  ExternalLinkIcon,
  ZapIcon,
  CheckIcon,
} from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type {
  FlowResponse,
  FlowStepComponentType,
  FlowStepResponse,
  FlowVersionResponse,
  FunctionResponse,
  FunctionVersionResponse,
  InvocationInspectionResponse,
} from "@/lib/types";

const NODE_WIDTH = 260;
const ROW_HEIGHT = 150;

// Step colours match the Flow step labels on the landing page.
const COMPONENT_META: Record<FlowStepComponentType, { badge: string; dot: string; label: string }> = {
  FUNCTION: {
    badge: "bg-brand-soft text-brand",
    dot: "bg-brand",
    label: "Function",
  },
  RESPONSE: {
    badge: "bg-success/10 text-success",
    dot: "bg-success",
    label: "Response",
  },
  MIDDLEWARE: {
    badge: "bg-code-kw/10 text-code-kw",
    dot: "bg-code-kw",
    label: "Middleware",
  },
  SUB_FLOW: {
    badge: "bg-code-num/10 text-code-num",
    dot: "bg-code-num",
    label: "Sub-flow",
  },
};

const PALETTE_ITEMS: { type: FlowStepComponentType; title: string; description: string }[] = [
  { type: "FUNCTION", title: "Function", description: "Runs a pinned Node artifact" },
  { type: "RESPONSE", title: "Response", description: "Ends the flow with the last result" },
];

interface StepNodeData extends Record<string, unknown> {
  step: FlowStepResponse;
  selected: boolean;
}

function StepNode({ data }: NodeProps<Node<StepNodeData>>) {
  const { step, selected } = data;
  const meta = COMPONENT_META[step.componentType];
  return (
    <div
      style={{ width: NODE_WIDTH }}
      className={`cursor-pointer rounded-xl border bg-surface px-4 py-3 transition-colors ${
        selected ? "border-brand ring-2 ring-brand/30" : "border-border hover:border-border-strong"
      }`}
    >
      <Handle type="target" position={Position.Top} isConnectable={false} className="!bg-border-strong" />
      <div className="flex items-center gap-2">
        <span className={`h-2 w-2 shrink-0 rounded-full ${meta.dot}`} />
        <span className="truncate text-sm font-medium text-foreground">{step.stepKey}</span>
      </div>
      <div className="mt-2 flex items-center justify-between">
        <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide ${meta.badge}`}>
          {meta.label}
        </span>
        <span className="font-mono text-[11px] text-muted-foreground">#{step.position}</span>
      </div>
      <Handle type="source" position={Position.Bottom} isConnectable={false} className="!bg-border-strong" />
    </div>
  );
}

const nodeTypes = { step: StepNode };

type InspectorMode = { kind: "view" | "edit"; step: FlowStepResponse } | { kind: "create"; componentType: FlowStepComponentType } | null;

export default function FlowVersionEditorPage() {
  return (
    <ReactFlowProvider>
      <FlowVersionCanvas />
    </ReactFlowProvider>
  );
}

function FlowVersionCanvas() {
  const params = useParams<{ flowId: string; versionId: string }>();
  const router = useRouter();
  const { flowId, versionId } = params;

  const [flow, setFlow] = useState<FlowResponse | null>(null);
  const [version, setVersion] = useState<FlowVersionResponse | null>(null);
  const [versionList, setVersionList] = useState<FlowVersionResponse[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const [inspector, setInspector] = useState<InspectorMode>(null);
  const [testPanelOpen, setTestPanelOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [nodes, setNodes, onNodesChange] = useNodesState<Node<StepNodeData>>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const flowData = await api.getFlow(flowId);
        if (!cancelled) setFlow(flowData);
      } catch {
        if (!cancelled) setError("Failed to load flow");
      }
      try {
        const versionData = await api.getFlowVersion(flowId, versionId);
        if (!cancelled) setVersion(versionData);
      } catch {
        if (!cancelled) setError("Failed to load version");
      }
      try {
        const versions = await api.listFlowVersions(flowId, 1, 50);
        if (!cancelled) setVersionList(versions.items.sort((a, b) => b.version - a.version));
      } catch {
        if (!cancelled) setVersionList([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [flowId, versionId, reloadKey]);

  const isDraft = version?.status === "DRAFT";
  const steps = useMemo(
    () => [...(version?.steps ?? [])].sort((a, b) => a.position - b.position),
    [version]
  );

  useEffect(() => {
    const selectedId = inspector?.kind !== "create" ? inspector?.step.id : undefined;
    const nextNodes: Node<StepNodeData>[] = steps.map((step, index) => ({
      id: step.id,
      type: "step",
      position: { x: 0, y: index * ROW_HEIGHT },
      data: { step, selected: step.id === selectedId },
      draggable: false,
    }));
    const nextEdges: Edge[] = steps.slice(1).map((step, index) => ({
      id: `${steps[index].id}-${step.id}`,
      source: steps[index].id,
      target: step.id,
      animated: true,
      style: { stroke: "var(--color-border-strong)", strokeWidth: 2 },
    }));
    setNodes(nextNodes);
    setEdges(nextEdges);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [steps, inspector]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  const onNodeClick = useCallback(
    (_: MouseEvent, node: Node<StepNodeData>) => {
      setTestPanelOpen(false);
      setInspector({ kind: isDraft ? "edit" : "view", step: node.data.step });
    },
    [isDraft]
  );

  function onPaletteDragStart(event: DragEvent, type: FlowStepComponentType) {
    event.dataTransfer.setData("application/funchole-step", type);
    event.dataTransfer.effectAllowed = "move";
  }

  function onDragOver(event: DragEvent) {
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    if (!isDraft) return;
    const type = event.dataTransfer.getData("application/funchole-step") as FlowStepComponentType;
    if (!type) return;
    setTestPanelOpen(false);
    setInspector({ kind: "create", componentType: type });
  }

  async function handleAdopt() {
    setError(null);
    setBusy(true);
    try {
      await api.adoptFlowVersion(flowId, versionId);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to adopt version");
    } finally {
      setBusy(false);
    }
  }

  async function handleArchive() {
    setError(null);
    setBusy(true);
    try {
      await api.archiveFlowVersion(flowId, versionId);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to archive version");
    } finally {
      setBusy(false);
    }
  }

  if (!flow || !version) {
    return <p className="p-6 text-sm text-muted-foreground">Loading…</p>;
  }

  return (
    <div className="flex h-[calc(100vh-3.5rem)] flex-col">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border bg-surface px-4 py-3 sm:px-6">
        <div className="flex min-w-0 items-center gap-3">
          <Link
            href={`/flows/${flowId}`}
            className="flex h-8 w-8 items-center justify-center rounded-lg border border-border text-muted-foreground hover:bg-surface-hover hover:text-foreground"
          >
            <ArrowLeftIcon className="h-4 w-4" />
          </Link>
          <nav className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <Link href="/flows" className="hover:text-foreground">
              Flows
            </Link>
            <ChevronRightIcon className="h-3.5 w-3.5" />
            <Link href={`/flows/${flowId}`} className="font-medium text-foreground hover:text-muted-strong">
              {flow.name}
            </Link>
          </nav>
          <NativeSelect
            value={versionId}
            onChange={(e) => router.push(`/flows/${flowId}/versions/${e.target.value}`)}
            size="sm" selectClassName="font-mono"
          >
            {versionList.map((v) => (
              <option key={v.id} value={v.id}>
                v{v.version}
              </option>
            ))}
          </NativeSelect>
          <StatusBadge status={version.status} />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span
            title={`${flow.httpMethod} ${flow.path} on ${flow.gatewayName}`}
            className="hidden items-center gap-1 font-mono text-xs text-muted-foreground sm:flex"
          >
            <ExternalLinkIcon className="h-3.5 w-3.5" />
            {flow.httpMethod} {flow.path}
          </span>
          {version.status !== "ARCHIVED" && (
            <Button
              variant="secondary"
              onClick={() => {
                setInspector(null);
                setTestPanelOpen((open) => !open);
              }}
              disabled={steps.length === 0}
              title={steps.length === 0 ? "Add at least one step first" : "Run this version directly, without a Gateway request"}
            >
              <ZapIcon className="h-4 w-4" />
              Test flow
            </Button>
          )}
          {version.status === "DRAFT" && (
            <Button
              variant="primary"
              onClick={handleAdopt}
              disabled={busy || steps.length === 0}
              title={steps.length === 0 ? "Add at least one step first" : "Make this the live version"}
            >
              <PlayIcon className="h-4 w-4" />
              Adopt
            </Button>
          )}
          {version.status === "ADOPTED" && (
            <Button variant="secondary" onClick={handleArchive} disabled={busy}>
              <ArchiveIcon className="h-4 w-4" />
              Archive
            </Button>
          )}
        </div>
      </div>

      {error && (
        <p role="alert" className="border-b border-danger/25 bg-danger/10 px-6 py-2 text-sm text-danger">
          {error}
        </p>
      )}

      <div className="flex flex-1 overflow-hidden">
        <aside className="hidden w-64 shrink-0 flex-col gap-4 overflow-y-auto border-r border-border bg-surface p-4 md:flex">
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Add node</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {isDraft ? "Drag onto the canvas to add a step." : "Only DRAFT versions can be edited."}
            </p>
          </div>
          <div className="flex flex-col gap-2">
            {PALETTE_ITEMS.map((item) => (
              <div
                key={item.type}
                draggable={isDraft}
                onDragStart={(e) => onPaletteDragStart(e, item.type)}
                onClick={() => {
                  if (!isDraft) return;
                  setTestPanelOpen(false);
                  setInspector({ kind: "create", componentType: item.type });
                }}
                className={`rounded-lg border border-border bg-background px-3 py-2.5 transition-colors ${
                  isDraft ? "cursor-grab hover:border-border-strong active:cursor-grabbing" : "cursor-not-allowed opacity-50"
                }`}
              >
                <div className="flex items-center gap-2">
                  <span className={`h-2 w-2 rounded-full ${COMPONENT_META[item.type].dot}`} />
                  <span className="text-sm font-medium text-foreground">{item.title}</span>
                </div>
                <p className="mt-0.5 text-xs text-muted-foreground">{item.description}</p>
              </div>
            ))}
          </div>
          <div className="mt-auto rounded-lg border border-border bg-background p-3">
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <WorkflowIcon className="h-3.5 w-3.5" />
              {steps.length} step{steps.length === 1 ? "" : "s"}
            </div>
          </div>
        </aside>

        <div className="relative flex-1" onDragOver={onDragOver} onDrop={onDrop}>
          {steps.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
              <WorkflowIcon className="h-8 w-8 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">
                {isDraft ? "Drag Function or Response from the left onto the canvas." : "This version has no steps."}
              </p>
            </div>
          ) : (
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeClick={onNodeClick}
              nodeTypes={nodeTypes}
              nodesDraggable={false}
              nodesConnectable={false}
              elementsSelectable
              fitView
              fitViewOptions={{ padding: 0.3 }}
              colorMode="dark"
              proOptions={{ hideAttribution: true }}
            >
              <Background variant={BackgroundVariant.Dots} gap={20} size={1} />
              <Controls showInteractive={false} />
            </ReactFlow>
          )}
        </div>

        {inspector && (
          <StepInspector
            mode={inspector}
            flowId={flowId}
            versionId={versionId}
            nextPosition={steps.length > 0 ? Math.max(...steps.map((s) => s.position)) + 10 : 10}
            isDraft={isDraft}
            onClose={() => setInspector(null)}
            onSaved={() => {
              setInspector(null);
              refresh();
            }}
            onError={setError}
          />
        )}

        {testPanelOpen && (
          <TestFlowPanel flowId={flowId} versionId={versionId} onClose={() => setTestPanelOpen(false)} onError={setError} />
        )}
      </div>
    </div>
  );
}

interface StepInspectorProps {
  mode: NonNullable<InspectorMode>;
  flowId: string;
  versionId: string;
  nextPosition: number;
  isDraft: boolean;
  onClose: () => void;
  onSaved: () => void;
  onError: (message: string) => void;
}

function StepInspector({ mode, flowId, versionId, nextPosition, isDraft, onClose, onSaved, onError }: StepInspectorProps) {
  const isCreate = mode.kind === "create";
  const initialStep = mode.kind !== "create" ? mode.step : null;

  const [stepKey, setStepKey] = useState(initialStep?.stepKey ?? "");
  const [componentType, setComponentType] = useState<FlowStepComponentType>(
    initialStep?.componentType ?? (mode.kind === "create" ? mode.componentType : "FUNCTION")
  );
  const [position, setPosition] = useState(String(initialStep?.position ?? nextPosition));
  const [componentId, setComponentId] = useState(initialStep?.componentId ?? "");
  const [componentVersionId, setComponentVersionId] = useState(initialStep?.componentVersionId ?? "");
  const [busy, setBusy] = useState(false);

  const editable = isDraft && mode.kind !== "view";

  async function handleSave() {
    setBusy(true);
    const payload = {
      stepKey: stepKey.trim(),
      componentType,
      position: Number(position),
      componentId: componentId.trim(),
      componentVersionId: componentVersionId.trim(),
    };
    try {
      if (isCreate) {
        await api.createFlowStep(flowId, versionId, payload);
      } else if (initialStep) {
        await api.updateFlowStep(flowId, versionId, initialStep.id, payload);
      }
      onSaved();
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to save step");
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete() {
    if (!initialStep || !await confirmAction(`Remove step "${initialStep.stepKey}"?`)) return;
    setBusy(true);
    try {
      await api.deleteFlowStep(flowId, versionId, initialStep.id);
      onSaved();
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to delete step");
    } finally {
      setBusy(false);
    }
  }

  return (
    <aside className="flex w-full max-w-80 shrink-0 flex-col border-l border-border bg-surface">
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <div>
          <p className="text-sm font-medium text-foreground">
            {isCreate ? "New step" : initialStep?.stepKey}
          </p>
          <p className="text-xs text-muted-foreground">{isCreate ? COMPONENT_META[componentType].label : mode.kind === "view" ? "Read only" : "Edit step"}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="flex h-7 w-7 cursor-pointer items-center justify-center rounded-lg text-muted-foreground hover:bg-surface-hover hover:text-foreground"
        >
          <XIcon className="h-4 w-4" />
        </button>
      </div>

      <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-4">
        <label className={fieldClass}>
          <span className={labelClass}>Step key</span>
          <input
            type="text"
            required
            maxLength={150}
            disabled={!editable}
            placeholder="list-orders"
            value={stepKey}
            onChange={(e) => setStepKey(e.target.value)}
            className={inputClass}
          />
        </label>

        {isCreate && (
          <label className={fieldClass}>
            <span className={labelClass}>Component type</span>
            <NativeSelect
              value={componentType}
              onChange={(e) => setComponentType(e.target.value as FlowStepComponentType)}
              className="w-full"
            >
              {PALETTE_ITEMS.map((item) => (
                <option key={item.type} value={item.type}>
                  {item.title}
                </option>
              ))}
            </NativeSelect>
          </label>
        )}

        <label className={fieldClass}>
          <span className={labelClass}>Order</span>
          <input
            type="number"
            required
            disabled={!editable}
            value={position}
            onChange={(e) => setPosition(e.target.value)}
            className={inputClass}
          />
        </label>

        <ComponentPicker
          componentType={componentType}
          flowId={flowId}
          componentId={componentId}
          componentVersionId={componentVersionId}
          editable={editable}
          onChange={(id, verId) => {
            setComponentId(id);
            setComponentVersionId(verId);
          }}
        />
      </div>

      {editable && (
        <div className="flex gap-2 border-t border-border p-4">
          <Button variant="primary" className="flex-1" disabled={busy} onClick={handleSave}>
            {isCreate ? "Create step" : "Save changes"}
          </Button>
          {!isCreate && (
            <Button variant="danger" size="icon" disabled={busy} onClick={handleDelete}>
              <TrashIcon className="h-4 w-4" />
            </Button>
          )}
        </div>
      )}
    </aside>
  );
}

interface ComponentPickerProps {
  componentType: FlowStepComponentType;
  flowId: string;
  componentId: string;
  componentVersionId: string;
  editable: boolean;
  onChange: (componentId: string, componentVersionId: string) => void;
}

/**
 * FUNCTION/RESPONSE/MIDDLEWARE steps pin a FunctionVersion; SUB_FLOW steps
 * pin a FlowVersion. Either way this replaces raw UUID text entry with real
 * name-based pickers (GAP-19) - only READY FunctionVersions / ADOPTED
 * FlowVersions are offered, matching what adoption itself requires.
 */
function ComponentPicker({ componentType, flowId, componentId, componentVersionId, editable, onChange }: ComponentPickerProps) {
  const isSubFlow = componentType === "SUB_FLOW";

  const [functions, setFunctions] = useState<FunctionResponse[] | null>(null);
  const [functionVersions, setFunctionVersions] = useState<FunctionVersionResponse[] | null>(null);
  const [flows, setFlows] = useState<FlowResponse[] | null>(null);
  const [flowVersions, setFlowVersions] = useState<FlowVersionResponse[] | null>(null);

  // The version list belongs to whichever componentId it was last fetched
  // for. When componentId changes (a new Function/Flow was picked, or it
  // was cleared), that stale list must disappear immediately - adjusting
  // state during render (React's documented pattern for this) rather than
  // resetting it from inside an effect.
  const [versionsFor, setVersionsFor] = useState(componentId);
  if (componentId !== versionsFor) {
    setVersionsFor(componentId);
    setFunctionVersions(null);
    setFlowVersions(null);
  }

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (isSubFlow) {
        try {
          const data = await api.listFlows(1, 100);
          if (!cancelled) setFlows(data.items.filter((f) => f.id !== flowId));
        } catch {
          if (!cancelled) setFlows([]);
        }
      } else {
        try {
          const data = await api.listFunctions(1, 100);
          if (!cancelled) setFunctions(data.items);
        } catch {
          if (!cancelled) setFunctions([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [isSubFlow, flowId]);

  useEffect(() => {
    if (!componentId) return;
    let cancelled = false;
    (async () => {
      if (isSubFlow) {
        try {
          const data = await api.listFlowVersions(componentId, 1, 100);
          if (!cancelled) setFlowVersions(data.items.filter((v) => v.status === "ADOPTED").sort((a, b) => b.version - a.version));
        } catch {
          if (!cancelled) setFlowVersions([]);
        }
      } else {
        try {
          const data = await api.listFunctionVersions(componentId, 1, 100);
          if (!cancelled) setFunctionVersions(data.items.filter((v) => v.status === "READY").sort((a, b) => b.version - a.version));
        } catch {
          if (!cancelled) setFunctionVersions([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [isSubFlow, componentId]);

  const parentOptions = isSubFlow ? flows : functions;
  const versionOptions = isSubFlow ? flowVersions : functionVersions;
  const parentLabel = isSubFlow ? "Flow" : "Function";

  return (
    <>
      <label className={fieldClass}>
        <span className={labelClass}>{parentLabel}</span>
        <NativeSelect
          required
          disabled={!editable || !parentOptions}
          value={componentId}
          onChange={(e) => onChange(e.target.value, "")}
          className="w-full"
        >
          <option value="" disabled>
            {parentOptions ? `Select a ${parentLabel.toLowerCase()}…` : "Loading…"}
          </option>
          {parentOptions?.map((item) =>
            isSubFlow ? (
              <option key={item.id} value={item.id}>
                {(item as FlowResponse).name}
              </option>
            ) : (
              <option key={item.id} value={item.id}>
                {(item as FunctionResponse).name} ({(item as FunctionResponse).functionKey})
              </option>
            )
          )}
          {componentId && parentOptions && !parentOptions.some((item) => item.id === componentId) && (
            <option value={componentId}>{componentId} (not in your list)</option>
          )}
        </NativeSelect>
      </label>

      <label className={fieldClass}>
        <span className={labelClass}>Version</span>
        <NativeSelect
          required
          disabled={!editable || !componentId || !versionOptions}
          value={componentVersionId}
          onChange={(e) => onChange(componentId, e.target.value)}
          className="w-full"
        >
          <option value="" disabled>
            {!componentId
              ? `Select a ${parentLabel.toLowerCase()} first`
              : versionOptions
                ? versionOptions.length === 0
                  ? isSubFlow
                    ? "No ADOPTED versions"
                    : "No READY versions"
                  : "Select a version…"
                : "Loading…"}
          </option>
          {versionOptions?.map((v) => (
            <option key={v.id} value={v.id}>
              v{v.version}
            </option>
          ))}
          {componentVersionId && versionOptions && !versionOptions.some((v) => v.id === componentVersionId) && (
            <option value={componentVersionId}>{componentVersionId} (not in your list)</option>
          )}
        </NativeSelect>
      </label>
    </>
  );
}

interface TestFlowPanelProps {
  flowId: string;
  versionId: string;
  onClose: () => void;
  onError: (message: string) => void;
}

function TestFlowPanel({ flowId, versionId, onClose, onError }: TestFlowPanelProps) {
  const [input, setInput] = useState("{}");
  const [busy, setBusy] = useState(false);
  const [invocationId, setInvocationId] = useState<string | null>(null);
  const [initialStatus, setInitialStatus] = useState<string | null>(null);
  const [inspection, setInspection] = useState<InvocationInspectionResponse | null>(null);
  const [inspecting, setInspecting] = useState(false);
  const currentStatus = inspection?.status ?? initialStatus ?? "PENDING";

  const inputError = useMemo(() => {
    if (input.trim() === "") return null;
    try {
      JSON.parse(input);
      return null;
    } catch (err) {
      return err instanceof Error ? err.message : "Invalid JSON";
    }
  }, [input]);

  // Runs this exact FlowVersion's own steps directly - no Gateway/HTTP/TLS
  // hop - so execution is asynchronous just like a real routed request.
  // Poll a few times so the user sees it actually complete.
  useEffect(() => {
    if (!invocationId) return;
    let cancelled = false;
    let attempts = 0;
    const timer = setInterval(async () => {
      attempts += 1;
      try {
        const data = await api.getInvocation(invocationId);
        if (cancelled) return;
        setInspection(data);
        if (data.status !== "PENDING" || attempts >= 10) {
          clearInterval(timer);
        }
      } catch {
        clearInterval(timer);
      }
    }, 1000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [invocationId]);

  async function handleRun() {
    onError("");
    setInspection(null);
    setBusy(true);
    try {
      const result = await api.invokeFlowVersion(flowId, versionId, input);
      setInvocationId(result.invocationId);
      setInitialStatus(result.initialStatus);
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to invoke flow");
    } finally {
      setBusy(false);
    }
  }

  async function handleInspect() {
    if (!invocationId) return;
    setInspecting(true);
    try {
      const data = await api.getInvocation(invocationId);
      setInspection(data);
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to inspect invocation");
    } finally {
      setInspecting(false);
    }
  }

  return (
    <aside className="flex w-full max-w-96 shrink-0 flex-col overflow-y-auto border-l border-border bg-surface">
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <div className="flex items-center gap-2">
          <ZapIcon className="h-4 w-4 text-warning" />
          <p className="text-sm font-medium text-foreground">Test flow</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="flex h-7 w-7 cursor-pointer items-center justify-center rounded-lg text-muted-foreground hover:bg-surface-hover hover:text-foreground"
        >
          <XIcon className="h-4 w-4" />
        </button>
      </div>

      <div className="flex flex-1 flex-col gap-4 p-4">
        <p className="text-xs text-muted-foreground">
          Runs this exact version&apos;s own steps directly, with no Gateway, HTTP route, or TLS involved -
          the same Dispatcher/Runtime path a real request to this route would use. Works on DRAFT versions too,
          so you can test before adopting.
        </p>

        <JsonEditor value={input} onChange={setInput} error={inputError} label="Input payload (JSON)" />

        <div>
          <Button variant="primary" size="sm" disabled={busy || !!inputError} onClick={handleRun}>
            <PlayIcon className="h-3.5 w-3.5" />
            Run
          </Button>
        </div>

        {invocationId && (
          <div className="rounded-lg border border-border bg-background p-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 text-xs">
                <CheckIcon className="h-3.5 w-3.5 text-success" />
                <span className="text-muted-foreground">Invocation status</span>
                <StatusBadge status={currentStatus} />
              </div>
              <Button variant="secondary" size="sm" disabled={inspecting} onClick={handleInspect}>
                Inspect
              </Button>
            </div>
            <p className="mt-1.5 break-all font-mono text-xs text-muted-foreground">{invocationId}</p>

            {inspection && (
              <div className="mt-3 border-t border-border pt-3">
                <dl className="grid gap-1.5 text-xs">
                  <div className="flex gap-2">
                    <dt className="w-20 shrink-0 text-muted-foreground">Status</dt>
                    <dd>
                      <StatusBadge status={inspection.status} />
                    </dd>
                  </div>
                  <div className="flex gap-2">
                    <dt className="w-20 shrink-0 text-muted-foreground">Input</dt>
                    <dd className="break-all font-mono text-foreground">{inspection.inputPayload}</dd>
                  </div>
                  {inspection.result && (
                    <div className="flex gap-2">
                      <dt className="w-20 shrink-0 text-muted-foreground">Response</dt>
                      <dd className="break-all font-mono text-foreground">{inspection.result}</dd>
                    </div>
                  )}
                  {inspection.error && (
                    <div className="flex gap-2">
                      <dt className="w-20 shrink-0 text-muted-foreground">Error</dt>
                      <dd className="break-all font-mono text-danger">{inspection.error}</dd>
                    </div>
                  )}
                </dl>

                <OutputLog steps={inspection.steps} />
              </div>
            )}
          </div>
        )}
      </div>
    </aside>
  );
}
