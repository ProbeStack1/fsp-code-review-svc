package com.probestack.forgesphere.codereview.config;

import com.forge.security.authn.model.AuthnToken;
import com.forge.security.authn.security.ForgeAuthnAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * {@code CodeReviewController} used to read "who is calling" and "what's their role" straight off
 * client-supplied {@code X-User-Email} / {@code X-User-Role} headers, trusted at face value — for
 * {@code merge}/{@code close} that role is what decides whether a non-owner is allowed to act on
 * someone else's pull request at all, so a spoofed header there was a real privilege-escalation
 * path, not just a cosmetic attribution issue. Once a request is past {@link SecurityConfig}'s
 * gate, forge-auth-lib has already verified a real, signed token, and that token's own claims
 * ({@code email}, {@code role}, ...) are exactly this same information, except a caller genuinely
 * cannot forge them without the identity provider's signing key.
 * <p>
 * Every accessor here returns empty when there's no verified token on this request — forge.authn
 * disabled (local dev) — callers decide their own fallback for that case rather than this class
 * silently inventing one.
 */
public final class AuthenticatedCaller {

    private AuthenticatedCaller() {
    }

    private static Optional<AuthnToken> token() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ForgeAuthnAuthenticationToken && auth.getDetails() instanceof AuthnToken authnToken) {
            return Optional.of(authnToken);
        }
        return Optional.empty();
    }

    public static Optional<String> email() {
        return token().map(t -> stringClaim(t, "email"));
    }

    public static Optional<String> role() {
        return token().map(t -> stringClaim(t, "role"));
    }

    /**
     * The calling user's own organization — used only to tell {@code ServiceTokenClient} which
     * organization the outbound fsp-cicd-automation-svc call is being made on behalf of. Never a
     * fixed/placeholder value: each organization gets its own cached service token.
     */
    public static Optional<String> organizationId() {
        return token().map(t -> stringClaim(t, "organization_id")).filter(id -> id != null && !id.isBlank());
    }

    private static String stringClaim(AuthnToken token, String claimName) {
        Object value = token.getClaim(claimName);
        return value == null ? null : value.toString();
    }
}
