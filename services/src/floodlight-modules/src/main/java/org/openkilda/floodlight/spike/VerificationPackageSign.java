package org.openkilda.floodlight.spike;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;

import java.io.UnsupportedEncodingException;

public class VerificationPackageSign {
    private Algorithm algorithm;
    private JWTVerifier verifier;

    public VerificationPackageSign(String secret) {
        try {
            algorithm = Algorithm.HMAC256(secret);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e.toString(), e);
        }
        verifier = JWT.require(algorithm).build();
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }
}
