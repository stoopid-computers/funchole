export type DomainStatus = "PENDING" | "VERIFIED" | "REJECTED";

export type GatewayStatus = "ACTIVE" | "INACTIVE";

export type CertificateProvider = "SELF_SIGNED" | "LETS_ENCRYPT";

export type CertificateStatus = "PENDING" | "ACTIVE" | "FAILED" | "EXPIRED";

export type FlowVersionStatus = "DRAFT" | "ADOPTED" | "ARCHIVED";

export type FlowStepComponentType = "FUNCTION" | "RESPONSE" | "MIDDLEWARE" | "SUB_FLOW";

export type FunctionVersionStatus = "DRAFT" | "PUBLISHING" | "READY" | "FAILED";

export type InvocationStatus = "PENDING" | "COMPLETED" | "FAILED";

export interface AuthTokenResponse {
  accessToken: string;
  tokenType: string;
  issuedAt: string;
  expiresAt: string;
  passwordChangeRequired: boolean;
}

export interface ProfileResponse {
  id: string;
  username: string;
  email: string;
  fullName: string;
}

export interface ProfileRequest {
  fullName?: string;
  password?: string;
}

export interface DomainResponse {
  id: string;
  domainName: string;
  status: DomainStatus;
  verificationCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DomainCreateRequest {
  domainName: string;
}

export interface CertificateSummaryResponse {
  hostname: string;
  wildcardHostname: string;
  provider: CertificateProvider;
  status: CertificateStatus;
  issuedAt: string | null;
  expiresAt: string | null;
}

export interface GatewayResponse {
  id: string;
  appDomainId: string;
  domainName: string;
  name: string;
  uniqueKey: string;
  description: string | null;
  status: GatewayStatus;
  certificate: CertificateSummaryResponse | null;
  createdAt: string;
  updatedAt: string;
}

export interface GatewayCreateRequest {
  name: string;
  description?: string | null;
  appDomainId: string;
  status: GatewayStatus;
}

export type GatewayUpdateRequest = GatewayCreateRequest;

export type CustomDomainStatus = "PENDING" | "VERIFIED";

export interface CustomDomainResponse {
  id: string;
  gatewayId: string;
  gatewayHostname: string;
  hostname: string;
  status: CustomDomainStatus;
  verificationCode: string | null;
  certStatus: CertificateStatus;
  gatewayPublicIp: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CustomDomainCreateRequest {
  hostname: string;
}

export interface FlowResponse {
  id: string;
  gatewayId: string;
  gatewayName: string;
  activeFlowVersionId: string | null;
  activeFlowVersionStatus: FlowVersionStatus | null;
  flowKey: string;
  name: string;
  description: string | null;
  httpMethod: string;
  path: string;
  priority: number;
  createdAt: string;
  updatedAt: string;
}

export interface FlowCreateRequest {
  flowKey: string;
  name: string;
  description?: string | null;
  gatewayId: string;
  httpMethod: string;
  path: string;
  priority?: number | null;
}

export interface FlowUpdateRequest {
  name: string;
  description?: string | null;
  gatewayId: string;
  httpMethod: string;
  path: string;
  priority?: number | null;
}

export interface FlowStepResponse {
  id: string;
  flowVersionId: string;
  stepKey: string;
  componentType: FlowStepComponentType;
  position: number;
  componentId: string;
  componentVersionId: string;
  metadata: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface FlowStepCreateRequest {
  stepKey: string;
  componentType: FlowStepComponentType;
  position: number;
  componentId: string;
  componentVersionId: string;
  metadata?: string | null;
}

export type FlowStepUpdateRequest = FlowStepCreateRequest;

export interface FlowVersionResponse {
  id: string;
  flowId: string;
  version: number;
  status: FlowVersionStatus;
  runtime: string;
  metadata: string | null;
  steps: FlowStepResponse[] | null;
  createdAt: string;
  updatedAt: string;
  adoptedAt: string | null;
  archivedAt: string | null;
}

export interface FlowVersionCreateRequest {
  runtime?: string | null;
  metadata?: string | null;
}

export interface FunctionResponse {
  id: string;
  functionKey: string;
  name: string;
  description: string | null;
  runtime: string;
  createdAt: string;
  updatedAt: string;
}

export interface FunctionCreateRequest {
  functionKey: string;
  name: string;
  description?: string | null;
  runtime?: string | null;
}

export interface FunctionUpdateRequest {
  name: string;
  description?: string | null;
  runtime?: string | null;
}

export interface FunctionVersionResponse {
  id: string;
  functionId: string;
  version: number;
  status: FunctionVersionStatus;
  runtime: string;
  artifactObjectKey: string | null;
  artifactFormat: string | null;
  artifactSha256: string | null;
  artifactSizeBytes: number | null;
  artifactPublishedAt: string | null;
  metadata: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface FunctionVersionCreateRequest {
  runtime?: string | null;
  metadata?: string | null;
}

export interface FunctionVersionSourceResponse {
  functionVersionId: string;
  runtimeType: string;
  runtimeVersion: string | null;
  entrypoint: string;
  handler: string;
  relativePaths: string[];
}

export interface FunctionVersionSourceFileResponse {
  path: string;
  content: string;
}

export interface FunctionVersionFullSourceResponse {
  entrypoint: string;
  handler: string;
  files: FunctionVersionSourceFileResponse[];
}

export interface FunctionVersionEnvVarResponse {
  id: string;
  key: string;
  value: string;
  createdAt: string;
  updatedAt: string;
}

export interface FunctionVersionSecretResponse {
  id: string;
  key: string;
  secretRef: string;
  createdAt: string;
  updatedAt: string;
}

export interface FunctionVersionConfigResponse {
  functionVersionId: string;
  envVars: FunctionVersionEnvVarResponse[];
  secrets: FunctionVersionSecretResponse[];
}

export interface DirectInvocationResponse {
  invocationId: string;
  functionVersionId: string;
  initialStatus: string;
}

export interface DirectFlowInvocationResponse {
  invocationId: string;
  flowVersionId: string;
  initialStatus: string;
}

export interface InvocationStepLogResponse {
  stream: string;
  message: string;
  createdAt: string;
}

export interface InvocationStepInspectionResponse {
  stepId: string;
  position: number;
  componentType: string;
  componentId: string;
  componentVersionId: string;
  status: string;
  attempt: number;
  result: string | null;
  error: string | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  logs: InvocationStepLogResponse[];
}

export interface InvocationInspectionResponse {
  invocationId: string;
  status: InvocationStatus;
  flowId: string | null;
  flowKey: string | null;
  flowVersionId: string | null;
  functionVersionId: string | null;
  inputPayload: string | null;
  result: string | null;
  error: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  steps: InvocationStepInspectionResponse[];
}

export interface DatabaseResponse {
  id: string;
  name: string;
  type: string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  sslEnabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface DatabaseCreateRequest {
  name: string;
  type: string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  password: string;
  sslEnabled?: boolean | null;
}

export interface DatabaseUpdateRequest {
  name: string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  password?: string | null;
  sslEnabled?: boolean | null;
}

export interface DatabasePasswordResponse {
  password: string;
}

export interface EnvironmentProfileResponse {
  id: string;
  environmentKey: string;
  name: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface EnvironmentProfileCreateRequest {
  environmentKey: string;
  name: string;
  description?: string | null;
}

export interface EnvironmentProfileUpdateRequest {
  name: string;
  description?: string | null;
}

export interface EnvironmentProfileConfigResponse {
  environmentProfileId: string;
  envVars: FunctionVersionEnvVarResponse[];
  secrets: FunctionVersionSecretResponse[];
}

export interface FlowEnvironmentAttachmentResponse {
  id: string;
  environmentProfileId: string;
  environmentKey: string;
  environmentName: string;
  priority: number;
  createdAt: string;
}

export interface FlowDatabaseAttachmentResponse {
  id: string;
  databaseId: string;
  databaseName: string;
  databaseType: string;
  createdAt: string;
}

export interface FunctionVersionDatabaseAttachmentResponse {
  id: string;
  databaseId: string;
  databaseName: string;
  databaseType: string;
  createdAt: string;
}

export interface ApiKeyResponse {
  id: string;
  name: string;
  keyPrefix: string;
  createdAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
}

export interface ApiKeyCreateRequest {
  name: string;
}

export interface ApiKeyCreateResponse {
  id: string;
  name: string;
  keyPrefix: string;
  rawKey: string;
  createdAt: string;
}

export interface ApiKeyRevealResponse {
  id: string;
  rawKey: string;
}

export interface PaginationResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface ApiResponse<T> {
  timestamp: string;
  success: boolean;
  data: T;
}

export interface ApiErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  details: string[];
}
