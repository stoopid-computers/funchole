# Ship a static site

Use STATIC for browser HTML/CSS/JS, a SPA, or a static-generated site. One Function Version holds the whole site, not one Function per page. `read("funchole://examples/STATIC_MULTIPAGE")` before the first build.

## Source and build contract

Submit text files as `{path, content}` entries, relative to the source root. Use `entrypoint="package.json"`; there is no exported handler. Include a package.json with a build script producing a `dist`, `build`, or `out` directory containing `index.html`. Standard frontend bundlers may be used, provided they emit static files rather than require a persistent application server.

Deployment always runs `npm ci` when a lockfile exists, otherwise `npm install`, followed by `npm run build`. Even dependency-free HTML requires npm. Shell commands in the build script work, as shown by the fixture. There is no build-command override. Missing npm is an operator/environment failure, not a reason to retry with npx or sh.

The fixture includes `index.html`, `about.html`, `blog/index.html`, and `blog/first-post.html` in one submission. Clean paths resolve exact files, then `<path>.html`, then `<path>/index.html`. Missing paths fall back to root index.html for SPA routing. A custom HTML 404 therefore needs app-level handling if that behavior matters.

## Route the artifact

After the Function Version reaches READY:

1. `compose_flow` with a request containing new `key`, `name`, `gatewayRef`, `httpMethod="GET"`, a trailing-wildcard `path` such as `/*` or `/app/*`, and `priority`.
2. Set `runtime="STATIC"` explicitly. Pin the READY Function Version reference in `componentRef`; single-component composition uses FUNCTION for STATIC.
3. Retain the returned Flow Version reference. Inspect existing routes before making it live.
4. `publish_flow` with that `reference` and explicit `expectedActiveVersionRef: null` initially, or the exact current active version reference on updates. The Gateway reads the artifact directly; STATIC does not need a RESPONSE step or runtime Invocation.
5. Check the real root page, nested page, CSS/JS/images, and a refresh on a nested path. An HTTP 200 alone may be the SPA fallback rather than the requested file.

The Gateway injects a base href scoped to the site for relative assets. For assets shared across sites, deploy one STATIC Function and mount it at an explicit subtree such as `/shared/*`; reference `/shared/style.css` with a leading slash. The same component version can be pinned by multiple Flows. Check route overlaps and priorities before adoption. See funchole://guides/flows.

## Update without losing files

Choose the intended `baseVersionRef` explicitly for `build_function` updates. `read(reference, view="source")` lists manifest paths; select `file` to read each file with character `offset` and bounded `maxChars` up to 20000. Submit the entire corrected file set. A partial submission is a replacement, not a patch. Build a new READY artifact and compose a new STATIC Flow Version with the existing `flowRef` and no route fields, then publish with the expected active revision. See funchole://guides/evolve and funchole://guides/troubleshooting.
