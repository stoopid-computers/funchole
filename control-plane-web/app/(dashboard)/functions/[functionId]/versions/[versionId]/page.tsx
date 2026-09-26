"use client";

import Link from "next/link";
import { NativeSelect } from "@/components/ui/native-select";
import { useParams, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useRef, useState, type ChangeEvent } from "react";
import CodeMirror, { EditorView } from "@uiw/react-codemirror";
import { syntaxHighlighting } from "@codemirror/language";
import { StatusBadge } from "@/components/StatusBadge";
import { panelClass, Panel } from "@/components/Panel";
import { Button } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { FunctionLifecycleDiagram } from "@/components/FunctionLifecycleDiagram";
import { LogTraceConsole } from "@/components/LogTraceConsole";
import {
  codeSyntaxColorsDark,
  codeSyntaxColorsLight,
  editorChrome,
  JsonEditor,
  languageForPath,
  useIsDarkMode,
} from "@/components/CodeEditor";
import {
  ArrowLeftIcon,
  ChevronRightIcon,
  UploadIcon,
  PlayIcon,
  ZapIcon,
  KeyIcon,
  DatabaseIcon,
  PlusIcon,
  TrashIcon,
  XIcon,
} from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type {
  DatabaseResponse,
  FunctionResponse,
  FunctionVersionConfigResponse,
  FunctionVersionDatabaseAttachmentResponse,
  FunctionVersionResponse,
  FunctionVersionSourceResponse,
  InvocationInspectionResponse,
} from "@/lib/types";

const DEFAULT_SOURCE = `export async function handler(input) {
  return { status: 200, body: { ok: true, input } };
}
`;

export default function FunctionVersionDetailPage() {
  const params = useParams<{ functionId: string; versionId: string }>();
  const { functionId, versionId } = params;
  const copyFrom = useSearchParams().get("copyFrom");

  const [fn, setFn] = useState<FunctionResponse | null>(null);
  const [version, setVersion] = useState<FunctionVersionResponse | null>(null);
  const [source, setSource] = useState<FunctionVersionSourceResponse | null>(null);
  const [sourceLoaded, setSourceLoaded] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [deployErrorDetails, setDeployErrorDetails] = useState<string[] | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setDeployErrorDetails(null);
      try {
        const fnData = await api.getFunction(functionId);
        if (!cancelled) setFn(fnData);
      } catch {
        if (!cancelled) setError("Failed to load function");
      }
      try {
        const versionData = await api.getFunctionVersion(functionId, versionId);
        if (!cancelled) setVersion(versionData);
      } catch {
        if (!cancelled) setError("Failed to load version");
      }
      try {
        const sourceData = await api.getFunctionVersionSource(functionId, versionId);
        if (!cancelled) setSource(sourceData);
      } catch {
        if (!cancelled) setSource(null);
      } finally {
        if (!cancelled) setSourceLoaded(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [functionId, versionId, reloadKey]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  async function handleDeploy() {
    setError(null);
    setDeployErrorDetails(null);
    setBusy(true);
    try {
      await api.deployFunctionVersion(functionId, versionId);
      refresh();
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        if (err.details.length > 0) setDeployErrorDetails(err.details);
      } else {
        setError("Failed to deploy");
      }
      refresh();
    } finally {
      setBusy(false);
    }
  }

  if (!fn || !version) {
    return <p className="p-6 text-sm text-muted-foreground">Loading…</p>;
  }

  const isDraft = version.status === "DRAFT";

  return (
    <div className="flex flex-col gap-6">
      <nav className="flex items-center gap-1.5 text-sm text-muted-foreground">
        <Link href="/functions" className="hover:text-foreground">
          Functions
        </Link>
        <ChevronRightIcon className="h-3.5 w-3.5" />
        <Link href={`/functions/${functionId}`} className="font-medium text-foreground hover:text-muted-strong">
          {fn.name}
        </Link>
        <ChevronRightIcon className="h-3.5 w-3.5" />
        <span className="font-mono text-foreground">v{version.version}</span>
      </nav>

      <div className="flex items-start justify-between gap-4">
        <div className="flex items-start gap-3">
          <Link
            href={`/functions/${functionId}`}
            className="mt-1 flex h-8 w-8 items-center justify-center rounded-lg border border-border text-muted-foreground hover:bg-surface-hover hover:text-foreground"
          >
            <ArrowLeftIcon className="h-4 w-4" />
          </Link>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-mono text-2xl font-medium tracking-tight text-foreground">v{version.version}</h1>
              <StatusBadge status={version.status} />
            </div>
            <p className="mt-1 text-xs text-muted-foreground">{version.runtime} runtime</p>
          </div>
        </div>
        {isDraft && (
          <Button variant="primary" onClick={handleDeploy} disabled={busy || !source}>
            <PlayIcon className="h-4 w-4" />
            Deploy
          </Button>
        )}
      </div>

      {error && (
        <div className="rounded-xl border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
          <p role="alert">{error}</p>
          {deployErrorDetails && (
            <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-xl bg-black/30 p-2 font-mono text-xs">
              {deployErrorDetails.join("\n")}
            </pre>
          )}
        </div>
      )}

      {version.status === "FAILED" && !deployErrorDetails && (
        <p className="rounded-xl border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
          This version failed to build. Build output isn&apos;t persisted, so it&apos;s only shown right after a deploy
          attempt in this session. Create a new draft version to try again.
        </p>
      )}

      <FunctionLifecycleDiagram status={version.status} hasSource={!!source} hasArtifact={!!version.artifactObjectKey || !!version.artifactSha256} />

      <SourcePanel
        functionId={functionId}
        versionId={versionId}
        source={source}
        sourceLoaded={sourceLoaded}
        isDraft={isDraft}
        copyFrom={copyFrom}
        onSubmitted={refresh}
        onError={setError}
      />

      <ConfigPanel functionId={functionId} versionId={versionId} onError={setError} />

      <DatabasesPanel functionId={functionId} versionId={versionId} onError={setError} />

      {version.status === "READY" && (
        <TestInvokePanel functionId={functionId} versionId={versionId} onError={setError} />
      )}

      {version.artifactSha256 && (
        <Panel className="p-4">
          <p className="text-sm font-medium text-foreground">Artifact</p>
          <dl className="mt-2 grid gap-1.5 text-xs">
            <div className="flex gap-2">
              <dt className="w-28 shrink-0 text-muted-foreground">SHA-256</dt>
              <dd className="break-all font-mono text-foreground">{version.artifactSha256}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="w-28 shrink-0 text-muted-foreground">Size</dt>
              <dd className="text-foreground">{version.artifactSizeBytes} bytes</dd>
            </div>
            <div className="flex gap-2">
              <dt className="w-28 shrink-0 text-muted-foreground">Published</dt>
              <dd className="text-foreground">
                {version.artifactPublishedAt ? new Date(version.artifactPublishedAt).toLocaleString() : "—"}
              </dd>
            </div>
          </dl>
        </Panel>
      )}
    </div>
  );
}

interface SourcePanelProps {
  functionId: string;
  versionId: string;
  source: FunctionVersionSourceResponse | null;
  sourceLoaded: boolean;
  isDraft: boolean;
  copyFrom: string | null;
  onSubmitted: () => void;
  onError: (message: string) => void;
}

interface SourceFileDraft {
  path: string;
  content: string;
}

const DEFAULT_ENTRYPOINT = "index.mjs";
const DEFAULT_FILES: SourceFileDraft[] = [{ path: DEFAULT_ENTRYPOINT, content: DEFAULT_SOURCE }];

function SourcePanel({ functionId, versionId, source, sourceLoaded, isDraft, copyFrom, onSubmitted, onError }: SourcePanelProps) {
  const [editing, setEditing] = useState(false);
  const [files, setFiles] = useState<SourceFileDraft[]>(DEFAULT_FILES);
  const [activePath, setActivePath] = useState(DEFAULT_ENTRYPOINT);
  const [entrypoint, setEntrypoint] = useState(DEFAULT_ENTRYPOINT);
  const [handler, setHandler] = useState("handler");
  const [addingFile, setAddingFile] = useState(false);
  const [newFileName, setNewFileName] = useState("");
  const [busy, setBusy] = useState(false);
  const [copying, setCopying] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const isDark = useIsDarkMode();

  // Derive the initial editing mode - and, when replacing an existing
  // submission, seed the file list from its known paths/entrypoint/handler -
  // once the real fetch settles, adjusting state during render (React's
  // documented pattern for this) rather than in an effect. `initializedFor`
  // guards it to fire only on that one loading->loaded transition, so a
  // user's own "Replace source"/"Cancel" toggle is never overwritten by a
  // later refresh. Note the backend only ever returns file paths, never
  // content, so re-editing an EXISTING submission starts those files empty -
  // there is no way to read back what was previously submitted for THIS
  // version. A brand new version with nothing of its own yet and a
  // `copyFrom` id (set by "New draft version from this one") instead fetches
  // that other version's real file content below, via a separate effect.
  const [initializedFor, setInitializedFor] = useState(false);
  if (sourceLoaded && !initializedFor) {
    setInitializedFor(true);
    setEditing(!source && isDraft);
    if (source) {
      const seeded = source.relativePaths.map((path) => ({ path, content: "" }));
      setFiles(seeded.length > 0 ? seeded : DEFAULT_FILES);
      setActivePath(source.entrypoint || seeded[0]?.path || DEFAULT_ENTRYPOINT);
      setEntrypoint(source.entrypoint);
      setHandler(source.handler);
    }
  }

  useEffect(() => {
    if (!sourceLoaded || source || !copyFrom) return;
    let cancelled = false;
    (async () => {
      setCopying(true);
      try {
        const data = await api.getFunctionVersionSourceFiles(functionId, copyFrom);
        if (cancelled) return;
        const copied = data.files.map((f) => ({ path: f.path, content: f.content }));
        setFiles(copied.length > 0 ? copied : DEFAULT_FILES);
        setActivePath(data.entrypoint || copied[0]?.path || DEFAULT_ENTRYPOINT);
        setEntrypoint(data.entrypoint || copied[0]?.path || DEFAULT_ENTRYPOINT);
        setHandler(data.handler || "handler");
      } catch (err) {
        if (!cancelled) onError(err instanceof ApiError ? err.message : "Failed to copy source from the selected version");
      } finally {
        if (!cancelled) setCopying(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [sourceLoaded, source, copyFrom, functionId, onError]);

  if (!sourceLoaded) {
    return (
      <Panel className="p-4">
        <p className="text-sm text-muted-foreground">Loading source…</p>
      </Panel>
    );
  }

  const activeFile = files.find((f) => f.path === activePath) ?? files[0];

  function updateActiveContent(content: string) {
    setFiles((prev) => prev.map((f) => (f.path === activePath ? { ...f, content } : f)));
  }

  function handleAddFile() {
    const name = newFileName.trim();
    if (!name) {
      setAddingFile(false);
      return;
    }
    if (files.some((f) => f.path === name)) {
      onError(`A file named "${name}" already exists`);
      return;
    }
    setFiles((prev) => [...prev, { path: name, content: "" }]);
    setActivePath(name);
    setNewFileName("");
    setAddingFile(false);
  }

  function handleRemoveFile(path: string) {
    if (files.length <= 1) return;
    const remaining = files.filter((f) => f.path !== path);
    setFiles(remaining);
    if (activePath === path) setActivePath(remaining[0].path);
    if (entrypoint === path) setEntrypoint(remaining[0].path);
  }

  async function handleFilesPicked(event: ChangeEvent<HTMLInputElement>) {
    const picked = event.target.files;
    if (!picked || picked.length === 0) return;
    const entries = await Promise.all(
      Array.from(picked).map(async (file) => ({ path: file.name, content: await file.text() }))
    );
    setFiles((prev) => {
      const merged = [...prev];
      for (const entry of entries) {
        const existingIndex = merged.findIndex((f) => f.path === entry.path);
        if (existingIndex >= 0) merged[existingIndex] = entry;
        else merged.push(entry);
      }
      return merged;
    });
    setActivePath(entries[0].path);
    event.target.value = "";
  }

  async function handleSubmit() {
    onError("");
    setBusy(true);
    try {
      const fileObjects = files.map((f) => new File([f.content], f.path));
      await api.submitFunctionVersionSource(functionId, versionId, fileObjects, entrypoint, handler);
      setEditing(false);
      onSubmitted();
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to submit source");
    } finally {
      setBusy(false);
    }
  }

  if (!editing) {
    return (
      <Panel className="overflow-hidden p-0">
        <div className="flex flex-col gap-3 border-b border-border p-5 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="eyebrow">Source workspace</p>
            <h2 className="mt-1 text-base font-medium tracking-tight text-foreground">Review generated code before deploy</h2>
          </div>
          {isDraft && (
            <Button variant="secondary" size="sm" onClick={() => setEditing(true)}>
              <UploadIcon className="h-3.5 w-3.5" />
              Replace source
            </Button>
          )}
        </div>
        {source ? (
          <div className="grid gap-4 p-5 lg:grid-cols-[240px_1fr]">
            <div className="rounded-xl border border-border bg-surface-2/55 p-3">
              <p className="eyebrow">Files</p>
              <div className="mt-3 space-y-1">
                {source.relativePaths.map((path) => (
                  <div key={path} className="rounded-xl border border-border bg-surface px-3 py-2 font-mono text-xs text-muted-strong">
                    {path}
                  </div>
                ))}
              </div>
            </div>
            <dl className="grid content-start gap-3 text-sm">
              <div className="rounded-xl border border-border bg-surface-2/55 p-3">
                <dt className="eyebrow">Entrypoint</dt>
                <dd className="mt-1 font-mono text-foreground">{source.entrypoint}</dd>
              </div>
              <div className="rounded-xl border border-border bg-surface-2/55 p-3">
                <dt className="eyebrow">Handler</dt>
                <dd className="mt-1 font-mono text-foreground">{source.handler}</dd>
              </div>
              {!isDraft && (
                <div className="rounded-xl border border-border bg-surface-2/55 p-3 text-sm text-muted-foreground">
                  This version is immutable. Create a new draft if you need to patch source.
                </div>
              )}
            </dl>
          </div>
        ) : (
          <div className="p-5">
            <div className="rounded-xl border border-dashed border-border bg-surface/50 p-5">
              <p className="text-sm font-medium text-foreground">No source submitted yet.</p>
              <p className="mt-1 text-sm leading-6 text-muted-foreground">
                Submit source files manually or let your coding agent prepare the first version through MCP.
              </p>
            </div>
          </div>
        )}
      </Panel>
    );
  }

  return (
    <div className={`${panelClass} overflow-hidden p-0`}>
      <div className="flex flex-col gap-3 border-b border-border p-5 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="eyebrow">Source editor</p>
          <h2 className="mt-1 text-base font-medium tracking-tight text-foreground">Prepare runnable source</h2>
          {copying && <span className="text-xs text-muted-foreground">Copying files from the previous version…</span>}
        </div>
        <div className="flex gap-2">
          <input ref={fileInputRef} type="file" multiple className="hidden" onChange={handleFilesPicked} />
          <Button variant="secondary" size="sm" disabled={copying} onClick={() => fileInputRef.current?.click()}>
            <UploadIcon className="h-3.5 w-3.5" />
            Upload file(s)
          </Button>
        </div>
      </div>

      <div className="grid gap-4 border-b border-border p-5 sm:grid-cols-2">
        <label className={fieldClass}>
          <span className={labelClass}>Entrypoint</span>
          <NativeSelect value={entrypoint} onChange={(e) => setEntrypoint(e.target.value)} className="w-full">
            {files.map((f) => (
              <option key={f.path} value={f.path}>
                {f.path}
              </option>
            ))}
          </NativeSelect>
        </label>
        <label className={fieldClass}>
          <span className={labelClass}>Handler (exported function name)</span>
          <input
            type="text"
            value={handler}
            onChange={(e) => setHandler(e.target.value)}
            className={`${inputClass} font-mono`}
          />
        </label>
      </div>

      <div className="grid min-h-[24rem] lg:grid-cols-[260px_1fr]">
        <aside className="border-b border-border bg-background p-4 lg:border-b-0 lg:border-r">
          <div className="mb-3 flex items-center justify-between">
            <span className={labelClass}>Files</span>
            {!addingFile && (
              <button type="button" onClick={() => setAddingFile(true)} className="text-xs font-medium text-muted-foreground hover:text-foreground">
                New
              </button>
            )}
          </div>
          <div className="space-y-1.5">
            {files.map((f) => (
              <div
                role="button"
                tabIndex={0}
                key={f.path}
                onClick={() => setActivePath(f.path)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    setActivePath(f.path);
                  }
                }}
                className={`group flex w-full items-center justify-between gap-2 rounded-xl border px-3 py-2 text-left text-xs font-mono transition-colors ${
                  f.path === activePath
                    ? "border-border-strong bg-white/[0.06] text-foreground"
                    : "border-border bg-surface/70 text-muted-foreground hover:text-foreground"
                }`}
              >
                <span className="truncate">{f.path}</span>
                <span className="flex items-center gap-1">
                  {f.path === entrypoint && <span className="rounded-full bg-surface px-1.5 py-0.5 text-[9px] uppercase tracking-wide text-muted-foreground">entry</span>}
                  {files.length > 1 && (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        handleRemoveFile(f.path);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" || e.key === " ") {
                          e.preventDefault();
                          e.stopPropagation();
                          handleRemoveFile(f.path);
                        }
                      }}
                      aria-label={`Remove ${f.path}`}
                      className="cursor-pointer text-muted-foreground hover:text-danger"
                    >
                      <XIcon className="h-3 w-3" />
                    </button>
                  )}
                </span>
              </div>
            ))}
          </div>
          {addingFile && (
            <div className="mt-3 flex flex-col gap-2 rounded-xl border border-border bg-surface/70 p-3">
              <input
                autoFocus
                type="text"
                value={newFileName}
                onChange={(e) => setNewFileName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") handleAddFile();
                  if (e.key === "Escape") {
                    setAddingFile(false);
                    setNewFileName("");
                  }
                }}
                placeholder="package.json"
                className={`${inputClass} font-mono text-xs`}
              />
              <div className="flex gap-2">
                <Button variant="primary" size="sm" onClick={handleAddFile}>Add</Button>
                <Button variant="secondary" size="sm" onClick={() => setAddingFile(false)}>Cancel</Button>
              </div>
            </div>
          )}
        </aside>

        <div className="flex min-w-0 flex-col">
          <div className="flex items-center justify-between border-b border-border bg-surface/70 px-4 py-3">
            <span className="font-mono text-xs text-muted-strong">{activeFile?.path ?? "Code"}</span>
            <span className="text-xs text-muted-foreground">{activeFile?.content.length ?? 0} chars</span>
          </div>
          <div className="overflow-hidden bg-background focus-within:ring-1 focus-within:ring-ring">
            <CodeMirror
              value={activeFile?.content ?? ""}
              onChange={updateActiveContent}
              extensions={[
                languageForPath(activeFile?.path ?? ""),
                syntaxHighlighting(isDark ? codeSyntaxColorsDark : codeSyntaxColorsLight),
                editorChrome,
                EditorView.lineWrapping,
              ]}
              theme="none"
              basicSetup={{ highlightActiveLine: true }}
              minHeight="21rem"
            />
          </div>
        </div>
      </div>

      <div className="flex gap-2 border-t border-border p-5">
        <Button variant="primary" size="sm" disabled={busy || !entrypoint} onClick={handleSubmit}>
          Submit source
        </Button>
        {source && (
          <Button variant="secondary" size="sm" onClick={() => setEditing(false)}>
            Cancel
          </Button>
        )}
      </div>
    </div>
  );
}

interface ConfigPanelProps {
  functionId: string;
  versionId: string;
  onError: (message: string) => void;
}

function ConfigPanel({ functionId, versionId, onError }: ConfigPanelProps) {
  const [config, setConfig] = useState<FunctionVersionConfigResponse | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.getFunctionVersionConfig(functionId, versionId);
        if (!cancelled) setConfig(data);
      } catch (err) {
        if (!cancelled) onError(err instanceof ApiError ? err.message : "Failed to load configuration");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [functionId, versionId, reloadKey, onError]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  return (
    <Panel className="flex flex-col gap-5 p-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <KeyIcon className="h-4 w-4 text-subtle" />
            <p className="eyebrow">Effective runtime context</p>
          </div>
          <h2 className="mt-2 text-base font-medium tracking-tight text-foreground">Environment &amp; secrets</h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
            Direct FunctionVersion attachments are injected into <code className="font-mono">process.env</code> whenever this exact version runs.
            Secrets are stored encrypted and never shown again after saving.
          </p>
        </div>
        <span className="rounded-md border border-border px-2 py-1 eyebrow">
          Direct scope
        </span>
      </div>

      <ConfigList
        title="Environment variables"
        entries={config?.envVars.map((v) => ({ key: v.key, display: v.value })) ?? null}
        placeholderValue="production"
        onSave={async (key, value) => {
          await api.upsertFunctionVersionEnvVar(functionId, versionId, key, value);
          refresh();
        }}
        onError={onError}
      />

      <ConfigList
        title="Secrets"
        entries={config?.secrets.map((s) => ({ key: s.key, display: s.secretRef })) ?? null}
        placeholderValue="super-secret-value"
        secret
        onSave={async (key, value) => {
          await api.upsertFunctionVersionSecret(functionId, versionId, key, value);
          refresh();
        }}
        onError={onError}
      />
    </Panel>
  );
}

interface ConfigListProps {
  title: string;
  entries: { key: string; display: string }[] | null;
  placeholderValue: string;
  secret?: boolean;
  onSave: (key: string, value: string) => Promise<void>;
  onError: (message: string) => void;
}

function ConfigList({ title, entries, placeholderValue, secret, onSave, onError }: ConfigListProps) {
  const [adding, setAdding] = useState(false);
  const [key, setKey] = useState("");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);

  async function handleSave() {
    onError("");
    setBusy(true);
    try {
      await onSave(key.trim(), value);
      setKey("");
      setValue("");
      setAdding(false);
    } catch (err) {
      onError(err instanceof ApiError ? err.message : `Failed to save ${title.toLowerCase()}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface-2/45">
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <p className="text-sm font-semibold text-foreground">{title}</p>
        {!adding && (
          <Button variant="secondary" size="sm" onClick={() => setAdding(true)}>
            <PlusIcon className="h-3.5 w-3.5" />
            Add
          </Button>
        )}
      </div>

      {entries === null ? (
        <p className="px-4 py-4 text-sm text-muted-foreground">Loading…</p>
      ) : entries.length === 0 && !adding ? (
        <div className="px-4 py-5">
          <p className="text-sm font-medium text-foreground">None attached directly.</p>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">Attach here only when this function version needs values that should not apply to the whole flow.</p>
        </div>
      ) : (
        <ul className="divide-y divide-border">
          {entries.map((entry) => (
            <li key={entry.key} className="flex items-center gap-3 px-4 py-3 text-xs">
              <span className="w-40 shrink-0 truncate font-mono font-medium text-foreground">{entry.key}</span>
              <span className="truncate font-mono text-muted-foreground">{secret ? `configured (${entry.display})` : entry.display}</span>
              <span className="ml-auto rounded-md border border-border bg-surface px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground">Direct</span>
            </li>
          ))}
        </ul>
      )}

      {adding && (
        <div className="flex flex-wrap items-end gap-2 border-t border-border px-4 py-3">
          <label className={fieldClass}>
            <span className={labelClass}>Key</span>
            <input
              type="text"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              placeholder="API_KEY"
              className={`${inputClass} w-40 font-mono text-xs`}
            />
          </label>
          <label className={fieldClass}>
            <span className={labelClass}>Value</span>
            <input
              type={secret ? "password" : "text"}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder={placeholderValue}
              className={`${inputClass} w-48 font-mono text-xs`}
            />
          </label>
          <Button variant="primary" size="sm" disabled={busy || !key.trim()} onClick={handleSave}>
            Save
          </Button>
          <Button variant="secondary" size="sm" onClick={() => setAdding(false)}>
            Cancel
          </Button>
        </div>
      )}
    </div>
  );
}

interface DatabasesPanelProps {
  functionId: string;
  versionId: string;
  onError: (message: string) => void;
}

function DatabasesPanel({ functionId, versionId, onError }: DatabasesPanelProps) {
  const [attached, setAttached] = useState<FunctionVersionDatabaseAttachmentResponse[] | null>(null);
  const [available, setAvailable] = useState<DatabaseResponse[] | null>(null);
  const [selected, setSelected] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [attachments, databases] = await Promise.all([
          api.listFunctionVersionDatabases(functionId, versionId),
          api.listDatabases(1, 100),
        ]);
        if (!cancelled) {
          setAttached(attachments);
          setAvailable(databases.items);
        }
      } catch (err) {
        if (!cancelled) onError(err instanceof ApiError ? err.message : "Failed to load databases");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [functionId, versionId, reloadKey, onError]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  async function handleAttach() {
    if (!selected) return;
    onError("");
    setBusy(true);
    try {
      await api.attachFunctionVersionDatabase(functionId, versionId, selected);
      setSelected("");
      refresh();
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to attach database");
    } finally {
      setBusy(false);
    }
  }

  async function handleDetach(databaseId: string) {
    onError("");
    try {
      await api.detachFunctionVersionDatabase(functionId, versionId, databaseId);
      refresh();
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to detach database");
    }
  }

  const attachableDatabases = (available ?? []).filter(
    (db) => !attached?.some((a) => a.databaseId === db.id)
  );

  return (
    <Panel className="flex flex-col gap-5 p-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <DatabaseIcon className="h-4 w-4 text-subtle" />
            <p className="eyebrow">Database attachments</p>
          </div>
          <h2 className="mt-2 text-base font-medium tracking-tight text-foreground">Direct FunctionVersion databases</h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
            Attach here when only this function version needs a database. Attach at Flow level when every step should inherit it.
          </p>
        </div>
        <code className="rounded-md border border-border bg-surface px-1.5 py-0.5 font-mono text-xs text-muted-strong">context.db(&quot;name&quot;)</code>
      </div>

      <div className="overflow-hidden rounded-xl border border-border bg-surface-2/45">
        {attached === null ? (
          <p className="px-4 py-4 text-sm text-muted-foreground">Loading…</p>
        ) : attached.length === 0 ? (
          <div className="px-4 py-5">
            <p className="text-sm font-medium text-foreground">No direct databases attached.</p>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">If this function needs a shared database, attach it here or inherit one from a Flow later.</p>
          </div>
        ) : (
          <ul className="divide-y divide-border">
            {attached.map((a) => (
              <li key={a.id} className="flex items-center gap-3 px-4 py-3 text-xs">
                <span className="w-40 shrink-0 truncate font-mono font-medium text-foreground">{a.databaseName}</span>
                <span className="flex-1 truncate text-muted-foreground">{a.databaseType}</span>
                <span className="rounded-md border border-border bg-surface px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground">Direct</span>
                <Button variant="danger" size="icon" title="Detach" onClick={() => handleDetach(a.databaseId)}>
                  <TrashIcon className="h-3.5 w-3.5" />
                </Button>
              </li>
            ))}
          </ul>
        )}

        <div className="flex flex-wrap items-end gap-2 border-t border-border px-4 py-3">
          <label className={fieldClass}>
            <span className={labelClass}>Database</span>
            <NativeSelect
              value={selected}
              onChange={(e) => setSelected(e.target.value)}
              className="w-full" selectClassName="w-48"
            >
              <option value="">Select a database…</option>
              {attachableDatabases.map((db) => (
                <option key={db.id} value={db.id}>
                  {db.name} ({db.type})
                </option>
              ))}
            </NativeSelect>
          </label>
          <Button variant="primary" size="sm" disabled={busy || !selected} onClick={handleAttach}>
            <PlusIcon className="h-3.5 w-3.5" />
            Attach
          </Button>
        </div>
      </div>
    </Panel>
  );
}

interface TestInvokePanelProps {
  functionId: string;
  versionId: string;
  onError: (message: string) => void;
}

function TestInvokePanel({ functionId, versionId, onError }: TestInvokePanelProps) {
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

  // Execution is asynchronous (the Dispatcher picks the invocation up off
  // NATS), so right after Run it's still PENDING. Poll a few times so the
  // user sees it actually complete instead of assuming it's stuck.
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
      const result = await api.invokeFunctionVersion(functionId, versionId, input);
      setInvocationId(result.invocationId);
      setInitialStatus(result.initialStatus);
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to invoke");
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
    <Panel className="overflow-hidden p-0">
      <div className="grid gap-0 lg:grid-cols-[0.85fr_1.15fr]">
        <div className="border-b border-border p-5 lg:border-b-0 lg:border-r">
          <div className="flex items-center gap-2">
            <ZapIcon className="h-4 w-4 text-subtle" />
            <div>
              <p className="eyebrow">Invoke console</p>
              <h2 className="mt-1 text-base font-medium tracking-tight text-foreground">Direct function test</h2>
            </div>
          </div>
          <p className="mt-3 text-sm leading-6 text-muted-foreground">
            Runs this exact version directly, with no Flow or Gateway involved. Execution still travels through Dispatcher
            and Runtime so the result matches the real execution path.
          </p>

          <div className="mt-4 grid gap-2">
            <PathPill label="Mode" value="Direct Function" />
            <PathPill label="Path" value="FunctionVersion -> Dispatcher -> Runtime" />
          </div>

          <div className="mt-5">
            <JsonEditor value={input} onChange={setInput} error={inputError} minHeight="11rem" />
          </div>

          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="primary" size="sm" disabled={busy || !!inputError} onClick={handleRun}>
              <PlayIcon className="h-3.5 w-3.5" />
              {busy ? "Starting…" : "Run test"}
            </Button>
            <Button variant="secondary" size="sm" onClick={() => setInput("{}")}>
              Reset payload
            </Button>
          </div>
        </div>

        <div className="p-5">
          {invocationId ? (
            <LogTraceConsole
              invocationId={invocationId}
              currentStatus={currentStatus}
              inspection={inspection}
              onRefresh={handleInspect}
              refreshing={inspecting}
            />
          ) : (
            <div className="flex min-h-full items-center justify-center rounded-2xl border border-dashed border-border bg-background p-8">
              <div className="max-w-sm text-center">
                <div className="mx-auto grid h-14 w-14 place-items-center rounded-xl border border-border bg-secondary text-muted-strong">
                  <PlayIcon className="h-6 w-6" />
                </div>
                <p className="mt-4 text-sm font-semibold text-foreground">No invocation yet</p>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">
                  Add a JSON payload and run a test. The trace, logs, result, and failures will appear here.
                </p>
              </div>
            </div>
          )}
        </div>
      </div>
    </Panel>
  );
}

function PathPill({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-border bg-surface-2/55 p-3">
      <p className="eyebrow">{label}</p>
      <p className="mt-1 font-mono text-xs text-muted-strong">{value}</p>
    </div>
  );
}
