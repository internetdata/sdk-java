package io.internetdata;

import io.internetdata.model.AbuseContact;
import io.internetdata.model.Asn;
import io.internetdata.model.BitTorrentDetail;
import io.internetdata.model.CarrierDetail;
import io.internetdata.model.ClassDetail;
import io.internetdata.model.Company;
import io.internetdata.model.DnsResolverDetail;
import io.internetdata.model.HostedDomains;
import io.internetdata.model.Location;
import io.internetdata.model.LookupResponse;
import io.internetdata.model.ProxyDetail;
import io.internetdata.model.PubproxyDetail;
import io.internetdata.model.Rwhois;
import io.internetdata.model.SatelliteDetail;
import io.internetdata.model.SmtpDetail;
import io.internetdata.model.SshDetail;
import io.internetdata.model.WebDetail;
import io.internetdata.model.Whois;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What an address lookup answers.
 *
 * <p>An <b>empty</b> {@code Optional} is a member your plan does not include. It never means "we
 * could not check", so empty and {@code false} are genuinely different answers: empty is "not in
 * your plan", {@code false} is "checked, and no". Call {@code orElse(false)} on the Optional
 * for the common case where you only care whether the address is flagged. A member every plan
 * answers is returned as it is.
 *
 * <p>A detail object that is present but empty means the flag above it is false, as an empty
 * {@link #web()} does. A populated one always carries every one of its keys. Inside the objects
 * every plan answers, a member your plan leaves out, such as {@code location.city}, reads as null,
 * never as an empty string, zero or false.
 */
public final class Result {
    private final LookupResponse raw;

    private Result(LookupResponse raw) {
        this.raw = raw;
    }

    static Result of(LookupResponse body) {
        return new Result(body);
    }

    /**
     * The answer a bogon gets, in the shape the API answers one on a paid plan: every flag present
     * and false, every detail object present and empty, the shared objects with every field empty,
     * and {@code is_bogon} true.
     *
     * <p>Note this is deliberately the WIDEST shape regardless of your plan, so do not infer which
     * fields your plan includes from a bogon answer.
     */
    static Result bogon(String ip) {
        LookupResponse r = new LookupResponse()
                .ip(ip)
                .isBogon(true)
                .isVpn(false)
                .isTor(false)
                .isRelay(false)
                .isResproxy(false)
                .isDcproxy(false)
                .isMobproxy(false)
                .isPubproxy(false)
                .isHosting(false)
                .isCdn(false)
                .isAnycast(false)
                .isCarrier(false)
                .isSatellite(false)
                .isSsh(false)
                .isSmtp(false)
                .isWeb(false)
                .isDnsResolver(false)
                .isBittorrent(false)
                .vpn(new ClassDetail())
                .tor(new ClassDetail())
                .relay(new ClassDetail())
                .resproxy(new ProxyDetail())
                .dcproxy(new ProxyDetail())
                .mobproxy(new ProxyDetail())
                .pubproxy(new PubproxyDetail())
                .hosting(new ClassDetail())
                .cdn(new ClassDetail())
                .carrier(new CarrierDetail())
                .satellite(new SatelliteDetail())
                .ssh(new SshDetail())
                .smtp(new SmtpDetail())
                .web(new ArrayList<>())
                .dnsResolver(new DnsResolverDetail())
                .bittorrent(new BitTorrentDetail())
                .location(new Location()
                        .continent("")
                        .continentName("")
                        .country("")
                        .countryName("")
                        .timezone("")
                        .currencyCode("")
                        .currencyName("")
                        .currencySymbol("")
                        .flagEmoji("")
                        .flagUnicode("")
                        .region("")
                        .regionCode("")
                        .city("")
                        .postalCode("")
                        .latitude(BigDecimal.ZERO)
                        .longitude(BigDecimal.ZERO)
                        .accuracyRadius(0)
                        .geonameId("")
                        .dmaCode("")
                        .lastChanged(""))
                .asn(new Asn().asn(0).name("").domain("").type("").prefix("").country("").rpkiStatus(""))
                .company(new Company().name("").domain("").type("").country(""))
                .hostname("")
                .abuse(new AbuseContact()
                        .email("")
                        .name("")
                        .phone("")
                        .address("")
                        .country("")
                        .network("")
                        .validated(false)
                        .unvalidatedSince(""))
                .whois(new Whois()
                        .id("")
                        .name("")
                        .org("")
                        .country("")
                        .status("")
                        .source("")
                        .domain("")
                        .abuseEmail("")
                        .asn(0))
                .rwhois(new Rwhois().id("").name("").country("").source("").domain("").abuseEmail(""))
                .hostedDomains(new HostedDomains().total(0));
        return new Result(r);
    }

    /** The address that was looked up, as parsed: {@code ::ffff:a.b.c.d} answers as {@code a.b.c.d}. */
    public String ip() {
        return raw.getIp();
    }

    /**
     * Whether the address is reserved space: private, loopback, documentation and the like. The
     * client answers these itself, without a request.
     */
    public boolean isBogon() {
        return Boolean.TRUE.equals(raw.getIsBogon());
    }

    /** In VPN IP: a VPN service's server. Paid plans. */
    public Optional<Boolean> isVpn() {
        return Optional.ofNullable(raw.getIsVpn());
    }

    /** In Tor IP: a Tor exit. Every plan includes this. */
    public boolean isTor() {
        return Boolean.TRUE.equals(raw.getIsTor());
    }

    /** In Relay IP: an anonymizing relay such as iCloud Private Relay. Every plan includes this. */
    public boolean isRelay() {
        return Boolean.TRUE.equals(raw.getIsRelay());
    }

    public Optional<Boolean> isResproxy() {
        return Optional.ofNullable(raw.getIsResproxy());
    }

    public Optional<Boolean> isDcproxy() {
        return Optional.ofNullable(raw.getIsDcproxy());
    }

    public Optional<Boolean> isMobproxy() {
        return Optional.ofNullable(raw.getIsMobproxy());
    }

    /** Whether a public proxy listens on the address, or its traffic left from it. Paid plans. */
    public Optional<Boolean> isPubproxy() {
        return Optional.ofNullable(raw.getIsPubproxy());
    }

    public Optional<Boolean> isHosting() {
        return Optional.ofNullable(raw.getIsHosting());
    }

    public Optional<Boolean> isCdn() {
        return Optional.ofNullable(raw.getIsCdn());
    }

    public boolean isAnycast() {
        return Boolean.TRUE.equals(raw.getIsAnycast());
    }

    public boolean isCarrier() {
        return Boolean.TRUE.equals(raw.getIsCarrier());
    }

    public boolean isSatellite() {
        return Boolean.TRUE.equals(raw.getIsSatellite());
    }

    public boolean isSsh() {
        return Boolean.TRUE.equals(raw.getIsSsh());
    }

    public boolean isSmtp() {
        return Boolean.TRUE.equals(raw.getIsSmtp());
    }

    public boolean isWeb() {
        return Boolean.TRUE.equals(raw.getIsWeb());
    }

    public boolean isDnsResolver() {
        return Boolean.TRUE.equals(raw.getIsDnsResolver());
    }

    public boolean isBittorrent() {
        return Boolean.TRUE.equals(raw.getIsBittorrent());
    }

    public Optional<ClassDetail> vpn() {
        return Optional.ofNullable(raw.getVpn());
    }

    public ClassDetail tor() {
        return raw.getTor();
    }

    public ClassDetail relay() {
        return raw.getRelay();
    }

    public Optional<ProxyDetail> resproxy() {
        return Optional.ofNullable(raw.getResproxy());
    }

    public Optional<ProxyDetail> dcproxy() {
        return Optional.ofNullable(raw.getDcproxy());
    }

    public Optional<ProxyDetail> mobproxy() {
        return Optional.ofNullable(raw.getMobproxy());
    }

    /**
     * Detail for {@link #isPubproxy()}: which end of a proxy the address is, its protocols, and how
     * many ports and proxies it counts at each end.
     */
    public Optional<PubproxyDetail> pubproxy() {
        return Optional.ofNullable(raw.getPubproxy());
    }

    public Optional<ClassDetail> hosting() {
        return Optional.ofNullable(raw.getHosting());
    }

    public Optional<ClassDetail> cdn() {
        return Optional.ofNullable(raw.getCdn());
    }

    public Optional<CarrierDetail> carrier() {
        return Optional.ofNullable(raw.getCarrier());
    }

    public Optional<SatelliteDetail> satellite() {
        return Optional.ofNullable(raw.getSatellite());
    }

    public Optional<SshDetail> ssh() {
        return Optional.ofNullable(raw.getSsh());
    }

    public Optional<SmtpDetail> smtp() {
        return Optional.ofNullable(raw.getSmtp());
    }

    /** A row per port a web server answered on, lowest first, and empty when {@link #isWeb()} is false. */
    public Optional<List<WebDetail>> web() {
        return Optional.ofNullable(raw.getWeb());
    }

    public Optional<DnsResolverDetail> dnsResolver() {
        return Optional.ofNullable(raw.getDnsResolver());
    }

    public Optional<BitTorrentDetail> bittorrent() {
        return Optional.ofNullable(raw.getBittorrent());
    }

    /**
     * Where the address is, on every answer. The country and its details are on every plan, an empty
     * string where not known; the city, region, coordinates and the rest are null where your plan
     * leaves them out.
     */
    public Location location() {
        return raw.getLocation();
    }

    /** The AS announcing the address, on every answer: 0 where none does. */
    public Asn asn() {
        return raw.getAsn();
    }

    /** The organization the address is assigned to, on every answer. */
    public Company company() {
        return raw.getCompany();
    }

    /** The name the address's reverse DNS record points to, empty when it has none. */
    public String hostname() {
        return raw.getHostname();
    }

    /** Where to report abuse from the address, on every answer. */
    public AbuseContact abuse() {
        return raw.getAbuse();
    }

    public Optional<Whois> whois() {
        return Optional.ofNullable(raw.getWhois());
    }

    public Optional<Rwhois> rwhois() {
        return Optional.ofNullable(raw.getRwhois());
    }

    public Optional<HostedDomains> hostedDomains() {
        return Optional.ofNullable(raw.getHostedDomains());
    }

    /**
     * On answers to a key on the free plan only: {@code false} while its organization's daily
     * trial lasts (every field for the first 100 lookups each UTC day), {@code true} once the
     * answer has Unlimited's.
     */
    public Optional<Boolean> trialExpired() {
        return Optional.ofNullable(raw.getTrialExpired());
    }

    /**
     * The response exactly as it came off the wire, with its original names.
     *
     * <p>For a bogon this is synthesized locally rather than served.
     */
    public LookupResponse raw() {
        return raw;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Result)) {
            return false;
        }
        return Objects.equals(raw, ((Result) other).raw);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(raw);
    }

    @Override
    public String toString() {
        return "Result{ip=" + ip() + ", isVpn=" + isVpn() + ", isBogon=" + isBogon() + "}";
    }
}
