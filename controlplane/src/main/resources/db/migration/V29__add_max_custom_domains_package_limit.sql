-- Adds the MAX_CUSTOM_DOMAINS limit to the seeded 'free' package (see
-- PackageLimitKey/PackageLimitService/CustomDomainService) - free tier gets
-- one custom domain per gateway slot they already have (MAX_GATEWAYS=1).

INSERT INTO package_limits (id, package_id, limit_key, limit_value)
VALUES ('33333333-3333-3333-3333-333333333339', '33333333-3333-3333-3333-333333333333', 'MAX_CUSTOM_DOMAINS', 1)
ON CONFLICT (package_id, limit_key) DO NOTHING;
