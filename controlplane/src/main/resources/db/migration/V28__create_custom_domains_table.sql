-- Lets an end user attach their own hostname (subdomain via CNAME, or an
-- apex domain via A record - see GatewayNetworkProperties.publicIp for the
-- A-record target) to one of their existing Gateways, so requests to it
-- route exactly like requests to the gateway's own auto-assigned hostname.
-- Verification is TXT-only (same algorithm as app_domains/DomainService),
-- deliberately not also checking CNAME/A resolution - see CustomDomainService.

CREATE TABLE custom_domains (
    id UUID PRIMARY KEY,
    gateway_id UUID NOT NULL REFERENCES gateways(id) ON DELETE CASCADE ON UPDATE CASCADE,
    app_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE ON UPDATE CASCADE,
    hostname VARCHAR(255) NOT NULL,
    verification_code VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    cert_provider VARCHAR(100) NOT NULL DEFAULT 'SELF_SIGNED',
    cert_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    cert_secret_ref VARCHAR(255),
    cert_issued_at TIMESTAMP WITH TIME ZONE,
    cert_expires_at TIMESTAMP WITH TIME ZONE,
    cert_renewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_custom_domains_hostname UNIQUE (hostname)
);

CREATE INDEX idx_custom_domains_gateway_id ON custom_domains (gateway_id);
CREATE INDEX idx_custom_domains_app_user_id ON custom_domains (app_user_id);
CREATE INDEX idx_custom_domains_status ON custom_domains (status);
CREATE INDEX idx_custom_domains_cert_status ON custom_domains (cert_status);
CREATE INDEX idx_custom_domains_cert_expires_at ON custom_domains (cert_expires_at);
