package com.richardsand.samltest.resources;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.richardsand.samltest.model.AicLogResult;
import com.richardsand.samltest.services.AicLogService;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/aic/logs")
@Produces(MediaType.APPLICATION_JSON)
public class AicLogResource {
    public record AicLogBatchRequest(List<String> transactionIds) {
    }

    private final AicLogService logService;

    public AicLogResource(AicLogService logService) {
        this.logService = logService;
    }

    @GET
    @Path("/{transactionId}")
    public AicLogResult getByTransactionId(@PathParam("transactionId") String transactionId) {
        return logService.query(transactionId);
    }

    @POST
    @Path("/batch")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<AicLogResult> queryBatch(AicLogBatchRequest request) {
        return request.transactionIds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .distinct()
                .map((String transactionId) -> {
                    try {
                        return logService.query(transactionId);
                    } catch (Exception e) {
                        return AicLogResult.failed(transactionId, e.getMessage());
                    }
                })
                .collect(Collectors.toList());
    }
}