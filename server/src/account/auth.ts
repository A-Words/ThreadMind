import { createRemoteJWKSet, jwtVerify } from "jose";
import { DomainError } from "../domain/errors.ts";

export interface AuthConfig {
  jwksUrl: string;
  issuer: string;
  audience: string;
}
export function authConfigFromEnv(env: NodeJS.ProcessEnv): AuthConfig {
  const jwksUrl = env.AUTH_JWKS_URL;
  const issuer = env.AUTH_ISSUER;
  const audience = env.AUTH_AUDIENCE;
  if (!jwksUrl || !issuer || !audience) {
    throw new Error("AUTH_JWKS_URL, AUTH_ISSUER and AUTH_AUDIENCE are required");
  }
  return { jwksUrl, issuer, audience };
}

export function createTokenVerifier(config: AuthConfig) {
  const keySet = createRemoteJWKSet(new URL(config.jwksUrl));
  return async (authorization: string | undefined): Promise<string> => {
    if (!authorization?.startsWith("Bearer ")) throw new DomainError("unauthorized", "Bearer token is required");
    const { payload } = await jwtVerify(authorization.slice(7), keySet, {
      issuer: config.issuer,
      audience: config.audience,
      requiredClaims: ["sub"],
    });
    if (!payload.sub) throw new DomainError("unauthorized", "Token subject is required");
    return payload.sub;
  };
}
