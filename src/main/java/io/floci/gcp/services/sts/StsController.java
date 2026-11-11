package io.floci.gcp.services.sts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code POST /v1/token}, form-encoded as the Google auth libraries send it or JSON in snake_case or camelCase, and the
 * legacy {@code /v1/identitybindingtoken} path ({@code securetoken.googleapis.com}) Google's Secret Manager CSI
 * provider posts GKE ServiceAccount tokens to. No {@code Authorization} header is read.
 */
@Path("/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class StsController {

    @Inject
    StsService service;

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response token(@FormParam("grant_type") String grantType,
            @FormParam("subject_token_type") String subjectTokenType,
            @FormParam("subject_token") String subjectToken,
            @FormParam("requested_token_type") String requestedTokenType,
            @FormParam("options") String options,
            @FormParam("audience") String audience,
            @FormParam("scope") String scope) {
        return respond(() -> service.exchangeToken(grantType, subjectTokenType, subjectToken, requestedTokenType,
                options, audience, scope));
    }

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tokenJson(Map<String, Object> body) {
        return respondJson(body);
    }

    @POST
    @Path("/identitybindingtoken")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response identityBindingToken(@FormParam("grant_type") String grantType,
            @FormParam("subject_token_type") String subjectTokenType,
            @FormParam("subject_token") String subjectToken,
            @FormParam("requested_token_type") String requestedTokenType,
            @FormParam("audience") String audience,
            @FormParam("scope") String scope) {
        return respond(() -> service.exchangeToken(grantType, subjectTokenType, subjectToken, requestedTokenType,
                null, audience, scope));
    }

    @POST
    @Path("/identitybindingtoken")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response identityBindingTokenJson(Map<String, Object> body) {
        return respondJson(body);
    }

    private Response respondJson(Map<String, Object> body) {
        Map<String, Object> fields = body == null ? Map.of() : body;
        return respond(() -> service.exchangeToken(field(fields, "grant_type", "grantType"),
                field(fields, "subject_token_type", "subjectTokenType"), field(fields, "subject_token", "subjectToken"),
                field(fields, "requested_token_type", "requestedTokenType"), field(fields, "options", "options"),
                field(fields, "audience", "audience"), field(fields, "scope", "scope")));
    }

    private static String field(Map<String, Object> body, String snake, String camel) {
        Object value = body.containsKey(snake) ? body.get(snake) : body.get(camel);
        return value instanceof String s ? s : null;
    }

    private static Response respond(Supplier<Map<String, Object>> exchange) {
        try {
            return Response.ok(exchange.get()).build();
        } catch (StsException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.error());
            error.put("error_description", e.getMessage());
            return Response.status(400).type(MediaType.APPLICATION_JSON).entity(error).build();
        }
    }
}
