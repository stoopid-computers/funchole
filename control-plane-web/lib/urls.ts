// The one place that builds a public address. A verified custom domain, when
// the caller has one, wins over the auto-assigned funchole.dev host.

export interface HostSource {
  uniqueKey: string;
  domainName: string;
}

export function gatewayHost(gateway: HostSource, customHostname?: string | null) {
  return customHostname || `${gateway.uniqueKey}.${gateway.domainName}`;
}

export function liveUrl(gateway: HostSource, path = "", customHostname?: string | null) {
  return `https://${gatewayHost(gateway, customHostname)}${path}`;
}
