package io.internetdata;

/**
 * The file formats a database is published in.
 *
 * <p>Which formats a given database is BUILT in is the API's answer, not this enum's: the
 * {@code _provider} catalogs are keyed by provider id, so no MMDB exists for them and asking for
 * one is a {@link ErrorKind#BAD_REQUEST}. Read the list off
 * {@link io.internetdata.model.DatabaseVersion#getFormats()}.
 */
public enum DatabaseFormat {
    CSVGZ("csvgz"),
    MMDB("mmdb");

    private final String wireValue;

    DatabaseFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The value the API expects, which is the lower-case spelling. */
    public String wireValue() {
        return wireValue;
    }
}
