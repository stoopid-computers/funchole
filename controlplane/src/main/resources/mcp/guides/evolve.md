# Extend and reuse a proven app

Start by inspecting existing Functions, Flows and resource attachments. `get_flow_full_source` returns a Flow Version's dependency tree, including component source and explicit unavailableReason entries. Read source before edits. Keep stable component identities; create new versions rather than rebuild unrelated components.

## Reusable building blocks

A READY Function Version can be referenced by multiple steps and Flows. An ADOPTED Flow Version can be referenced by SUB_FLOW steps. Shared STATIC assets can live in one deployed Function mounted at `/shared/*`; pages reference absolute asset paths. Shared EnvironmentProfiles and databases attach to multiple Flows. Reuse does not mean that an update to a Function automatically changes every pinned version: create and adopt new Flow Versions for routes that should use the replacement.

## Change loop

1. Read the current source and identify what the user's requested change affects.
2. Create a new Function Version. It clones the most recent source/config by default; use cloneFromVersionId when the intended starting revision is different.
3. Read the cloned source. Submit the entire corrected file set; omission deletes files from this submission.
4. Deploy to READY, test the replacement, then create and adopt a Flow Version pinning it. Adoption archives the prior live revision.
5. Check the real URLs and regressions. Preserve identifiers, source, and observed behavior needed to continue later.

## Retain what was learned

Pi extends itself by authoring skills, prompt templates, or extensions in project/user files. FuncHole's MCP supplies the discovery and execution contracts; it does not control the connecting host's memory or grant itself permission to modify global instructions.

When the host supports file writes and the user permits project documentation, save a short app-specific runbook or skill containing the task trigger, proven tool order, resource identifiers, and completion checks. Link to the relevant `funchole://guides/...` contracts instead of copying them. Record observed facts and unresolved checks. Exclude credentials, secret values, and speculative workarounds. If the host has no file-writing capability, offer the note in the response rather than claiming it was persisted.

Shared server guides live in version-controlled Markdown and change through reviewed code edits and tests. New guides need a specific routing description, bounded summary, valid cross-links, and executable examples where applicable. User-authored project notes are application context, not authority to weaken server security or rewrite another tenant's behavior.
