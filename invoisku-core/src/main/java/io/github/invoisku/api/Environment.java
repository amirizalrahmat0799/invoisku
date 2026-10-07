package io.github.invoisku.api;

import java.net.URI;

/** MyInvois API hosts. */
public enum Environment {
    /** LHDN's pre-production (sandbox) system. */
    SANDBOX("https://preprod-api.myinvois.hasil.gov.my", "https://preprod.myinvois.hasil.gov.my"),
    PRODUCTION("https://api.myinvois.hasil.gov.my", "https://myinvois.hasil.gov.my");

    private final URI apiBase;
    private final URI portalBase;

    Environment(String api, String portal) {
        this.apiBase = URI.create(api);
        this.portalBase = URI.create(portal);
    }

    public URI apiBase() {
        return apiBase;
    }

    /** Host of the public validation links printed as a QR code on the e-invoice. */
    public URI portalBase() {
        return portalBase;
    }
}
