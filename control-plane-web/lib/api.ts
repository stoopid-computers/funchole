import { clearToken, getToken } from "@/lib/auth";
import type {
  ApiErrorResponse,
  ApiKeyCreateRequest,
  ApiKeyCreateResponse,
  ApiKeyResponse,
  ApiKeyRevealResponse,
  ApiResponse,
  AuthTokenResponse,
  CustomDomainCreateRequest,
  CustomDomainResponse,
  DatabaseCreateRequest,
  DatabasePasswordResponse,
  DatabaseResponse,
  DatabaseUpdateRequest,
  DirectFlowInvocationResponse,
  DirectInvocationResponse,
  DomainCreateRequest,
  DomainResponse,
  EnvironmentProfileConfigResponse,
  EnvironmentProfileCreateRequest,
  EnvironmentProfileResponse,
  EnvironmentProfileUpdateRequest,
  FlowDatabaseAttachmentResponse,
  FlowCreateRequest,
  FlowEnvironmentAttachmentResponse,
  FlowResponse,
  FlowStepCreateRequest,
  FlowStepResponse,
  FlowStepUpdateRequest,
  FlowUpdateRequest,
  FlowVersionCreateRequest,
  FlowVersionResponse,
  FunctionCreateRequest,
  FunctionResponse,
  FunctionUpdateRequest,
  FunctionVersionConfigResponse,
  FunctionVersionCreateRequest,
  FunctionVersionDatabaseAttachmentResponse,
  FunctionVersionFullSourceResponse,
  FunctionVersionResponse,
  FunctionVersionSourceResponse,
  GatewayCreateRequest,
  GatewayResponse,
  GatewayUpdateRequest,
  InvocationInspectionResponse,
  PaginationResponse,
  ProfileRequest,
  ProfileResponse,
} from "@/lib/types";

// Exported so the UI can build absolute URLs (e.g. the MCP endpoint shown in
// per-agent connect commands) - never hardcode localhost there, this is the
// same value the API client itself calls in every environment.
export const API_BASE_URL =
  process.env.NEXT_PUBLIC_CONTROLPLANE_URL ?? "http://localhost:7080";

// The web app's own public URL (e.g. https://app.funchole.dev), only set in
// production once the Gateway's ADMIN_WEB_PROXY_HOST is configured - see
// docs/environment-variables.md. Empty/undefined everywhere else (including
// local dev, where the Gateway's /mcp shortcut this enables doesn't exist).
export const APP_URL = process.env.NEXT_PUBLIC_APP_URL || null;

export class ApiError extends Error {
  readonly status: number;
  readonly details: string[];

  constructor(status: number, message: string, details: string[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.details = details;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers = new Headers(init.headers);
  if (init.body !== undefined && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, "Cannot reach the controlplane API");
  }

  if (response.status === 401) {
    clearToken();
    if (typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
      window.location.replace("/login");
    }
    throw new ApiError(401, "Session expired");
  }

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as ApiErrorResponse | null;
    throw new ApiError(
      response.status,
      body?.message ?? `Request failed with status ${response.status}`,
      body?.details ?? []
    );
  }

  const body = (await response.json()) as ApiResponse<T>;
  return body.data;
}

export const api = {
  login(username: string, password: string): Promise<AuthTokenResponse> {
    return request("/api/v1/auth/token", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
  },

  loginWithGoogle(idToken: string): Promise<AuthTokenResponse> {
    return request("/api/v1/auth/google", {
      method: "POST",
      body: JSON.stringify({ idToken }),
    });
  },

  getProfile(): Promise<ProfileResponse> {
    return request("/api/v1/profile/me");
  },

  updateProfile(payload: ProfileRequest): Promise<ProfileResponse> {
    return request("/api/v1/profile/me", {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  listDomains(page: number, size: number): Promise<PaginationResponse<DomainResponse>> {
    return request(`/api/v1/domains?page=${page}&size=${size}`);
  },

  createDomain(payload: DomainCreateRequest): Promise<DomainResponse> {
    return request("/api/v1/domains", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  initiateDomainVerification(id: string): Promise<DomainResponse> {
    return request(`/api/v1/domains/${id}/verification`, { method: "POST" });
  },

  listGateways(page: number, size: number): Promise<PaginationResponse<GatewayResponse>> {
    return request(`/api/v1/gateways?page=${page}&size=${size}`);
  },

  createGateway(payload: GatewayCreateRequest): Promise<GatewayResponse> {
    return request("/api/v1/gateways", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  updateGateway(id: string, payload: GatewayUpdateRequest): Promise<GatewayResponse> {
    return request(`/api/v1/gateways/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  deleteGateway(id: string): Promise<Record<string, string>> {
    return request(`/api/v1/gateways/${id}`, { method: "DELETE" });
  },

  getGateway(id: string): Promise<GatewayResponse> {
    return request(`/api/v1/gateways/${id}`);
  },

  listGatewayCustomDomains(gatewayId: string): Promise<CustomDomainResponse[]> {
    return request(`/api/v1/gateways/${gatewayId}/custom-domains`);
  },

  createGatewayCustomDomain(gatewayId: string, payload: CustomDomainCreateRequest): Promise<CustomDomainResponse> {
    return request(`/api/v1/gateways/${gatewayId}/custom-domains`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  initiateCustomDomainVerification(id: string): Promise<CustomDomainResponse> {
    return request(`/api/v1/custom-domains/${id}/verification`, { method: "POST" });
  },

  deleteCustomDomain(id: string): Promise<Record<string, string>> {
    return request(`/api/v1/custom-domains/${id}`, { method: "DELETE" });
  },

  listFlows(page: number, size: number): Promise<PaginationResponse<FlowResponse>> {
    return request(`/api/v1/flows?page=${page}&size=${size}`);
  },

  getFlow(id: string): Promise<FlowResponse> {
    return request(`/api/v1/flows/${id}`);
  },

  createFlow(payload: FlowCreateRequest): Promise<FlowResponse> {
    return request("/api/v1/flows", { method: "POST", body: JSON.stringify(payload) });
  },

  updateFlow(id: string, payload: FlowUpdateRequest): Promise<FlowResponse> {
    return request(`/api/v1/flows/${id}`, { method: "PUT", body: JSON.stringify(payload) });
  },

  deleteFlow(id: string): Promise<Record<string, string>> {
    return request(`/api/v1/flows/${id}`, { method: "DELETE" });
  },

  listFlowVersions(
    flowId: string,
    page: number,
    size: number
  ): Promise<PaginationResponse<FlowVersionResponse>> {
    return request(`/api/v1/flows/${flowId}/versions?page=${page}&size=${size}`);
  },

  getFlowVersion(flowId: string, versionId: string): Promise<FlowVersionResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}`);
  },

  createFlowVersion(
    flowId: string,
    payload: FlowVersionCreateRequest
  ): Promise<FlowVersionResponse> {
    return request(`/api/v1/flows/${flowId}/versions`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  adoptFlowVersion(flowId: string, versionId: string, expectedActiveVersionId: string | null): Promise<FlowVersionResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/publish`, {
      method: "POST",
      body: JSON.stringify({ expectedActiveVersionId }),
    });
  },

  archiveFlowVersion(flowId: string, versionId: string): Promise<FlowVersionResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/archive`, { method: "POST" });
  },

  deleteFlowVersion(flowId: string, versionId: string): Promise<Record<string, string>> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}`, { method: "DELETE" });
  },

  invokeFlowVersion(
    flowId: string,
    versionId: string,
    inputPayload: string
  ): Promise<DirectFlowInvocationResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/invoke`, {
      method: "POST",
      body: inputPayload,
    });
  },

  listFlowSteps(flowId: string, versionId: string): Promise<FlowStepResponse[]> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/steps`);
  },

  createFlowStep(
    flowId: string,
    versionId: string,
    payload: FlowStepCreateRequest
  ): Promise<FlowStepResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/steps`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  updateFlowStep(
    flowId: string,
    versionId: string,
    stepId: string,
    payload: FlowStepUpdateRequest
  ): Promise<FlowStepResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/steps/${stepId}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  deleteFlowStep(flowId: string, versionId: string, stepId: string): Promise<Record<string, string>> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/steps/${stepId}`, {
      method: "DELETE",
    });
  },

  listFunctions(page: number, size: number): Promise<PaginationResponse<FunctionResponse>> {
    return request(`/api/v1/functions?page=${page}&size=${size}`);
  },

  getFunction(id: string): Promise<FunctionResponse> {
    return request(`/api/v1/functions/${id}`);
  },

  createFunction(payload: FunctionCreateRequest): Promise<FunctionResponse> {
    return request("/api/v1/functions", { method: "POST", body: JSON.stringify(payload) });
  },

  updateFunction(id: string, payload: FunctionUpdateRequest): Promise<FunctionResponse> {
    return request(`/api/v1/functions/${id}`, { method: "PUT", body: JSON.stringify(payload) });
  },

  deleteFunction(id: string): Promise<Record<string, string>> {
    return request(`/api/v1/functions/${id}`, { method: "DELETE" });
  },

  listFunctionVersions(
    functionId: string,
    page: number,
    size: number
  ): Promise<PaginationResponse<FunctionVersionResponse>> {
    return request(`/api/v1/functions/${functionId}/versions?page=${page}&size=${size}`);
  },

  getFunctionVersion(functionId: string, versionId: string): Promise<FunctionVersionResponse> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}`);
  },

  createFunctionVersion(
    functionId: string,
    payload: FunctionVersionCreateRequest
  ): Promise<FunctionVersionResponse> {
    return request(`/api/v1/functions/${functionId}/versions`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  getFunctionVersionSource(
    functionId: string,
    versionId: string
  ): Promise<FunctionVersionSourceResponse> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/source`);
  },

  getFunctionVersionSourceFiles(
    functionId: string,
    versionId: string
  ): Promise<FunctionVersionFullSourceResponse> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/source/files`);
  },

  submitFunctionVersionSource(
    functionId: string,
    versionId: string,
    files: File[],
    entrypoint: string,
    handler: string
  ): Promise<FunctionVersionSourceResponse> {
    const form = new FormData();
    for (const file of files) {
      form.append("files", file, file.name);
    }
    form.append("entrypoint", entrypoint);
    form.append("handler", handler);
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/source`, {
      method: "POST",
      body: form,
    });
  },

  deployFunctionVersion(functionId: string, versionId: string): Promise<FunctionVersionResponse> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/deploy`, { method: "POST" });
  },

  invokeFunctionVersion(
    functionId: string,
    versionId: string,
    inputPayload: string
  ): Promise<DirectInvocationResponse> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/invoke`, {
      method: "POST",
      body: inputPayload,
    });
  },

  getInvocation(invocationId: string): Promise<InvocationInspectionResponse> {
    return request(`/api/v1/invocations/${invocationId}`);
  },

  getFunctionVersionConfig(functionId: string, versionId: string): Promise<FunctionVersionConfigResponse> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/config`);
  },

  upsertFunctionVersionEnvVar(
    functionId: string,
    versionId: string,
    key: string,
    value: string
  ): Promise<FunctionVersionConfigResponse> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/config/env/${encodeURIComponent(key)}`, {
      method: "PUT",
      body: JSON.stringify({ value }),
    });
  },

  upsertFunctionVersionSecret(
    functionId: string,
    versionId: string,
    key: string,
    value: string
  ): Promise<FunctionVersionConfigResponse> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/config/secrets/${encodeURIComponent(key)}`, {
      method: "PUT",
      body: JSON.stringify({ value }),
    });
  },

  listDatabases(page: number, size: number): Promise<PaginationResponse<DatabaseResponse>> {
    return request(`/api/v1/databases?page=${page}&size=${size}`);
  },

  getDatabase(id: string): Promise<DatabaseResponse> {
    return request(`/api/v1/databases/${id}`);
  },

  createDatabase(payload: DatabaseCreateRequest): Promise<DatabaseResponse> {
    return request("/api/v1/databases", { method: "POST", body: JSON.stringify(payload) });
  },

  updateDatabase(id: string, payload: DatabaseUpdateRequest): Promise<DatabaseResponse> {
    return request(`/api/v1/databases/${id}`, { method: "PUT", body: JSON.stringify(payload) });
  },

  deleteDatabase(id: string): Promise<Record<string, string>> {
    return request(`/api/v1/databases/${id}`, { method: "DELETE" });
  },

  revealDatabasePassword(id: string): Promise<DatabasePasswordResponse> {
    return request(`/api/v1/databases/${id}/password`);
  },

  listEnvironments(page: number, size: number): Promise<PaginationResponse<EnvironmentProfileResponse>> {
    return request(`/api/v1/environments?page=${page}&size=${size}`);
  },

  createEnvironment(payload: EnvironmentProfileCreateRequest): Promise<EnvironmentProfileResponse> {
    return request("/api/v1/environments", { method: "POST", body: JSON.stringify(payload) });
  },

  updateEnvironment(id: string, payload: EnvironmentProfileUpdateRequest): Promise<EnvironmentProfileResponse> {
    return request(`/api/v1/environments/${id}`, { method: "PUT", body: JSON.stringify(payload) });
  },

  deleteEnvironment(id: string): Promise<Record<string, string>> {
    return request(`/api/v1/environments/${id}`, { method: "DELETE" });
  },

  getEnvironmentConfig(id: string): Promise<EnvironmentProfileConfigResponse> {
    return request(`/api/v1/environments/${id}/config`);
  },

  upsertEnvironmentEnvVar(id: string, key: string, value: string): Promise<EnvironmentProfileConfigResponse> {
    return request(`/api/v1/environments/${id}/config/env/${encodeURIComponent(key)}`, {
      method: "PUT",
      body: JSON.stringify({ value }),
    });
  },

  upsertEnvironmentSecret(id: string, key: string, value: string): Promise<EnvironmentProfileConfigResponse> {
    return request(`/api/v1/environments/${id}/config/secrets/${encodeURIComponent(key)}`, {
      method: "PUT",
      body: JSON.stringify({ value }),
    });
  },

  listFlowEnvironments(flowId: string): Promise<FlowEnvironmentAttachmentResponse[]> {
    return request(`/api/v1/flows/${flowId}/environments`);
  },

  attachFlowEnvironment(
    flowId: string,
    environmentId: string,
    priority?: number | null
  ): Promise<FlowEnvironmentAttachmentResponse[]> {
    return request(`/api/v1/flows/${flowId}/environments/${environmentId}`, {
      method: "PUT",
      body: JSON.stringify({ priority: priority ?? null }),
    });
  },

  detachFlowEnvironment(flowId: string, environmentId: string): Promise<FlowEnvironmentAttachmentResponse[]> {
    return request(`/api/v1/flows/${flowId}/environments/${environmentId}`, {
      method: "DELETE",
    });
  },

  listFlowDatabases(flowId: string): Promise<FlowDatabaseAttachmentResponse[]> {
    return request(`/api/v1/flows/${flowId}/databases`);
  },

  attachFlowDatabase(flowId: string, databaseId: string): Promise<FlowDatabaseAttachmentResponse[]> {
    return request(`/api/v1/flows/${flowId}/databases/${databaseId}`, {
      method: "PUT",
    });
  },

  detachFlowDatabase(flowId: string, databaseId: string): Promise<FlowDatabaseAttachmentResponse[]> {
    return request(`/api/v1/flows/${flowId}/databases/${databaseId}`, {
      method: "DELETE",
    });
  },

  listFunctionVersionDatabases(
    functionId: string,
    versionId: string
  ): Promise<FunctionVersionDatabaseAttachmentResponse[]> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/databases`);
  },

  attachFunctionVersionDatabase(
    functionId: string,
    versionId: string,
    databaseId: string
  ): Promise<FunctionVersionDatabaseAttachmentResponse[]> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/databases/${databaseId}`, {
      method: "PUT",
    });
  },

  detachFunctionVersionDatabase(
    functionId: string,
    versionId: string,
    databaseId: string
  ): Promise<FunctionVersionDatabaseAttachmentResponse[]> {
    return request(`/api/v1/functions/${functionId}/versions/${versionId}/databases/${databaseId}`, {
      method: "DELETE",
    });
  },

  listApiKeys(): Promise<ApiKeyResponse[]> {
    return request("/api/v1/api-keys");
  },

  createApiKey(payload: ApiKeyCreateRequest): Promise<ApiKeyCreateResponse> {
    return request("/api/v1/api-keys", { method: "POST", body: JSON.stringify(payload) });
  },

  revealApiKey(id: string): Promise<ApiKeyRevealResponse> {
    return request(`/api/v1/api-keys/${id}/reveal`, { method: "POST" });
  },

  revokeApiKey(id: string): Promise<Record<string, string>> {
    return request(`/api/v1/api-keys/${id}`, { method: "DELETE" });
  },
};
