# FuncHole

FuncHole is a platform for building and running serverless applications: users define Functions, compose them into Flows, and expose them over HTTP through Gateways. The Control Plane manages desired state; the Data/Execution Plane serves live traffic.

## Language

### Composition

**Flow**:
A logical composition definition: what should execute and in what order, such as Function calls, retries, branching, and transformations. A Flow is the logical identity; its executable content lives in Flow Versions.
_Avoid_: workflow, pipeline

**Flow Route**:
The mapping between an HTTP request (method + path) and a Flow, e.g. `POST /checkout → flw_checkout`. Belongs to a Gateway.
_Avoid_: route, endpoint mapping

**Flow Version**:
A revision of a Flow's composition, editable while being prepared and immutable once adopted for live traffic. Different Flow Versions can reference different Function Versions.

### Code

**Function**:
A logical unit of executable business logic (e.g. `authenticate-user`, `create-order`). The identity/container for a piece of code, not a running process.
_Avoid_: lambda, handler, service

**Function Version**:
A particular version of a Function's code and configuration, e.g. `create-order@4`.

**Artifact**:
The built, packageable output produced from Function source code (bundled JavaScript, Node SEA, or another executable format). This is what a Runtime actually executes.
_Avoid_: bundle, build, package

### Execution

**Invocation**:
A single execution instance of a Flow or Function, with lifecycle state (`PENDING → RUNNING → SUCCEEDED/FAILED`), IDs, timestamps, and errors. 1,000 requests to one Flow ≈ 1,000 separate Invocations.
_Avoid_: execution, run, request

**Invocation Registry**:
The system responsible for creating and tracking Invocation records and state: what is this execution, which Flow Version is it running, what is its current status.

**Dispatcher**:
The component between orchestration and execution capacity. Decides where an Invocation should execute and dispatches the work to an appropriate Runtime.
_Avoid_: scheduler, load balancer

**Runtime**:
An execution environment capable of running an Artifact (e.g. the Node.js Runtime). Runtime is about *how* code executes; Function is about *what* code executes.
_Avoid_: worker, executor, container

**Runtime Registry**:
Keeps track of available Runtime instances and their capabilities so the Dispatcher can find somewhere appropriate to execute.

### Entry

**Gateway**:
The external HTTP entry point. Receives incoming requests, identifies the matching Flow Route, and forwards the request into the execution pipeline: the traffic entry and routing boundary.
_Avoid_: API gateway, ingress, reverse proxy

### Planes

**Control Plane**:
The management side of FuncHole: Functions, Versions, Flows, Gateways, deployments, configuration, and metadata are created and managed here. It defines desired state and does not serve Function traffic itself.

**Data/Execution Plane**:
The request-processing side: Gateway → Flow resolution → Invocation → Dispatcher → Runtime. This is where actual user traffic and Function execution happen.
_Avoid_: data plane alone (ambiguous with analytics/data pipelines)
