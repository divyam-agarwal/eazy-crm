package com.easycrm.platform.security;

import com.easycrm.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        try {
            if (header != null && header.startsWith("Bearer ")) {
                TenantContext.TenantPrincipal p = jwt.parse(header.substring(7));
                TenantContext.set(p);
                var auth = new UsernamePasswordAuthenticationToken(
                    p.userId(), null, List.of(new SimpleGrantedAuthority("ROLE_" + p.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(req, res);
        } catch (RuntimeException ex) {
            // invalid token: leave unauthenticated; SecurityConfig will 401 protected routes
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();           // MUST clear — pooled threads
            SecurityContextHolder.clearContext();
        }
    }
}
