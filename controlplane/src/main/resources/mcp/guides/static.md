# Ship a static site

Use STATIC for browser HTML/CSS/JS, a SPA, or a static-generated site. One Function Version holds the whole site, not one Function per page. Fetch `get_function_example("STATIC_MULTIPAGE")` before the first submission.

## Source and build contract

Submit text files as `{path, content}` entries, relative to the source root. Use `entrypoint="package.json"`; there is no exported handler. Include a package.json with a build script producing a `dist`, `build`, or `out` directory containing `index.html`. Standard frontend bundlers may be used, provided they emit static files rather than require a persistent application server.

Deployment always runs `npm ci` when a lockfile exists, otherwise `npm install`, followed by `npm run build`. Even dependency-free HTML requires npm. Shell commands in the build script work, as shown by the fixture. There is no build-command override. Missing npm is an operator/environment failure, not a reason to retry with npx or sh.

The fixture includes `index.html`, `about.html`, `blog/index.html`, and `blog/first-post.html` in one submission. Clean paths resolve exact files, then `<path>.html`, then `<path>/index.html`. Missing paths fall back to root index.html for SPA routing. A custom HTML 404 therefore needs app-level handling if that behavior matters.

## Route the artifact

After the Function Version reaches READY:

1. `create_flow` under the chosen Gateway with `httpMethod="GET"` and a trailing wildcard, such as `/*` for the whole host or `/app/*` for a subtree.
2. `create_flow_version` with `runtime="STATIC"`. Do not accept the NODE default for a STATIC site.
3. `create_flow_step` with `componentType="FUNCTION"`, `position=1`, the Function ID and READY Function Version ID.
4. `adopt_flow_version`. The Gateway reads the artifact directly; STATIC does not need a RESPONSE step or runtime Invocation.
5. Check the real root page, nested page, CSS/JS/images, and a refresh on a nested path. An HTTP 200 alone may be the SPA fallback rather than the requested file.

The Gateway injects a base href scoped to the site for relative assets. For assets shared across sites, deploy one STATIC Function and mount it at an explicit subtree such as `/shared/*`; reference `/shared/style.css` with a leading slash. The same component version can be pinned by multiple Flows. Check route overlaps and priorities before adoption. See funchole://guides/flows.

## Update without losing files

New Function Versions clone the latest source/config by default, including a FAILED version. Read `get_function_version_source` before edits, then submit the entire corrected file set. A partial submission is a replacement, not a patch. Build a new READY artifact and create a new STATIC Flow Version that pins it, then adopt. See funchole://guides/evolve and funchole://guides/troubleshooting.
