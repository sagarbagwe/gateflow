package com.gateflow.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.security.Principal;
import java.util.UUID;

public record UserPrincipal(UUID id, String email, String displayName) implements Principal {
    @JsonIgnore
    @Override
    public String getName() { return id.toString(); }
}
