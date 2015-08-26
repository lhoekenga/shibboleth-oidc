package net.shibboleth.idp.oidc.flow;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.google.gson.JsonObject;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import net.shibboleth.idp.oidc.OpenIdConnectUtils;
import net.shibboleth.idp.oidc.config.SpringSecurityAuthenticationToken;
import net.shibboleth.idp.profile.AbstractProfileAction;
import net.shibboleth.utilities.java.support.net.HttpServletRequestResponseContext;
import org.apache.http.client.utils.URIBuilder;
import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.model.SystemScope;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.mitre.oauth2.service.SystemScopeService;
import org.mitre.openid.connect.model.UserInfo;
import org.mitre.openid.connect.request.ConnectRequestParameters;
import org.mitre.openid.connect.service.ScopeClaimTranslationService;
import org.mitre.openid.connect.service.StatsService;
import org.mitre.openid.connect.service.UserInfoService;
import org.opensaml.profile.context.ProfileRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.AuthorizationRequest;
import org.springframework.security.oauth2.provider.endpoint.RedirectResolver;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import javax.annotation.Nonnull;
import javax.security.auth.Subject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.net.URISyntaxException;
import java.security.Principal;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prepares the webflow response for the approval/consent view.
 */
public class PreAuthorizeUserApprovalAction extends AbstractProfileAction {



    private final Logger log = LoggerFactory.getLogger(PreAuthorizeUserApprovalAction.class);

    @Autowired
    private ClientDetailsEntityService clientService;

    @Autowired
    private SystemScopeService scopeService;

    @Autowired
    private ScopeClaimTranslationService scopeClaimTranslationService;

    @Autowired
    private UserInfoService userInfoService;

    @Autowired
    private StatsService statsService;

    @Autowired
    private RedirectResolver redirectResolver;

    @Autowired
    private AuthenticationManager authenticationManager;

    /**
     * Instantiates a Pre-authorize user approval action.
     */
    public PreAuthorizeUserApprovalAction() {
    }

    @Nonnull
    @Override
    protected Event doExecute(@Nonnull RequestContext springRequestContext,
                              @Nonnull ProfileRequestContext profileRequestContext) {

        HttpServletRequest request = HttpServletRequestResponseContext.getRequest();
        HttpSession session = request.getSession();
        AuthorizationRequest authRequest = OpenIdConnectUtils.getAuthorizationRequest(request);
        if (authRequest == null || Strings.isNullOrEmpty(authRequest.getClientId())) {
            log.warn("Authorization request could not be loaded from session");
            return Events.Failure.event(this);
        }

        String prompt = (String)authRequest.getExtensions().get(ConnectRequestParameters.PROMPT);
        List<String> prompts = Splitter.on(ConnectRequestParameters.PROMPT_SEPARATOR)
                .splitToList(Strings.nullToEmpty(prompt));

        ClientDetailsEntity client;

        try {
            client = clientService.loadClientByClientId(authRequest.getClientId());
            if (client == null) {
                log.error("Could not find client {}", authRequest.getClientId());
                return Events.ClientNotFound.event(this);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Events.BadRequest.event(this);
        }

        if (prompts.contains("none")) {
            return handleWhenNoPromptIsPresent(springRequestContext, request, authRequest, client);
        }

        SecurityContext securityContext = SecurityContextHolder.getContext();
        SpringSecurityAuthenticationToken token = new SpringSecurityAuthenticationToken(profileRequestContext);
        Authentication authentication = authenticationManager.authenticate(token);
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        session.setAttribute("SPRING_SECURITY_CONTEXT", securityContext);

        OpenIdConnectResponse response = new OpenIdConnectResponse();
        response.setAuthorizationRequest(authRequest);
        response.setClient(client);
        response.setRedirectUri(authRequest.getRedirectUri());

        // pre-process the scopes
        Set<SystemScope> scopes = scopeService.fromStrings(authRequest.getScope());

        Set<SystemScope> sortedScopes = getSystemScopes(scopes);
        response.setScopes(sortedScopes);

        Map<String, Map<String, String>> claimsForScopes = getUserInfoClaimsForScopes(sortedScopes);
        response.setClaims(claimsForScopes);

        // client stats
        Integer count = statsService.getCountForClientId(client.getId());
        response.setCount(count);

        if (client.getContacts() != null) {
            response.setContacts(client.getContacts());
        }

        // if the client is over a week old and has more than one registration, don't give such a big warning
        // instead, tag as "Generally Recognized As Safe" (gras)
        Date lastWeek = new Date(System.currentTimeMillis() - (60 * 60 * 24 * 7 * 1000));
        response.setGras(count > 1 && client.getCreatedAt() != null && client.getCreatedAt().before(lastWeek));

        OpenIdConnectUtils.setResponse(springRequestContext, response);
        OpenIdConnectUtils.setAuthorizationRequest(request, authRequest,
                OpenIdConnectUtils.getAuthorizationRequestParameters(request));



        return Events.Proceed.event(this);
    }

    private Map<String, Map<String, String>> getUserInfoClaimsForScopes(final Set<SystemScope> sortedScopes) {

        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();
        Subject principal = (Subject) authentication.getPrincipal();
        Collection<Principal> collection =
                principal.getPrincipals().stream().filter(p -> p instanceof UsernamePrincipal).collect(Collectors.toList());
        UsernamePrincipal usernamePrincipal = (UsernamePrincipal) collection.iterator().next();

        UserInfo user = userInfoService.getByUsername(usernamePrincipal.getName());
        Map<String, Map<String, String>> claimsForScopes = new HashMap<>();
        if (user != null) {
            JsonObject userJson = user.toJson();

            for (SystemScope systemScope : sortedScopes) {
                Map<String, String> claimValues = new HashMap<>();

                Set<String> claims = scopeClaimTranslationService.getClaimsForScope(systemScope.getValue());
                claims.stream().filter(claim -> userJson.has(claim) &&
                        userJson.get(claim).isJsonPrimitive()).forEach(claim -> {
                    claimValues.put(claim, userJson.get(claim).getAsString());
                });

                claimsForScopes.put(systemScope.getValue(), claimValues);
            }
        }
        return claimsForScopes;
    }

    private Set<SystemScope> getSystemScopes(final Set<SystemScope> scopes) {
        Set<SystemScope> sortedScopes = new LinkedHashSet<>(scopes.size());
        Set<SystemScope> systemScopes = scopeService.getAll();

        // sort scopes for display based on the inherent order of system scopes
        sortedScopes.addAll(systemScopes.stream().filter(s -> scopes.contains(s)).collect(Collectors.toList()));

        // add in any scopes that aren't system scopes to the end of the list
        sortedScopes.addAll(Sets.difference(scopes, systemScopes));
        return sortedScopes;
    }

    private Event handleWhenNoPromptIsPresent(@Nonnull final  RequestContext springRequestContext,
                                              @Nonnull final HttpServletRequest request,
                                              @Nonnull final AuthorizationRequest authRequest,
                                              @Nonnull final ClientDetailsEntity client) {
        try {
            String url = redirectResolver.resolveRedirect(authRequest.getRedirectUri(), client);
            URIBuilder uriBuilder = new URIBuilder(url);

            uriBuilder.addParameter("error", "interaction_required");
            if (!Strings.isNullOrEmpty(authRequest.getState())) {
                uriBuilder.addParameter("state", authRequest.getState());
            }

            OpenIdConnectResponse response = new OpenIdConnectResponse();
            response.setRedirectUri(uriBuilder.toString());
            response.setAuthorizationRequest(authRequest);
            response.setClient(client);

            OpenIdConnectUtils.setResponse(springRequestContext, response);
            OpenIdConnectUtils.setAuthorizationRequest(request, authRequest,
                    OpenIdConnectUtils.getAuthorizationRequestParameters(request));
            return Events.Redirect.event(this);

        } catch (URISyntaxException e) {
            log.error("Can't build redirect URI for prompt=none, sending error instead", e);
            return Events.BadRequest.event(this);
        }
    }

}


