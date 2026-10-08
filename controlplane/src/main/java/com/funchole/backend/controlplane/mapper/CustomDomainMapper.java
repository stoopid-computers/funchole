package com.funchole.backend.controlplane.mapper;

import com.funchole.backend.controlplane.dto.CustomDomainResponse;
import com.funchole.backend.controlplane.entity.CustomDomain;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CustomDomainMapper {

    @Mapping(target = "gatewayId", source = "gateway.id")
    @Mapping(target = "gatewayHostname", ignore = true)
    @Mapping(target = "gatewayPublicIp", ignore = true)
    @Mapping(target = "verificationRecordName", expression = "java(\"funchole-\" + customDomain.getId() + \".\" + customDomain.getHostname())")
    CustomDomainResponse toResponse(CustomDomain customDomain);

    /**
     * {@code gatewayHostname} (the CNAME target) and {@code gatewayPublicIp}
     * (the A-record target for apex domains) are computed cross-entity
     * values - see {@code GatewayCertificateService.buildHostname} and
     * {@code GatewayNetworkProperties} - not plain field mappings, so the
     * caller supplies them here rather than this interface reaching for
     * that state itself.
     */
    default CustomDomainResponse toResponse(CustomDomain customDomain, String gatewayHostname, String gatewayPublicIp) {
        CustomDomainResponse base = toResponse(customDomain);
        return new CustomDomainResponse(
                base.id(),
                base.gatewayId(),
                gatewayHostname,
                base.hostname(),
                base.status(),
                base.verificationCode(),
                base.verificationRecordName(),
                base.certStatus(),
                gatewayPublicIp,
                base.createdAt(),
                base.updatedAt()
        );
    }
}
