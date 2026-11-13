package io.internetdata;

import io.internetdata.model.DisposableDetail;
import io.internetdata.model.DomainDns;
import io.internetdata.model.DomainIpRanges;
import io.internetdata.model.DomainLookupResponse;

import java.util.Objects;
import java.util.Optional;

/**
 * What a domain lookup answers.
 *
 * <p>An <b>empty</b> {@code Optional} is a member your plan does not include, never "we could not
 * check", so empty and {@code false} are different answers. A member every plan answers is returned
 * as it is.
 */
public final class DomainResult {
    private final DomainLookupResponse raw;

    private DomainResult(DomainLookupResponse raw) {
        this.raw = raw;
    }

    static DomainResult of(DomainLookupResponse body) {
        return new DomainResult(body);
    }

    /** The domain, lowercase and in punycode. */
    public String domain() {
        return raw.getDomain();
    }

    /** In Domain List: a registered domain. */
    public boolean isRegistered() {
        return Boolean.TRUE.equals(raw.getIsRegistered());
    }

    /** In Disposable Domain. Paid plans. */
    public Optional<Boolean> isDisposable() {
        return Optional.ofNullable(raw.getIsDisposable());
    }

    public boolean hasA() {
        return Boolean.TRUE.equals(raw.getHasA());
    }

    public boolean hasAaaa() {
        return Boolean.TRUE.equals(raw.getHasAaaa());
    }

    public boolean hasMx() {
        return Boolean.TRUE.equals(raw.getHasMx());
    }

    public boolean hasNs() {
        return Boolean.TRUE.equals(raw.getHasNs());
    }

    public boolean hasCname() {
        return Boolean.TRUE.equals(raw.getHasCname());
    }

    /** The records themselves. Paid plans. */
    public Optional<DomainDns> dns() {
        return Optional.ofNullable(raw.getDns());
    }

    /** The IP ranges of the organization behind the domain. Paid plans. */
    public Optional<DomainIpRanges> ipRanges() {
        return Optional.ofNullable(raw.getIpRanges());
    }

    /** Detail for {@link #isDisposable()}, present and empty when it is false. Paid plans. */
    public Optional<DisposableDetail> disposable() {
        return Optional.ofNullable(raw.getDisposable());
    }

    /**
     * On answers to a key on the free plan only: {@code false} while its organization's daily
     * trial lasts, {@code true} once the answer has Unlimited's.
     */
    public Optional<Boolean> trialExpired() {
        return Optional.ofNullable(raw.getTrialExpired());
    }

    /** The response exactly as it came off the wire, with its original names. */
    public DomainLookupResponse raw() {
        return raw;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DomainResult)) {
            return false;
        }
        return Objects.equals(raw, ((DomainResult) other).raw);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(raw);
    }

    @Override
    public String toString() {
        return "DomainResult{domain=" + domain() + ", isRegistered=" + isRegistered() + "}";
    }
}
