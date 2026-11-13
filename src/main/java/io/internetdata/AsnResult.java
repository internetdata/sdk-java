package io.internetdata;

import io.internetdata.model.AsnLookupResponse;
import io.internetdata.model.AsnPrefix;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What an AS lookup answers.
 *
 * <p>An <b>empty</b> {@code Optional} is a member your plan does not include, never "we could not
 * check". A member every plan answers is returned as it is.
 */
public final class AsnResult {
    private final AsnLookupResponse raw;

    private AsnResult(AsnLookupResponse raw) {
        this.raw = raw;
    }

    static AsnResult of(AsnLookupResponse body) {
        return new AsnResult(body);
    }

    /** The AS number. */
    public long asn() {
        return raw.getAsn();
    }

    /** Its operator. */
    public String name() {
        return raw.getName();
    }

    /** The country its registry assigned it to. */
    public String country() {
        return raw.getCountry();
    }

    /** The regional registry that allocated it, such as {@code ripe}. */
    public String registry() {
        return raw.getRegistry();
    }

    /** When it was allocated, a date. */
    public String allocated() {
        return raw.getAllocated();
    }

    /** Its operator's domain. */
    public String domain() {
        return raw.getDomain();
    }

    /** The kind of network it runs, such as {@code hosting}. Paid plans. */
    public Optional<String> type() {
        return Optional.ofNullable(raw.getType());
    }

    /** IPv4 addresses it announces. Paid plans. */
    public Optional<Integer> numIps() {
        return Optional.ofNullable(raw.getNumIps());
    }

    public Optional<Integer> numPrefixes4() {
        return Optional.ofNullable(raw.getNumPrefixes4());
    }

    public Optional<Integer> numPrefixes6() {
        return Optional.ofNullable(raw.getNumPrefixes6());
    }

    public Optional<Integer> rpkiValid() {
        return Optional.ofNullable(raw.getRpkiValid());
    }

    public Optional<Integer> rpkiInvalid() {
        return Optional.ofNullable(raw.getRpkiInvalid());
    }

    public Optional<Integer> rpkiNotFound() {
        return Optional.ofNullable(raw.getRpkiNotFound());
    }

    /** Its ASN Whois record, every column, empty where the registry states nothing. Paid plans. */
    public Optional<Map<String, String>> whois() {
        return Optional.ofNullable(raw.getWhois());
    }

    /** What it announces, IPv4 then IPv6, each with its RPKI status. Paid plans. */
    public Optional<List<AsnPrefix>> prefixes() {
        return Optional.ofNullable(raw.getPrefixes());
    }

    /**
     * On answers to a key on the free plan only: {@code false} while its organization's daily
     * trial lasts, {@code true} once the answer has Unlimited's.
     */
    public Optional<Boolean> trialExpired() {
        return Optional.ofNullable(raw.getTrialExpired());
    }

    /** The response exactly as it came off the wire, with its original names. */
    public AsnLookupResponse raw() {
        return raw;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AsnResult)) {
            return false;
        }
        return Objects.equals(raw, ((AsnResult) other).raw);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(raw);
    }

    @Override
    public String toString() {
        return "AsnResult{asn=" + asn() + ", name=" + name() + "}";
    }
}
